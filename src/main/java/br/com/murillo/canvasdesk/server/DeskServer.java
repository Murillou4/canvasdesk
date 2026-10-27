package br.com.murillo.canvasdesk.server;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.core.CanvasStore;
import br.com.murillo.canvasdesk.network.CanvasPayload;
import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server authority for sessions and physical projections. No client classes are referenced. */
public final class DeskServer {
    private static final Map<MinecraftServer, DeskServer> INSTANCES = new ConcurrentHashMap<>();
    private static final Direction[] SIDES = {Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST};
    private final MinecraftServer server;
    public final CanvasStore store;
    private final Path indexFile;
    private final Map<String,Module> modules = new LinkedHashMap<>();
    private final Map<String,DeskBlockEntity> loaded = new HashMap<>();
    private final Map<UUID,Session> sessions = new HashMap<>();
    private final Map<UUID,CanvasPayload.Assembler> assemblers = new HashMap<>();
    private final Map<UUID,Map<String,Long>> tileRevisions = new HashMap<>();
    private final ArrayDeque<String> normalizeQueue = new ArrayDeque<>();
    private NormalizeJob normalize;
    private long sequence = System.currentTimeMillis(), ticks;
    private boolean indexDirty;

    private DeskServer(MinecraftServer server) throws IOException {
        this.server=server;
        Path directory=server.getWorldPath(LevelResource.ROOT).resolve("canvasdesk");
        Files.createDirectories(directory);
        store=new CanvasStore(directory.resolve("documents"));
        indexFile=directory.resolve("modules.json");
        if(Files.exists(indexFile)) {
            JsonObject root=JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
            sequence=root.get("sequence").getAsLong();
            for(JsonElement e:root.getAsJsonArray("modules")) {
                Module module=new Gson().fromJson(e,Module.class); modules.put(module.key(),module);
            }
        }
    }
    public static DeskServer get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> { try { return new DeskServer(s); } catch (IOException e) { throw new UncheckedIOException(e); } });
    }
    public static void start(MinecraftServer server) { get(server); }
    public static void stop(MinecraftServer server) {
        DeskServer service = INSTANCES.remove(server);
        if (service != null) try { service.saveIndex(); service.store.close(); } catch (Exception e) { CanvasDesk.LOGGER.error("Canvas Desk save failed", e); }
    }
    private static String dimension(ServerLevel level){return level.dimension().identifier().toString();}
    private static String key(String dim,BlockPos pos){return dim+":"+pos.asLong();}
    private static String key(ServerLevel level,BlockPos pos){return key(dimension(level),pos);}

    public void loadChunk(ServerLevel level,LevelChunk chunk) {
        Set<String> actual=new HashSet<>();
        for(var entity:chunk.getBlockEntities().values()) if(entity instanceof DeskBlockEntity desk) {
            actual.add(key(level,desk.getBlockPos()));
            if(desk.workspace!=null) reconcile(level,desk);
        }
        // Chunk contents settle physical truth; absence never removes the persistent document.
        List<Module> stale=modules.values().stream().filter(m->m.dimension.equals(dimension(level))
                && (m.x>>4)==chunk.getPos().x() && (m.z>>4)==chunk.getPos().z() && !actual.contains(m.key())).toList();
        for(Module m:stale){modules.remove(m.key()); loaded.remove(m.key()); indexDirty=true;}
    }
    public void unloadChunk(ServerLevel level,LevelChunk chunk){loaded.entrySet().removeIf(e->e.getValue().getLevel()==level
            && (e.getValue().getBlockPos().getX()>>4)==chunk.getPos().x() && (e.getValue().getBlockPos().getZ()>>4)==chunk.getPos().z());}

    private void reconcile(ServerLevel level,DeskBlockEntity desk) {
        String key=key(level,desk.getBlockPos());
        Module known=modules.get(key);
        if(known!=null && known.moduleId.equals(desk.moduleId.toString())) apply(known,desk);
        else {
            // Creative copies get a fresh module identity; references still address one document.
            if(modules.values().stream().anyMatch(m->m.moduleId.equals(desk.moduleId.toString()))) desk.moduleId=UUID.randomUUID();
            Module module=Module.of(level,desk); modules.put(key,module); indexDirty=true;
        }
        loaded.put(key,desk); queue(key); updateConnections(level,desk.getBlockPos());
    }

    public void placed(ServerLevel level,DeskBlockEntity desk,ServerPlayer actor,ItemStack stack) {
        CustomData ref=stack.get(DataComponents.CUSTOM_DATA);
        if(ref!=null) desk.readReference(ref.copyTag());
        List<DeskBlockEntity> neighbors=new ArrayList<>();
        for(Direction direction:SIDES) if(level.getBlockEntity(desk.getBlockPos().relative(direction)) instanceof DeskBlockEntity next && next.workspace!=null) neighbors.add(next);
        neighbors.sort(Comparator.comparingLong(d->d.projectionOrder));
        DeskBlockEntity anchor=null;
        for(DeskBlockEntity neighbor:neighbors) {
            try { if(store.view(neighbor.workspace,actor.getUUID(),0,0,0,0).get("editable").getAsBoolean()){anchor=neighbor;break;} }
            catch(RuntimeException e){CanvasDesk.LOGGER.warn("Unavailable Canvas Desk workspace {}",neighbor.workspace);}
        }
        if(desk.workspace==null) {
            if(anchor!=null) {
                desk.workspace=store.resolve(anchor.workspace);
                double[] offset=store.projectionOffset(anchor.workspace);
                desk.tileX=anchor.tileX+offset[0]+256.0*(desk.getBlockPos().getX()-anchor.getBlockPos().getX());
                desk.tileY=anchor.tileY+offset[1]+256.0*(desk.getBlockPos().getZ()-anchor.getBlockPos().getZ());
                desk.projectionOrder=anchor.projectionOrder;
            } else {desk.workspace=store.create(actor.getUUID()); desk.tileX=desk.tileY=0; desk.projectionOrder=++sequence;}
        } else {
            try {store.resolve(desk.workspace);} catch(RuntimeException e) {
                actor.sendSystemMessage(Component.literal("Canvas Desk: referência de outro mundo ou documento indisponível. O vínculo foi preservado; use outro módulo para criar uma mesa."));
                reconcile(level,desk); desk.changed(); return;
            }
            // A replaced item starts a new physical projection. An existing connected
            // surface wins; preserving the old order would let a moved tile shift it.
            desk.projectionOrder=++sequence;
            if(anchor!=null && store.resolve(desk.workspace).equals(store.resolve(anchor.workspace))) {
                double[] offset=store.projectionOffset(anchor.workspace);
                desk.workspace=store.resolve(anchor.workspace);
                desk.tileX=anchor.tileX+offset[0]+256.0*(desk.getBlockPos().getX()-anchor.getBlockPos().getX());
                desk.tileY=anchor.tileY+offset[1]+256.0*(desk.getBlockPos().getZ()-anchor.getBlockPos().getZ());
                desk.projectionOrder=anchor.projectionOrder;
            }
        }
        if(desk.projectionOrder==0) desk.projectionOrder=++sequence;
        // Any structural change invalidates approvals before calculating fresh adjacent unions.
        store.invalidateMerges(desk.workspace);
        for(DeskBlockEntity neighbor:neighbors) {
            try {
                store.invalidateMerges(neighbor.workspace);
                if(!store.resolve(desk.workspace).equals(store.resolve(neighbor.workspace))) {
                    double[] a=store.projectionOffset(desk.workspace), b=store.projectionOffset(neighbor.workspace);
                    double dx=desk.tileX+a[0]+256.0*(neighbor.getBlockPos().getX()-desk.getBlockPos().getX())-neighbor.tileX-b[0];
                    double dy=desk.tileY+a[1]+256.0*(neighbor.getBlockPos().getZ()-desk.getBlockPos().getZ())-neighbor.tileY-b[1];
                    JsonObject result=store.requestMerge(desk.workspace,neighbor.workspace,dx,dy,actor.getUUID());
                    actor.sendSystemMessage(Component.literal("Canvas Desk: "+message(result,"Conexão preparada; donos podem aprovar na opção Compartilhar.")));
                }
            }catch(RuntimeException e){actor.sendSystemMessage(Component.literal("Canvas Desk: "+e.getMessage()));}
        }
        reconcile(level,desk); desk.changed(); indexDirty=true; saveIndexSafe(); refreshAll("Mesa conectada; documento preservado.");
    }
    public void removed(ServerLevel level,BlockPos pos) {
        Module old=modules.remove(key(level,pos)); loaded.remove(key(level,pos)); indexDirty=true;
        if(old!=null) try{store.invalidateMerges(UUID.fromString(old.workspace));}catch(RuntimeException ignored){}
        for(Direction side:SIDES){BlockPos p=pos.relative(side);updateConnections(level,p);queue(key(level,p));}
        saveIndexSafe();
    }
    private void queue(String key){if(!normalizeQueue.contains(key)) normalizeQueue.add(key);}
    private void apply(Module module,DeskBlockEntity desk){
        desk.workspace=UUID.fromString(module.workspace);desk.tileX=module.tileX;desk.tileY=module.tileY;desk.projectionOrder=module.order;desk.changed();
    }
    private void updateConnections(ServerLevel level,BlockPos pos) {
        if(!(level.getBlockEntity(pos) instanceof DeskBlockEntity desk))return;
        var state=level.getBlockState(pos);
        for(Direction side:SIDES) {
            boolean connects=false;
            if(desk.workspace!=null && level.getBlockEntity(pos.relative(side)) instanceof DeskBlockEntity other && other.workspace!=null)
                try{connects=store.resolve(desk.workspace).equals(store.resolve(other.workspace));}catch(RuntimeException ignored){}
            var property=switch(side){case NORTH->DeskBlock.NORTH;case SOUTH->DeskBlock.SOUTH;case EAST->DeskBlock.EAST;default->DeskBlock.WEST;};
            state=state.setValue(property,connects);
        }
        if(!state.equals(level.getBlockState(pos)))level.setBlock(pos,state,3);
    }

    public void open(ServerPlayer player,DeskBlockEntity desk) {
        if(desk.workspace==null)return;
        Session session=new Session(player.level(),desk.getBlockPos(),desk.moduleId,desk.workspace,desk.tileX,desk.tileY);
        double[] offset=store.projectionOffset(desk.workspace);session.shift(offset[0],offset[1]);
        sessions.put(player.getUUID(),session);sendView(player,session,true,null);
    }
    public void receive(ServerPlayer player,CanvasPayload payload) {
        try {
            if(payload.count()>176)throw new IllegalArgumentException("Operação excede 1 MB");
            String data=assemblers.computeIfAbsent(player.getUUID(),k->new CanvasPayload.Assembler()).accept(payload);
            if(data==null)return;
            JsonObject request=JsonParser.parseString(data).getAsJsonObject();
            String action=request.get("action").getAsString();
            if(action.equals("tiles")){sendTiles(player,request);return;}
            Session session=sessions.get(player.getUUID());
            if(session==null || !valid(player,session)) {sessions.remove(player.getUUID());return;}
            if(session.lastTick==ticks && ++session.actions>30)throw new IllegalArgumentException("Muitas ações; tente novamente");
            if(session.lastTick!=ticks){session.lastTick=ticks;session.actions=0;}
            if(action.equals("close")){sessions.remove(player.getUUID());return;}
            if(action.equals("view")) {
                session.minX=finite(request,"minX");session.minY=finite(request,"minY");session.maxX=finite(request,"maxX");session.maxY=finite(request,"maxY");
                if(session.maxX<session.minX||session.maxY<session.minY)throw new IllegalArgumentException("Área inválida");
                sendView(player,session,false,null);return;
            }
            JsonObject result=store.action(session.workspace,player.getUUID(),request);
            if(action.equals("approveMerge") && result.get("ok").getAsBoolean()) {
                for(String key:modules.keySet())queue(key);
            }
            JsonObject viewAck=new JsonObject();viewAck.addProperty("ackAction",action);
            if(request.has("operationId"))viewAck.add("ackOperation",request.get("operationId"));
            viewAck.addProperty("ok",result.get("ok").getAsBoolean());
            sendView(player,session,false,message(result,null),viewAck);
            refreshOthers(player.getUUID());
            tileRevisions.clear();
        }catch(RuntimeException e){
            CanvasDesk.LOGGER.warn("Rejected canvas operation: {}",e.toString());
            Session session=sessions.get(player.getUUID());
            if(session!=null)sendView(player,session,false,e.getMessage());
        }
    }
    private static double finite(JsonObject o,String key){double value=o.get(key).getAsDouble();if(!Double.isFinite(value)||Math.abs(value)>1e15)throw new IllegalArgumentException("Coordenada inválida");return value;}
    private boolean valid(ServerPlayer player,Session session){return player.level()==session.level && player.distanceToSqr(session.pos.getX()+0.5,session.pos.getY()+0.5,session.pos.getZ()+0.5)<=100
            && session.level.getBlockEntity(session.pos) instanceof DeskBlockEntity desk && desk.moduleId.equals(session.module);}
    private void sendView(ServerPlayer player,Session session,boolean open,String feedback) {
        sendView(player,session,open,feedback,null);
    }
    private void sendView(ServerPlayer player,Session session,boolean open,String feedback,JsonObject acknowledgement) {
        try {
            double[] offset=store.projectionOffset(session.workspace);
            double shiftX=offset[0]-session.offsetX,shiftY=offset[1]-session.offsetY;
            session.shift(shiftX,shiftY);
            JsonObject view=store.view(session.workspace,player.getUUID(),session.minX,session.minY,session.maxX,session.maxY);
            view.addProperty("kind","state");view.addProperty("open",open);
            view.addProperty("originX",session.originX+offset[0]);view.addProperty("originY",session.originY+offset[1]);
            if(shiftX!=0||shiftY!=0){view.addProperty("viewShiftX",shiftX);view.addProperty("viewShiftY",shiftY);}
            JsonArray players=new JsonArray();for(ServerPlayer p:server.getPlayerList().getPlayers()) {
                JsonObject entry=new JsonObject();entry.addProperty("id",p.getUUID().toString());entry.addProperty("name",p.getName().getString());players.add(entry);
            }view.add("players",players);if(feedback!=null)view.addProperty("message",feedback);
            if(acknowledgement!=null)for(var entry:acknowledgement.entrySet())view.add(entry.getKey(),entry.getValue());
            send(player,view);
        }catch(RuntimeException e){player.sendSystemMessage(Component.literal("Canvas Desk: "+e.getMessage()));}
    }
    private void refreshAll(String feedback){
        for(var entry:sessions.entrySet()){ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null&&valid(player,entry.getValue()))sendView(player,entry.getValue(),false,feedback);}
    }
    private void refreshOthers(UUID actor){
        for(var entry:sessions.entrySet())if(!entry.getKey().equals(actor)){ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null&&valid(player,entry.getValue()))sendView(player,entry.getValue(),false,null);}
    }
    private void sendTiles(ServerPlayer player,JsonObject request) {
        JsonArray positions=request.getAsJsonArray("positions");
        if(positions==null||positions.size()>32)return;
        Map<String,Long> sent=tileRevisions.computeIfAbsent(player.getUUID(),k->new LinkedHashMap<>());
        if(sent.size()>2048)sent.clear();
        for(JsonElement element:positions) {
            BlockPos pos=BlockPos.of(element.getAsLong());
            if(player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>80*80 || !player.level().hasChunkAt(pos))continue;
            if(!(player.level().getBlockEntity(pos) instanceof DeskBlockEntity desk)||desk.workspace==null)continue;
            try {
                double[] offset=store.projectionOffset(desk.workspace);double x=desk.tileX+offset[0],y=desk.tileY+offset[1];
                JsonObject tile=store.view(desk.workspace,player.getUUID(),x,y,x+256,y+256);
                String key=pos.asLong()+":"+tile.get("workspace").getAsString()+":"+x+":"+y;
                long rev=tile.get("revision").getAsLong();if(Objects.equals(sent.get(key),rev))continue;
                sent.put(key,rev);tile.addProperty("kind","tile");tile.addProperty("pos",pos.asLong());tile.addProperty("tileX",x);tile.addProperty("tileY",y);send(player,tile);
            }catch(RuntimeException e){CanvasDesk.LOGGER.debug("Tile unavailable {}",pos);}
        }
    }
    public static void send(ServerPlayer player,JsonObject json){for(CanvasPayload part:CanvasPayload.split(json.toString()))ServerPlayNetworking.send(player,part);}

    public void tick(){
        ticks++;
        if(normalize==null && !normalizeQueue.isEmpty()) {
            String seed=normalizeQueue.poll();Module module=modules.get(seed);
            if(module!=null)try{normalize=new NormalizeJob(module);}catch(RuntimeException ignored){}
        }
        if(normalize!=null && normalize.step(512)){normalizeQueue.removeIf(normalize.seen::contains);normalize=null;indexDirty=true;tileRevisions.clear();}
        if(ticks%20==0) {
            sessions.entrySet().removeIf(e->{ServerPlayer p=server.getPlayerList().getPlayer(e.getKey());return p==null||!valid(p,e.getValue());});
            assemblers.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            tileRevisions.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            if(indexDirty)saveIndexSafe();
        }
    }
    private final class NormalizeJob {
        final UUID workspace;final ArrayDeque<Module> frontier=new ArrayDeque<>();final Set<String> seen=new HashSet<>();
        final List<Module> component=new ArrayList<>();Module anchor;int applyIndex=-1;
        NormalizeJob(Module seed){workspace=store.resolve(UUID.fromString(seed.workspace));frontier.add(seed);}
        boolean step(int budget){
            while(budget-->0){
                if(applyIndex<0) {
                    Module m=frontier.poll();if(m==null){applyIndex=0;continue;}
                    if(!seen.add(m.key()))continue;
                    try{if(!workspace.equals(store.resolve(UUID.fromString(m.workspace))))continue;}catch(RuntimeException e){continue;}
                    component.add(m);if(anchor==null||m.order<anchor.order||(m.order==anchor.order&&m.moduleId.compareTo(anchor.moduleId)<0))anchor=m;
                    for(Direction side:SIDES){Module n=modules.get(key(m.dimension,m.pos().relative(side)));if(n!=null&&!seen.contains(n.key()))frontier.add(n);}
                }else {
                    if(applyIndex>=component.size())return true;
                    Module m=component.get(applyIndex++);double[] offset=store.projectionOffset(UUID.fromString(anchor.workspace));
                    double x=anchor.tileX+offset[0]+256.0*(m.x-anchor.x),y=anchor.tileY+offset[1]+256.0*(m.z-anchor.z);
                    m.workspace=workspace.toString();m.tileX=x;m.tileY=y;m.order=anchor.order;
                    DeskBlockEntity desk=loaded.get(m.key());if(desk!=null&&desk.getLevel() instanceof ServerLevel level){apply(m,desk);updateConnections(level,m.pos());}
                }
            }return false;
        }
    }
    private void saveIndexSafe(){try{saveIndex();}catch(IOException e){CanvasDesk.LOGGER.error("Failed to persist module projection index",e);}}
    private void saveIndex() throws IOException {
        if(!indexDirty && Files.exists(indexFile))return;
        JsonObject root=new JsonObject();root.addProperty("version",1);root.addProperty("sequence",sequence);root.add("modules",new Gson().toJsonTree(modules.values()));
        Path temp=indexFile.resolveSibling("modules.json.tmp");Files.writeString(temp,root.toString());
        try{Files.move(temp,indexFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,indexFile,StandardCopyOption.REPLACE_EXISTING);}
        indexDirty=false;
    }
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("canvasdesk")
                .then(Commands.literal("list").executes(context->{
                    CommandSourceStack source=context.getSource();ServerPlayer player=source.getPlayerOrException();
                    JsonArray list=get(source.getServer()).store.list(player.getUUID(),source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
                    for(JsonElement entry:list){JsonObject doc=entry.getAsJsonObject();source.sendSuccess(()->Component.literal(doc.get("id").getAsString()+" — "+doc.get("name").getAsString()),false);}return list.size();
                }))
                .then(Commands.literal("recover").then(Commands.argument("id",StringArgumentType.word()).executes(context->{
                    CommandSourceStack source=context.getSource();ServerPlayer player=source.getPlayerOrException();DeskServer service=get(source.getServer());
                    UUID id=UUID.fromString(StringArgumentType.getString(context,"id"));
                    boolean allowed=source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
                    for(JsonElement entry:service.store.list(player.getUUID(),false))if(entry.getAsJsonObject().get("id").getAsString().equals(service.store.resolve(id).toString()))allowed=true;
                    if(!allowed){source.sendFailure(Component.literal("Só os donos podem recuperar este documento."));return 0;}
                    ItemStack stack=new ItemStack(CanvasDesk.DESK_ITEM);CompoundTag tag=new CompoundTag();tag.putString("workspace",service.store.resolve(id).toString());tag.putLong("projectionOrder",++service.sequence);
                    stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));if(!player.getInventory().add(stack))net.minecraft.world.level.block.Block.popResource(player.level(),player.blockPosition(),stack);
                    source.sendSuccess(()->Component.literal("Módulo vinculado recuperado; o documento não foi duplicado."),false);return 1;
                }))));
    }
    private static String message(JsonObject object,String fallback){return object.has("message")?object.get("message").getAsString():fallback;}
    private static final class Session {
        final ServerLevel level;final BlockPos pos;final UUID module,workspace;final double originX,originY;
        double minX,minY,maxX,maxY,offsetX,offsetY;long lastTick;int actions;
        Session(ServerLevel level,BlockPos pos,UUID module,UUID workspace,double x,double y){this.level=level;this.pos=pos.immutable();this.module=module;this.workspace=workspace;originX=x;originY=y;minX=x-1024;minY=y-1024;maxX=x+2048;maxY=y+2048;}
        void shift(double dx,double dy){minX+=dx;minY+=dy;maxX+=dx;maxY+=dy;offsetX+=dx;offsetY+=dy;}
    }
    private static final class Module {
        String dimension,moduleId,workspace;int x,y,z;double tileX,tileY;long order;
        BlockPos pos(){return new BlockPos(x,y,z);}String key(){return DeskServer.key(dimension,pos());}
        static Module of(ServerLevel level,DeskBlockEntity desk){Module m=new Module();m.dimension=DeskServer.dimension(level);m.moduleId=desk.moduleId.toString();m.workspace=desk.workspace.toString();m.x=desk.getBlockPos().getX();m.y=desk.getBlockPos().getY();m.z=desk.getBlockPos().getZ();m.tileX=desk.tileX;m.tileY=desk.tileY;m.order=desk.projectionOrder;return m;}
    }
}
