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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
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
    private final MinecraftServer server;
    public final CanvasStore store;
    private final SharedMedia media;
    private final Path indexFile;
    private final Map<String,Module> modules = new LinkedHashMap<>();
    private final Map<String,DeskBlockEntity> loaded = new HashMap<>();
    private final Map<UUID,Session> sessions = new HashMap<>();
    private final Map<UUID,CanvasPayload.Assembler> assemblers = new HashMap<>();
    private final Map<UUID,Map<String,Long>> tileRevisions = new HashMap<>();
    private final ArrayDeque<String> normalizeQueue = new ArrayDeque<>();
    private final ArrayDeque<PendingChunk> chunkReconciliations = new ArrayDeque<>();
    private record PendingChunk(ServerLevel level,LevelChunk chunk) {}
    private NormalizeJob normalize;
    private long sequence = System.currentTimeMillis(), ticks;
    private boolean indexDirty;
    private long surfaceRevision,topologyEpoch;
    private final Map<String,SurfaceLayout> surfaces=new HashMap<>();
    private final Set<SurfaceLayout> dirtySurfaces=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID,LinkedHashMap<UUID,JsonObject>> assistReceipts=new HashMap<>();
    private final Map<UUID,Long> assistTicks=new HashMap<>();
    private final Map<UUID,LinkedHashMap<UUID,JsonObject>> checklistReceipts=new HashMap<>();
    private final Map<UUID,Long> checklistTicks=new HashMap<>();
    private final Map<UUID,LinkedHashSet<Long>> tilePush=new LinkedHashMap<>();
    private final Map<UUID,BlockPos> surfaceWatchers=new HashMap<>();

    private DeskServer(MinecraftServer server) throws IOException {
        this.server=server;
        Path directory=server.getWorldPath(LevelResource.ROOT).resolve("canvasdesk");
        Files.createDirectories(directory);
        store=new CanvasStore(directory.resolve("documents"));
        media=new SharedMedia(server,store);
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
        if (service != null) {
            try { service.saveIndex(); } catch (Exception e) { CanvasDesk.LOGGER.error("Canvas Desk index save failed", e); }
            finally { try { service.store.close(); } catch (Exception e) { CanvasDesk.LOGGER.error("Canvas Desk document close failed", e); } }
        }
    }
    private static String dimension(ServerLevel level){return level.dimension().identifier().toString();}
    private static String key(String dim,BlockPos pos){return dim+":"+pos.asLong();}
    private static String key(ServerLevel level,BlockPos pos){return key(dimension(level),pos);}

    public void loadChunk(ServerLevel level,LevelChunk chunk) {
        // CHUNK_LOAD fires inside the FULL chunk future. Marking a block dirty here
        // would synchronously request that same unfinished future and deadlock a reload.
        chunkReconciliations.add(new PendingChunk(level,chunk));
    }
    private void reconcileChunk(ServerLevel level,LevelChunk chunk) {
        Set<String> actual=new HashSet<>();
        for(var entity:chunk.getBlockEntities().values()) if(entity instanceof DeskBlockEntity desk) {
            actual.add(key(level,desk.getBlockPos()));
            if(desk.workspace!=null) reconcile(level,desk);
        }
        // Chunk contents settle physical truth; absence never removes the persistent document.
        List<Module> stale=modules.values().stream().filter(m->m.dimension.equals(dimension(level))
                && (m.x>>4)==chunk.getPos().x() && (m.z>>4)==chunk.getPos().z() && !actual.contains(m.key())).toList();
        for(Module m:stale)removed(level,m.pos());
    }
    public void unloadChunk(ServerLevel level,LevelChunk chunk){chunkReconciliations.removeIf(job->job.level==level&&job.chunk==chunk);loaded.entrySet().removeIf(e->e.getValue().getLevel()==level
            && (e.getValue().getBlockPos().getX()>>4)==chunk.getPos().x() && (e.getValue().getBlockPos().getZ()>>4)==chunk.getPos().z());}

    private void reconcile(ServerLevel level,DeskBlockEntity desk) {
        String key=key(level,desk.getBlockPos());
        Module known=modules.get(key);
        if(known!=null && known.moduleId.equals(desk.moduleId.toString())) {
            known.color=desk.getBlockState().getValue(DeskBlock.COLOR).getSerializedName();
            known.horizontal=BoardPlane.horizontal(desk.getBlockState());known.wall=desk.getBlockState().getValue(DeskBlock.WALL);apply(known,desk);
        }
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
        Direction facing=desk.getBlockState().getValue(DeskBlock.FACING);
        for(Direction direction:BoardPlane.sides(facing,BoardPlane.horizontal(desk.getBlockState()))) if(level.getBlockEntity(desk.getBlockPos().relative(direction)) instanceof DeskBlockEntity next
                && BoardPlane.compatible(desk.getBlockState(),next.getBlockState()) && next.workspace!=null) neighbors.add(next);
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
                desk.tileX=anchor.tileX+offset[0]+256.0*BoardPlane.horizontal(facing,anchor.getBlockPos(),desk.getBlockPos());
                desk.tileY=anchor.tileY+offset[1]+256.0*BoardPlane.vertical(facing,BoardPlane.horizontal(desk.getBlockState()),anchor.getBlockPos(),desk.getBlockPos());
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
                desk.tileX=anchor.tileX+offset[0]+256.0*BoardPlane.horizontal(facing,anchor.getBlockPos(),desk.getBlockPos());
                desk.tileY=anchor.tileY+offset[1]+256.0*BoardPlane.vertical(facing,BoardPlane.horizontal(desk.getBlockState()),anchor.getBlockPos(),desk.getBlockPos());
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
                    double dx=desk.tileX+a[0]+256.0*BoardPlane.horizontal(facing,desk.getBlockPos(),neighbor.getBlockPos())-neighbor.tileX-b[0];
                    double dy=desk.tileY+a[1]+256.0*BoardPlane.vertical(facing,BoardPlane.horizontal(desk.getBlockState()),desk.getBlockPos(),neighbor.getBlockPos())-neighbor.tileY-b[1];
                    JsonObject result=store.requestMerge(desk.workspace,neighbor.workspace,dx,dy,actor.getUUID());
                    if(result.has("merged"))media.merged(store.resolve(desk.workspace));
                    actor.sendSystemMessage(Component.literal("Canvas Desk: "+message(result,"Conexão preparada; donos podem aprovar na opção Compartilhar.")));
                }
            }catch(RuntimeException e){actor.sendSystemMessage(Component.literal("Canvas Desk: "+e.getMessage()));}
        }
        reconcile(level,desk); desk.changed(); indexDirty=true; saveIndexSafe();
    }
    public void removed(ServerLevel level,BlockPos pos) {
        SurfaceLayout previous=surfaces.get(key(level,pos));if(previous!=null)dirtySurfaces.add(previous);
        Module old=modules.remove(key(level,pos)); loaded.remove(key(level,pos)); indexDirty=true;
        if(old!=null) try{store.invalidateMerges(UUID.fromString(old.workspace));}catch(RuntimeException ignored){}
        for(Direction side:Direction.values()){BlockPos p=pos.relative(side);updateConnections(level,p);queue(key(level,p));}
        saveIndexSafe();tileRevisions.clear();
        refreshAll(null);
    }
    private void queue(String key){
        topologyEpoch++;
        SurfaceLayout previous=surfaces.get(key);if(previous!=null)dirtySurfaces.add(previous);
        Module m=modules.get(key);
        if(m!=null)for(Direction side:BoardPlane.sides(m.front(),m.horizontal)){
            Module n=modules.get(key(m.dimension,m.pos().relative(side)));
            if(n!=null&&m.compatible(n)){SurfaceLayout surface=surfaces.get(n.key());if(surface!=null)dirtySurfaces.add(surface);}
        }
        requestSurfaceCalculation(key);
    }
    /** Opening an editor requests a snapshot without restarting an existing topology job. */
    private void requestSurfaceCalculation(String key){
        if(!normalizeQueue.contains(key))normalizeQueue.add(key);
    }
    private void apply(Module module,DeskBlockEntity desk){
        desk.workspace=UUID.fromString(module.workspace);desk.tileX=module.tileX;desk.tileY=module.tileY;desk.projectionOrder=module.order;desk.changed();
    }
    private void updateConnections(ServerLevel level,BlockPos pos) {
        if(!(level.getBlockEntity(pos) instanceof DeskBlockEntity desk))return;
        var state=level.getBlockState(pos);
        Direction facing=state.getValue(DeskBlock.FACING);
        for(Direction side:BoardPlane.sides(facing,BoardPlane.horizontal(desk.getBlockState()))) {
            boolean connects=false;
            Module other=modules.get(key(level,pos.relative(side)));
            if(desk.workspace!=null&&other!=null&&other.front()==facing&&other.horizontal==BoardPlane.horizontal(state)&&other.wall==state.getValue(DeskBlock.WALL))
                try{connects=store.resolve(desk.workspace).equals(store.resolve(UUID.fromString(other.workspace)));}catch(RuntimeException ignored){}
            var property=side==BoardPlane.down(facing,BoardPlane.horizontal(state)).getOpposite()?DeskBlock.UP:side==BoardPlane.down(facing,BoardPlane.horizontal(state))?DeskBlock.DOWN:side==BoardPlane.right(facing)?DeskBlock.RIGHT:DeskBlock.LEFT;
            state=state.setValue(property,connects);
        }
        if(!state.equals(level.getBlockState(pos)))level.setBlock(pos,state,3);
    }

    public void open(ServerPlayer player,DeskBlockEntity desk) {
        if(desk.workspace==null)return;
        try {
            store.resolve(desk.workspace);
            String moduleKey=key(player.level(),desk.getBlockPos());
            Module known=modules.get(moduleKey);
            if(known==null||!known.moduleId.equals(desk.moduleId.toString()))reconcile(player.level(),desk);
            Session existing=sessions.get(player.getUUID());
            if(existing!=null&&existing.level==player.level()&&existing.module.equals(desk.moduleId)){
                SurfaceLayout current=surfaces.get(moduleKey);
                if(current==null||dirtySurfaces.contains(current))requestSurfaceCalculation(moduleKey);
                // Repeated native clicks update the same screen. Replacing it would send close() for the new session.
                sendView(player,existing,false,null);return;
            }
            Session session=new Session(player.level(),desk.getBlockPos(),desk.moduleId,desk.workspace,desk.tileX,desk.tileY);
            double[] offset=store.projectionOffset(desk.workspace);session.shift(offset[0],offset[1]);
            sessions.put(player.getUUID(),session);session.opening=true;
            SurfaceLayout surface=surfaces.get(moduleKey);
            if(surface==null||dirtySurfaces.contains(surface))requestSurfaceCalculation(moduleKey);
            // The panel can show its loading state while the authoritative surface is calculated.
            sendView(player,session,true,null);
        }catch(RuntimeException e){
            CanvasDesk.LOGGER.warn("Could not open Canvas Desk at {} for {}",desk.getBlockPos(),player.getUUID(),e);
            player.sendSystemMessage(Component.literal("Canvas Desk: documento indisponível; a referência foi preservada."));
        }
    }
    public void receive(ServerPlayer player,CanvasPayload payload) {
        JsonObject request=null;
        try {
            if(payload.count()>176)throw new IllegalArgumentException("Operação excede 1 MB");
            String data=assemblers.computeIfAbsent(player.getUUID(),k->new CanvasPayload.Assembler()).accept(payload);
            if(data==null)return;
            request=JsonParser.parseString(data).getAsJsonObject();
            String action=request.get("action").getAsString();
            if(action.equals("tiles")){sendTiles(player,request);return;}
            if(action.equals("partyJoin")||action.equals("partyLeave")||action.equals("partyPoll")){media.party(player,request);return;}
            if(action.equals("worldSurface")){worldSurface(player,request);return;}
            if(action.equals("mediaSubscribe")||action.equals("mediaControl")){media.receive(player,request);return;}
            if(action.equals("assistPlace")){assistPlace(player,request);return;}
            if(action.equals("worldChecklist")){worldChecklist(player,request);return;}
            if(action.equals("worldMedia")){media.worldControl(player,request);return;}
            Session session=sessions.get(player.getUUID());
            if(session==null || !valid(player,session)) {closeSession(player);return;}
            if(session.lastTick==ticks && ++session.actions>30)throw new IllegalArgumentException("Muitas ações; tente novamente");
            if(session.lastTick!=ticks){session.lastTick=ticks;session.actions=0;}
            if(action.equals("close")){sessions.remove(player.getUUID());return;}
            if(action.equals("view")) {
                session.minX=finite(request,"minX");session.minY=finite(request,"minY");session.maxX=finite(request,"maxX");session.maxY=finite(request,"maxY");
                if(session.maxX<session.minX||session.maxY<session.minY)throw new IllegalArgumentException("Área inválida");
                sendView(player,session,false,null);return;
            }
            if(action.equals("edit")){validateSurfaceEdit(session,request);session.focusElement=UUID.fromString(request.getAsJsonArray("changes").get(0).getAsJsonObject().get("id").getAsString());}
            JsonObject result=store.action(session.workspace,player.getUUID(),request);
            if(result.has("merged"))media.merged(store.resolve(session.workspace));
            else if(result.get("ok").getAsBoolean()&&(action.equals("edit")||action.equals("undo")||action.equals("redo")))media.changed(store.resolve(session.workspace));
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
            if(session!=null){JsonObject ack=new JsonObject();ack.addProperty("ok",false);
                if(request!=null){if(request.has("action"))ack.add("ackAction",request.get("action"));if(request.has("operationId"))ack.add("ackOperation",request.get("operationId"));}
                sendView(player,session,false,e.getMessage(),ack);
            }
        }
    }
    /** Assisted placement has no editor session. Validate the real player/world before entering vanilla interaction. */
    private void assistPlace(ServerPlayer player,JsonObject request){
        JsonObject response=new JsonObject();response.addProperty("kind","assist");UUID operation=null;
        try{
            operation=UUID.fromString(request.get("operationId").getAsString());response.addProperty("operationId",operation.toString());
            var receipts=assistReceipts.computeIfAbsent(player.getUUID(),id->new LinkedHashMap<>());
            if(receipts.containsKey(operation)){send(player,receipts.get(operation));return;}
            if(Objects.equals(assistTicks.get(player.getUUID()),ticks))throw new IllegalArgumentException("Aguarde a próxima colocação.");
            assistTicks.put(player.getUUID(),ticks);
            if(player.isSpectator()||player.isShiftKeyDown())throw new IllegalArgumentException("Colocação assistida indisponível neste momento.");
            InteractionHand hand=InteractionHand.valueOf(request.get("hand").getAsString());
            ItemStack stack=player.getItemInHand(hand);
            if(!(stack.getItem() instanceof DeskBlockItem)||hand!=DeskPlacement.chosenHand(player)||player.getCooldowns().isOnCooldown(stack))
                throw new IllegalArgumentException("Selecione um módulo disponível para expandir.");
            double yaw=finite(request,"yaw"),pitch=finite(request,"pitch");
            if(pitch< -90||pitch>90)throw new IllegalArgumentException("Direção de mira inválida.");
            DeskPlacement.Result result=DeskPlacement.resolve(player,hand,Vec3.directionFromRotation((float)pitch,(float)(yaw%360)));
            if(result==null||!result.anchor().equals(BlockPos.of(request.get("anchor").getAsLong()))
                ||!result.moduleId().equals(UUID.fromString(request.get("moduleId").getAsString()))
                ||!result.pos().equals(BlockPos.of(request.get("target").getAsLong()))
                ||!result.expansion().name().equals(request.get("direction").getAsString()))
                throw new IllegalArgumentException("A mira ou a superfície mudou; confira a prévia e tente novamente.");
            ServerLevel level=player.level();
            if(!result.valid()||!level.hasChunkAt(result.anchor())||!level.hasChunkAt(result.pos())
                ||!level.getWorldBorder().isWithinBounds(result.anchor())||!level.getWorldBorder().isWithinBounds(result.pos())
                ||!player.mayInteract(level,result.anchor())||!player.mayInteract(level,result.pos()))
                throw new IllegalArgumentException("Não é possível expandir nessa posição.");
            // The item scopes the expected anchor/target across vanilla/Fabric protection callbacks.
            DeskBlockItem.dispatch(player,hand,result);
            boolean placed=level.getBlockState(result.pos()).getBlock()==result.state().getBlock()
                &&level.getBlockEntity(result.pos()) instanceof DeskBlockEntity;
            response.addProperty("ok",placed);
            response.addProperty("message",placed?"":"A colocação foi recusada ou a posição mudou.");
            player.inventoryMenu.broadcastChanges();
        }catch(RuntimeException error){
            response.addProperty("ok",false);response.addProperty("message",error.getMessage()==null?"Colocação inválida.":error.getMessage());
            CanvasDesk.LOGGER.debug("Rejected assisted placement for {}: {}",player.getUUID(),error.toString());
        }
        if(operation!=null){var receipts=assistReceipts.computeIfAbsent(player.getUUID(),id->new LinkedHashMap<>());receipts.put(operation,response.deepCopy());if(receipts.size()>64)receipts.remove(receipts.keySet().iterator().next());}
        send(player,response);
    }
    private void worldChecklist(ServerPlayer player,JsonObject request){
        JsonObject response=new JsonObject();response.addProperty("kind","checklist");UUID operation=null;
        BlockPos pos=null;
        try{
            operation=UUID.fromString(request.get("operationId").getAsString());response.addProperty("operationId",operation.toString());
            var receipts=checklistReceipts.computeIfAbsent(player.getUUID(),id->new LinkedHashMap<>());
            if(receipts.containsKey(operation)){send(player,receipts.get(operation));return;}
            if(Objects.equals(checklistTicks.get(player.getUUID()),ticks))throw new IllegalArgumentException("Aguarde a confirmação da tarefa.");
            checklistTicks.put(player.getUUID(),ticks);
            pos=BlockPos.of(request.get("pos").getAsLong());
            var hit=validateChecklist(player,request,pos);
            final BlockPos target=pos;final JsonObject[] applied={null};
            ChecklistInteraction.dispatch(player,hit,()->{
                try{return validateChecklist(player,request,target)!=null;}catch(RuntimeException e){return false;}
            },()->{
                var desk=(DeskBlockEntity)player.level().getBlockEntity(target);
                JsonObject action=request.deepCopy();action.addProperty("action","setChecklistItem");
                applied[0]=store.action(desk.workspace,player.getUUID(),action);
            });
            if(applied[0]==null)throw new IllegalArgumentException("A interação foi recusada ou a superfície mudou.");
            response.addProperty("ok",applied[0].get("ok").getAsBoolean());response.add("message",applied[0].get("message"));
            if(response.get("ok").getAsBoolean())refreshAll(null);
        }catch(RuntimeException error){response.addProperty("ok",false);response.addProperty("message",error.getMessage()==null?"Tarefa indisponível.":error.getMessage());}
        if(operation!=null){var receipts=checklistReceipts.computeIfAbsent(player.getUUID(),id->new LinkedHashMap<>());receipts.put(operation,response.deepCopy());if(receipts.size()>64)receipts.remove(receipts.keySet().iterator().next());}
        send(player,response);
        // A bounded queue pushes affected watched tiles next tick; no full-board raster/packet burst.
        if(pos!=null)tilePush.computeIfAbsent(player.getUUID(),id->new LinkedHashSet<>()).add(pos.asLong());
        if(response.get("ok").getAsBoolean())queueChecklistTiles(request);
    }
    private void queueChecklistTiles(JsonObject request){
        UUID workspace=UUID.fromString(request.get("workspace").getAsString());
        JsonObject e=store.element(workspace,UUID.fromString(request.get("elementId").getAsString()));if(e==null)return;
        double x=e.get("x").getAsDouble(),y=e.get("y").getAsDouble(),w=e.get("w").getAsDouble();
        double h=Math.max(e.get("h").getAsDouble(),(e.get("text").getAsString().length()+1)*12*e.get("fontSize").getAsDouble()/9+18);
        for(var watched:tileRevisions.entrySet()){
            ServerPlayer viewer=server.getPlayerList().getPlayer(watched.getKey());if(viewer==null)continue;
            for(String sent:watched.getValue().keySet()){
                BlockPos pos=BlockPos.of(Long.parseLong(sent.substring(0,sent.indexOf(':'))));Module m=modules.get(key(viewer.level(),pos));
                if(m==null)continue;
                try{
                    UUID source=UUID.fromString(m.workspace);if(!store.resolve(source).equals(workspace))continue;
                    double[] shift=store.projectionOffset(source);double tx=m.tileX+shift[0],ty=m.tileY+shift[1];
                    if(tx<=x+w&&tx+256>=x&&ty<=y+h&&ty+256>=y)tilePush.computeIfAbsent(viewer.getUUID(),id->new LinkedHashSet<>()).add(pos.asLong());
                }catch(RuntimeException unavailable){/* A foreign-world reference has no local document to refresh. */}
            }
        }
    }
    private net.minecraft.world.phys.BlockHitResult validateChecklist(ServerPlayer player,JsonObject request,BlockPos pos){
        if(player.isSpectator()||player.isShiftKeyDown()||!player.getMainHandItem().isEmpty()||DeskPlacement.chosenHand(player)!=null
                ||!player.level().hasChunkAt(pos)||!player.level().getWorldBorder().isWithinBounds(pos)||!player.mayInteract(player.level(),pos))
            throw new IllegalArgumentException("Aproxime-se do quadro com a mão vazia.");
        if(!(player.level().getBlockEntity(pos) instanceof DeskBlockEntity desk)||desk.workspace==null||!desk.moduleId.toString().equals(request.get("moduleId").getAsString()))
            throw new IllegalArgumentException("O módulo mudou; confira o quadro.");
        UUID workspace=store.resolve(desk.workspace);double[] shift=store.projectionOffset(desk.workspace);
        double ox=desk.tileX+shift[0],oy=desk.tileY+shift[1];
        if(!workspace.toString().equals(request.get("workspace").getAsString())||Math.abs(ox-finite(request,"tileX"))>1e-6||Math.abs(oy-finite(request,"tileY"))>1e-6
                ||!store.canEdit(workspace,player.getUUID()))throw new IllegalArgumentException("A projeção ou o acesso mudou.");
        double yaw=finite(request,"yaw"),pitch=finite(request,"pitch");if(pitch< -90||pitch>90)throw new IllegalArgumentException("Mira inválida.");
        var hit=BoardInteraction.pick(player,pos,Vec3.directionFromRotation((float)pitch,(float)(yaw%360)));
        if(hit==null)throw new IllegalArgumentException("Mire na caixinha de uma tarefa visível.");
        JsonObject e=store.element(workspace,UUID.fromString(request.get("elementId").getAsString()));
        if(e==null||!e.get("type").getAsString().equals("CHECKLIST")||e.get("revision").getAsLong()!=request.get("expectedRevision").getAsLong())
            throw new IllegalArgumentException("A lista mudou; confira o quadro.");
        int row=request.get("row").getAsInt();String text=e.get("text").getAsString();
        if(row<0||row>=text.split("\n",-1).length)throw new IllegalArgumentException("Tarefa inválida.");
        Vec3 uv=BoardInteraction.coordinates(desk.getBlockState(),pos,hit.getLocation());
        double x=ox+uv.x*256-e.get("x").getAsDouble(),y=oy+uv.y*256-e.get("y").getAsDouble(),s=e.get("fontSize").getAsDouble()/9;
        // Font wrapping is client/resource-pack specific. Server validates the box column and bounded card region.
        double maxHeight=Math.max(e.get("h").getAsDouble(),(text.length()+text.split("\n",-1).length)*12*s+18*s);
        if(x<7*s||x>21*s||y<7*s||y>maxHeight)throw new IllegalArgumentException("Mire na caixinha, não no texto.");
        return hit;
    }
    private static double finite(JsonObject o,String key){double value=o.get(key).getAsDouble();if(!Double.isFinite(value)||Math.abs(value)>1e15)throw new IllegalArgumentException("Coordenada inválida");return value;}
    private boolean valid(ServerPlayer player,Session session){
        if(player.level()!=session.level)return false;
        Module current=modules.get(key(session.level,session.pos));
        if(current==null||!current.moduleId.equals(session.module.toString())) {
            SurfaceLayout old=session.lastSurface;if(old==null)return false;
            Set<String> ids=new HashSet<>();for(var tile:old.tiles)ids.add(tile.id());
            BlockPos previous=session.pos;
            Module candidate=modules.values().stream().filter(m->ids.contains(m.moduleId)&&loaded.containsKey(m.key())
                &&m.dimension.equals(dimension(session.level))&&player.distanceToSqr(m.x+.5,m.y+.5,m.z+.5)<=100)
                .min(Comparator.<Module>comparingDouble(m->m.pos().distSqr(previous))
                    .thenComparingDouble(m->player.distanceToSqr(m.x+.5,m.y+.5,m.z+.5)).thenComparing(m->m.moduleId)).orElse(null);
            if(candidate==null)return false;session.pos=candidate.pos();session.module=UUID.fromString(candidate.moduleId);current=candidate;
        }
        return player.distanceToSqr(session.pos.getX()+.5,session.pos.getY()+.5,session.pos.getZ()+.5)<=100
            &&session.level.getBlockEntity(session.pos) instanceof DeskBlockEntity desk&&desk.moduleId.equals(session.module);
    }
    private void closeSession(ServerPlayer player){
        sessions.remove(player.getUUID());JsonObject state=new JsonObject();state.addProperty("closed",true);
        state.addProperty("message","Canvas Desk: aproxime-se de um módulo existente para continuar.");send(player,state);
    }
    private void validateSurfaceEdit(Session session,JsonObject request){
        SurfaceLayout surface=surfaces.get(key(session.level,session.pos));
        if(surface==null||dirtySurfaces.contains(surface)||!request.has("surfaceRevision")||request.get("surfaceRevision").getAsLong()!=surface.revision)
            throw new IllegalArgumentException("A superfície mudou; aguarde a atualização e tente novamente.");
        for(JsonElement e:request.getAsJsonArray("changes")) {
            JsonObject change=e.getAsJsonObject(),before=store.element(session.workspace,UUID.fromString(change.get("id").getAsString()));
            if(before!=null&&!ElementFootprint.visible(surface,before))throw new IllegalArgumentException("O elemento está fora da superfície disponível.");
            JsonElement after=change.get("after");
            if(after!=null&&!after.isJsonNull()&&!ElementFootprint.visible(surface,after.getAsJsonObject()))throw new IllegalArgumentException("Mantenha parte do elemento sobre um módulo existente.");
        }
    }
    public void recolor(ServerPlayer player,BlockPos pos,DyeColor color,ItemStack stack){
        if(!(player.level().getBlockEntity(pos) instanceof DeskBlockEntity desk)||desk.workspace==null)return;
        try{if(!store.canEdit(desk.workspace,player.getUUID())){player.sendSystemMessage(Component.literal("Canvas Desk: sem permissão para editar este documento."));return;}}
        catch(RuntimeException e){player.sendSystemMessage(Component.literal("Canvas Desk: documento indisponível; a referência foi preservada."));return;}
        var state=desk.getBlockState();if(state.getValue(DeskBlock.COLOR)==color)return;
        player.level().setBlock(pos,state.setValue(DeskBlock.COLOR,color),3);
        Module m=modules.get(key(player.level(),pos));if(m!=null)m.color=color.getSerializedName();
        if(!player.hasInfiniteMaterials())stack.shrink(1);desk.changed();queue(key(player.level(),pos));indexDirty=true;tileRevisions.clear();
    }
    private void sendView(ServerPlayer player,Session session,boolean open,String feedback) {
        sendView(player,session,open,feedback,null);
    }
    private void sendView(ServerPlayer player,Session session,boolean open,String feedback,JsonObject acknowledgement) {
        try {
            Module module=modules.get(key(session.level,session.pos));if(module==null)return;
            double[] offset=store.projectionOffset(UUID.fromString(module.workspace));
            double originX=module.tileX+offset[0],originY=module.tileY+offset[1];
            double basisX=originX-256*BoardPlane.horizontal(module.front(),session.referencePos,module.pos());
            double basisY=originY-256*BoardPlane.vertical(module.front(),module.horizontal,session.referencePos,module.pos());
            double shiftX=basisX-session.basisX,shiftY=basisY-session.basisY;
            UUID workspace=store.resolve(UUID.fromString(module.workspace));
            SurfaceLayout surface=surfaces.get(module.key());boolean ready=surface!=null&&!dirtySurfaces.contains(surface);
            JsonObject view=store.view(workspace,player.getUUID(),session.minX+shiftX,session.minY+shiftY,session.maxX+shiftX,session.maxY+shiftY);
            if(session.focusElement!=null){JsonObject focused=store.element(workspace,session.focusElement);
                if(focused!=null){boolean present=false;for(JsonElement e:view.getAsJsonArray("elements"))if(e.getAsJsonObject().get("id").getAsString().equals(session.focusElement.toString())){present=true;break;}
                    if(!present)view.getAsJsonArray("elements").add(focused);}
            }
            boolean opening=open||session.opening;view.addProperty("kind","state");view.addProperty("open",opening);
            view.addProperty("originX",originX);view.addProperty("originY",originY);
            view.addProperty("anchorPos",session.pos.asLong());view.addProperty("moduleId",session.module.toString());
            view.addProperty("surfaceUpdating",!ready);
            if(surface!=null){
                view.addProperty("surfaceRevision",surface.revision);
                if(opening||session.sentSurfaceRevision!=surface.revision)view.add("surface",surface.toJson());
            }
            if(shiftX!=0||shiftY!=0){view.addProperty("viewShiftX",shiftX);view.addProperty("viewShiftY",shiftY);}
            JsonArray players=new JsonArray();for(ServerPlayer p:server.getPlayerList().getPlayers()) {
                JsonObject entry=new JsonObject();entry.addProperty("id",p.getUUID().toString());entry.addProperty("name",p.getName().getString());players.add(entry);
            }view.add("players",players);if(feedback!=null)view.addProperty("message",feedback);
            if(acknowledgement!=null)for(var entry:acknowledgement.entrySet())view.add(entry.getKey(),entry.getValue());
            send(player,view);
            session.shift(shiftX,shiftY);session.workspace=workspace;session.opening=false;
            if(surface!=null){session.sentSurfaceRevision=surface.revision;if(ready)session.lastSurface=surface;}
        }catch(RuntimeException e){
            CanvasDesk.LOGGER.warn("Could not send Canvas Desk view at {} for {}",session.pos,player.getUUID(),e);
            player.sendSystemMessage(Component.literal("Canvas Desk: "+e.getMessage()));
        }
    }
    private void refreshAll(String feedback){
        for(var entry:List.copyOf(sessions.entrySet())){ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null){if(valid(player,entry.getValue()))sendView(player,entry.getValue(),false,feedback);else closeSession(player);}}
    }
    private void refreshOthers(UUID actor){
        for(var entry:List.copyOf(sessions.entrySet()))if(!entry.getKey().equals(actor)){ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null&&valid(player,entry.getValue()))sendView(player,entry.getValue(),false,null);}
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
                String key=pos.asLong()+":"+desk.moduleId+":"+tile.get("workspace").getAsString()+":"+x+":"+y+":"+desk.getBlockState().getValue(DeskBlock.COLOR);
                boolean missing=false;if(request.has("missing"))for(JsonElement value:request.getAsJsonArray("missing"))if(value.getAsLong()==pos.asLong()){missing=true;break;}
                long rev=tile.get("revision").getAsLong();if(!missing&&Objects.equals(sent.get(key),rev))continue;
                sent.put(key,rev);tile.addProperty("kind","tile");tile.addProperty("moduleId",desk.moduleId.toString());tile.addProperty("pos",pos.asLong());tile.addProperty("tileX",x);tile.addProperty("tileY",y);tile.addProperty("background",DeskGeometry.color(desk.getBlockState().getValue(DeskBlock.COLOR).getSerializedName()));send(player,tile);
            }catch(RuntimeException e){CanvasDesk.LOGGER.debug("Tile unavailable {}",pos);}
        }
    }
    public static void send(ServerPlayer player,JsonObject json){for(CanvasPayload part:CanvasPayload.split(json.toString()))ServerPlayNetworking.send(player,part);}
    public boolean elementOnSurface(ServerPlayer player,BlockPos pos,JsonObject element){SurfaceLayout s=surfaces.get(key(player.level(),pos));return s!=null&&!dirtySurfaces.contains(s)&&ElementFootprint.visible(s,element);}
    private void worldSurface(ServerPlayer player,JsonObject request){
        BlockPos pos=BlockPos.of(request.get("pos").getAsLong());
        if(!player.level().hasChunkAt(pos)||player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>6400)return;
        if(!(player.level().getBlockEntity(pos) instanceof DeskBlockEntity desk)||desk.workspace==null||!desk.moduleId.toString().equals(request.get("moduleId").getAsString()))return;
        surfaceWatchers.put(player.getUUID(),pos);SurfaceLayout surface=surfaces.get(key(player.level(),pos));
        if(surface==null||dirtySurfaces.contains(surface)){requestSurfaceCalculation(key(player.level(),pos));return;}
        if(request.has("surfaceRevision")&&request.get("surfaceRevision").getAsLong()==surface.revision)return;
        double[] shift=store.projectionOffset(desk.workspace);JsonObject state=new JsonObject();state.addProperty("kind","worldSurface");state.addProperty("pos",pos.asLong());state.addProperty("moduleId",desk.moduleId.toString());
        state.addProperty("originX",desk.tileX+shift[0]);state.addProperty("originY",desk.tileY+shift[1]);state.add("surface",surface.toJson());send(player,state);
    }

    public void tick(){
        ticks++;
        media.tick(ticks);
        int pushBudget=32;
        for(var it=tilePush.entrySet().iterator();it.hasNext()&&pushBudget>0;){
            var entry=it.next();ServerPlayer viewer=server.getPlayerList().getPlayer(entry.getKey());
            if(viewer==null){it.remove();continue;}
            JsonArray positions=new JsonArray();var pending=entry.getValue().iterator();
            while(pending.hasNext()&&pushBudget>0){positions.add(pending.next());pending.remove();pushBudget--;}
            JsonObject request=new JsonObject();request.add("positions",positions);request.add("missing",positions.deepCopy());sendTiles(viewer,request);
            if(entry.getValue().isEmpty())it.remove();
        }
        for(int budget=16;budget>0&&!chunkReconciliations.isEmpty();budget--) {
            PendingChunk job=chunkReconciliations.poll();
            if(job.level.getChunkSource().getChunkNow(job.chunk.getPos().x(),job.chunk.getPos().z())==job.chunk)reconcileChunk(job.level,job.chunk);
        }
        if(normalize!=null&&normalize.epoch!=topologyEpoch){
            for(Module m:normalize.component)if(modules.containsKey(m.key())&&!normalizeQueue.contains(m.key()))normalizeQueue.add(m.key());
            if(normalize.anchor!=null&&!normalizeQueue.contains(normalize.anchor.key()))normalizeQueue.add(normalize.anchor.key());normalize=null;
        }
        if(normalize==null && !normalizeQueue.isEmpty()) {
            String seed=normalizeQueue.poll();Module module=modules.get(seed);
            if(module!=null)try{normalize=new NormalizeJob(module);}catch(RuntimeException ignored){}
        }
        if(normalize!=null && normalize.step(512)){normalizeQueue.removeIf(normalize.seen::contains);publishSurface(normalize);normalize=null;indexDirty=true;tileRevisions.clear();refreshAll(null);
            for(var entry:List.copyOf(surfaceWatchers.entrySet())){var viewer=server.getPlayerList().getPlayer(entry.getKey());if(viewer!=null&&viewer.level().getBlockEntity(entry.getValue()) instanceof DeskBlockEntity d){JsonObject r=new JsonObject();r.addProperty("pos",entry.getValue().asLong());r.addProperty("moduleId",d.moduleId.toString());worldSurface(viewer,r);}}
        }
        if(ticks%20==0) {
            for(var entry:List.copyOf(sessions.entrySet())){ServerPlayer p=server.getPlayerList().getPlayer(entry.getKey());if(p==null)sessions.remove(entry.getKey());else if(!valid(p,entry.getValue()))closeSession(p);}
            assemblers.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            tileRevisions.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            assistReceipts.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            assistTicks.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            checklistReceipts.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            checklistTicks.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            surfaceWatchers.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            if(indexDirty)saveIndexSafe();
        }
    }
    private final class NormalizeJob {
        final long epoch=topologyEpoch;final UUID workspace;final ArrayDeque<Module> frontier=new ArrayDeque<>();final Set<String> seen=new HashSet<>();
        final List<Module> component=new ArrayList<>();Module anchor;int applyIndex=-1,minY=Integer.MAX_VALUE;
        NormalizeJob(Module seed){workspace=store.resolve(UUID.fromString(seed.workspace));frontier.add(seed);}
        boolean step(int budget){
            while(budget-->0){
                if(applyIndex<0) {
                    Module m=frontier.poll();if(m==null){applyIndex=0;continue;}
                    if(modules.get(m.key())!=m||seen.contains(m.key()))continue;
                    try{if(!workspace.equals(store.resolve(UUID.fromString(m.workspace))))continue;}catch(RuntimeException e){continue;}
                    seen.add(m.key());
                    component.add(m);minY=Math.min(minY,m.y);if(anchor==null||m.order<anchor.order||(m.order==anchor.order&&m.moduleId.compareTo(anchor.moduleId)<0))anchor=m;
                    for(Direction side:BoardPlane.sides(m.front(),m.horizontal)){Module n=modules.get(key(m.dimension,m.pos().relative(side)));if(n!=null&&m.compatible(n)&&!seen.contains(n.key()))frontier.add(n);}
                }else {
                    if(applyIndex>=component.size())return true;
                    Module m=component.get(applyIndex++);double[] offset=store.projectionOffset(UUID.fromString(anchor.workspace));
                    double x=anchor.tileX+offset[0]+256.0*BoardPlane.horizontal(anchor.front(),anchor.pos(),m.pos()),y=anchor.tileY+offset[1]+256.0*BoardPlane.vertical(anchor.front(),anchor.horizontal,anchor.pos(),m.pos());
                    m.workspace=workspace.toString();m.tileX=x;m.tileY=y;m.order=anchor.order;
                    DeskBlockEntity desk=loaded.get(m.key());if(desk!=null&&desk.getLevel() instanceof ServerLevel level){
                        apply(m,desk);updateConnections(level,m.pos());
                        var state=level.getBlockState(m.pos());if(state.getValue(DeskBlock.BASE)!=(m.horizontal||m.y==minY))level.setBlock(m.pos(),state.setValue(DeskBlock.BASE,m.horizontal||m.y==minY),3);
                    }
                }
            }return false;
        }
    }
    private void publishSurface(NormalizeJob job){
        if(job.component.isEmpty())return;
        Set<String> members=new HashSet<>();for(Module m:job.component)if(modules.get(m.key())==m)members.add(m.key());
        List<SurfaceLayout.Tile> tiles=new ArrayList<>();
        for(Module m:job.component){
            if(!members.contains(m.key()))continue;
            Direction right=BoardPlane.right(m.front()),down=BoardPlane.down(m.front(),m.horizontal);
            tiles.add(new SurfaceLayout.Tile(m.moduleId,m.tileX,m.tileY,DeskGeometry.color(m.color),
                members.contains(key(m.dimension,m.pos().relative(right.getOpposite()))),members.contains(key(m.dimension,m.pos().relative(right))),
                members.contains(key(m.dimension,m.pos().relative(down.getOpposite()))),members.contains(key(m.dimension,m.pos().relative(down)))));
        }
        SurfaceLayout snapshot=new SurfaceLayout(++surfaceRevision,job.anchor.horizontal?"horizontal":"vertical",tiles);
        for(Module m:job.component)if(members.contains(m.key()))surfaces.put(m.key(),snapshot);
        Set<SurfaceLayout> remaining=Collections.newSetFromMap(new IdentityHashMap<>());remaining.addAll(surfaces.values());dirtySurfaces.retainAll(remaining);
    }
    private void saveIndexSafe(){try{saveIndex();}catch(IOException e){CanvasDesk.LOGGER.error("Failed to persist module projection index",e);}}
    private void saveIndex() throws IOException {
        if(!indexDirty && Files.exists(indexFile))return;
        JsonObject root=new JsonObject();root.addProperty("version",3);root.addProperty("sequence",sequence);root.add("modules",new Gson().toJsonTree(modules.values()));
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
        final ServerLevel level;final BlockPos referencePos;BlockPos pos;UUID module,workspace,focusElement;
        double minX,minY,maxX,maxY,basisX,basisY;long lastTick,sentSurfaceRevision=-1;int actions;boolean opening;SurfaceLayout lastSurface;
        Session(ServerLevel level,BlockPos pos,UUID module,UUID workspace,double x,double y){this.level=level;this.pos=pos.immutable();referencePos=this.pos;this.module=module;this.workspace=workspace;basisX=x;basisY=y;minX=x-1024;minY=y-1024;maxX=x+2048;maxY=y+2048;}
        void shift(double dx,double dy){minX+=dx;minY+=dy;maxX+=dx;maxY+=dy;basisX+=dx;basisY+=dy;}
    }
    private static final class Module {
        String dimension,moduleId,workspace,facing,color="white";boolean horizontal,wall;int x,y,z;double tileX,tileY;long order;
        boolean compatible(Module other){return front()==other.front()&&horizontal==other.horizontal&&wall==other.wall;}
        Direction front(){return facing==null?Direction.NORTH:Direction.valueOf(facing);}
        BlockPos pos(){return new BlockPos(x,y,z);}String key(){return DeskServer.key(dimension,pos());}
        static Module of(ServerLevel level,DeskBlockEntity desk){Module m=new Module();m.dimension=DeskServer.dimension(level);m.moduleId=desk.moduleId.toString();m.workspace=desk.workspace.toString();m.x=desk.getBlockPos().getX();m.y=desk.getBlockPos().getY();m.z=desk.getBlockPos().getZ();m.facing=desk.getBlockState().getValue(DeskBlock.FACING).name();m.color=desk.getBlockState().getValue(DeskBlock.COLOR).getSerializedName();m.horizontal=BoardPlane.horizontal(desk.getBlockState());m.wall=desk.getBlockState().getValue(DeskBlock.WALL);m.tileX=desk.tileX;m.tileY=desk.tileY;m.order=desk.projectionOrder;return m;}
    }
}
