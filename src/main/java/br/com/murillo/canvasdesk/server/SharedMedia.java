package br.com.murillo.canvasdesk.server;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.core.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;

/** Server coordinates clocks/navigation. Browsers, images and audio stay on individual clients. */
public final class SharedMedia {
    private record Key(UUID workspace,UUID element) {}
    private record Watch(Key key,BlockPos anchor,UUID module,long lastSeen) {}
    private final MinecraftServer server;private final CanvasStore store;
    private final Map<Key,MediaTimeline> states=new HashMap<>();
    private final MediaParties parties=new MediaParties();
    private record Anchor(BlockPos pos,String module,String dimension) {}
    private final Map<UUID,Anchor> partyAnchors=new HashMap<>();
    private final Map<UUID,LinkedHashMap<Key,Watch>> watchers=new HashMap<>();
    private final Map<UUID,Long> lastCommand=new HashMap<>();
    private final Map<UUID,LinkedHashMap<UUID,JsonObject>> worldReceipts=new HashMap<>();
    public SharedMedia(MinecraftServer server,CanvasStore store){this.server=server;this.store=store;}
    public static long now(){return System.nanoTime()/1_000_000;}
    /** A press is linearized against the authoritative clock, never a client's stale playback position. */
    public void worldControl(ServerPlayer player,JsonObject request){
        JsonObject reply=new JsonObject();reply.addProperty("kind","worldMedia");UUID operation=null;
        try{
            operation=UUID.fromString(request.get("operationId").getAsString());
            reply.addProperty("operationId",operation.toString());reply.addProperty("nonce",operation.toString());
            var receipts=worldReceipts.computeIfAbsent(player.getUUID(),id->new LinkedHashMap<>());
            if(receipts.containsKey(operation)){DeskServer.send(player,receipts.get(operation));return;}
            BlockPos pos=BlockPos.of(request.get("pos").getAsLong());
            var hit=validateWorld(player,request,pos);
            Key key=new Key(UUID.fromString(request.get("workspace").getAsString()),UUID.fromString(request.get("elementId").getAsString()));
            long time=now();
            if(time-lastCommand.getOrDefault(player.getUUID(),-1000L)<50)throw new IllegalArgumentException("Aguarde a confirmação do vídeo.");
            lastCommand.put(player.getUUID(),time);
            boolean[] applied={false};
            ChecklistInteraction.dispatch(player,hit,()->{
                try{return validateWorld(player,request,pos)!=null;}catch(RuntimeException changed){return false;}
            },()->{
                var desk=(DeskBlockEntity)player.level().getBlockEntity(pos);
                String source=request.get("source").getAsString();
                MediaTimeline timeline=states.get(key);
                if(timeline!=null&&!timeline.source.equals(source)){end(key,"A fonte do embed mudou.");timeline=null;}
                if(timeline==null){timeline=new MediaTimeline(source,now());states.put(key,timeline);}
                watch(player,key,pos,desk.moduleId);
                timeline.command("toggle",new JsonObject(),timeline.revision(),now());
                if(timeline.snapshot(now()).get("playing").getAsBoolean())parties.activate(key.workspace,key.element);
                observe(player,key,store.element(key.workspace,key.element),pos,desk.moduleId);
                applied[0]=true;broadcastBoard(key.workspace);
            });
            if(!applied[0])throw new IllegalArgumentException("A interação foi recusada ou a superfície mudou.");
            reply.addProperty("ok",true);
        }catch(RuntimeException error){reply.addProperty("ok",false);reply.addProperty("message",error.getMessage()==null?"Vídeo indisponível.":error.getMessage());}
        if(operation!=null){var receipts=worldReceipts.computeIfAbsent(player.getUUID(),id->new LinkedHashMap<>());receipts.put(operation,reply.deepCopy());while(receipts.size()>64)receipts.remove(receipts.keySet().iterator().next());}
        DeskServer.send(player,reply);
    }
    private net.minecraft.world.phys.BlockHitResult validateWorld(ServerPlayer player,JsonObject request,BlockPos pos){
        if(player.isSpectator()||player.isShiftKeyDown()||!player.getMainHandItem().isEmpty()||DeskPlacement.chosenHand(player)!=null
                ||!player.level().hasChunkAt(pos)||!player.level().getWorldBorder().isWithinBounds(pos)||!player.mayInteract(player.level(),pos))
            throw new IllegalArgumentException("Aproxime-se do vídeo com a mão vazia.");
        if(!(player.level().getBlockEntity(pos) instanceof DeskBlockEntity desk)||desk.workspace==null||!desk.moduleId.toString().equals(request.get("moduleId").getAsString()))
            throw new IllegalArgumentException("O módulo mudou; confira o quadro.");
        UUID workspace=store.resolve(desk.workspace);double[] shift=store.projectionOffset(desk.workspace);
        double ox=desk.tileX+shift[0],oy=desk.tileY+shift[1];
        if(!workspace.toString().equals(request.get("workspace").getAsString())||Math.abs(ox-finite(request,"tileX"))>1e-6||Math.abs(oy-finite(request,"tileY"))>1e-6||!store.canEdit(workspace,player.getUUID()))
            throw new IllegalArgumentException("A projeção ou o acesso mudou.");
        double yaw=finite(request,"yaw"),pitch=finite(request,"pitch");if(pitch< -90||pitch>90)throw new IllegalArgumentException("Mira inválida.");
        var hit=BoardInteraction.pick(player,pos,net.minecraft.world.phys.Vec3.directionFromRotation((float)pitch,(float)(yaw%360)));
        if(hit==null)throw new IllegalArgumentException("Mire no vídeo visível dentro do alcance.");
        JsonObject e=store.element(workspace,UUID.fromString(request.get("elementId").getAsString()));
        if(e==null||!e.get("type").getAsString().equals("EMBED")||e.get("revision").getAsLong()!=request.get("expectedRevision").getAsLong()||!e.get("url").getAsString().equals(request.get("source").getAsString())
                ||!DeskServer.get(server).elementOnSurface(player,pos,e))throw new IllegalArgumentException("O vídeo mudou; confira o quadro.");
        var uv=BoardInteraction.coordinates(desk.getBlockState(),pos,hit.getLocation());
        if(!EmbedGeometry.fit(e.get("x").getAsDouble(),e.get("y").getAsDouble(),e.get("w").getAsDouble(),e.get("h").getAsDouble()).contains(ox+uv.x*256,oy+uv.y*256))
            throw new IllegalArgumentException("Mire na imagem do vídeo.");
        return hit;
    }
    private static double finite(JsonObject request,String field){double value=request.get(field).getAsDouble();if(!Double.isFinite(value)||Math.abs(value)>1e15)throw new IllegalArgumentException("Coordenada inválida.");return value;}
    private void watch(ServerPlayer player,Key key,BlockPos pos,UUID module){var watches=watchers.computeIfAbsent(player.getUUID(),p->new LinkedHashMap<>());watches.put(key,new Watch(key,pos,module,now()));while(watches.size()>8)watches.remove(watches.keySet().iterator().next());}
    public void receive(ServerPlayer player,JsonObject request){
        Key key=null;JsonObject result=new JsonObject();result.addProperty("kind","mediaState");
        try{
            UUID workspace=UUID.fromString(request.get("workspace").getAsString()),element=UUID.fromString(request.get("elementId").getAsString());
            key=new Key(workspace,element);result.addProperty("workspace",workspace.toString());result.addProperty("elementId",element.toString());
            BlockPos pos=BlockPos.of(request.get("pos").getAsLong());
            if(!player.level().hasChunkAt(pos)||player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>4096||!(player.level().getBlockEntity(pos) instanceof DeskBlockEntity desk)
                    ||desk.workspace==null||!desk.moduleId.toString().equals(request.get("moduleId").getAsString())||!store.resolve(desk.workspace).equals(workspace))throw new IllegalArgumentException("Mídia fora de alcance ou módulo alterado.");
            JsonObject e=store.element(workspace,element);if(e==null||!e.get("type").getAsString().equals("EMBED"))throw new IllegalArgumentException("Embed indisponível.");
            if(!DeskServer.get(server).elementOnSurface(player,pos,e))throw new IllegalArgumentException("Embed não aparece nessa superfície.");
            String url=e.get("url").getAsString();MediaTimeline timeline=states.get(key);if(timeline!=null&&!timeline.source.equals(url)){end(key,"A fonte do embed mudou.");timeline=null;}if(timeline==null){timeline=new MediaTimeline(url,now());states.put(key,timeline);}
            watch(player,key,pos,desk.moduleId);observe(player,key,e,pos,desk.moduleId);
            if(request.get("action").getAsString().equals("mediaControl")){
                if(player.isSpectator()||player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>100||!player.mayInteract(player.level(),pos)||!store.canEdit(workspace,player.getUUID()))throw new IllegalArgumentException("Somente donos e convidados próximos podem controlar.");
                long time=now();if(time-lastCommand.getOrDefault(player.getUUID(),-1000L)<50)throw new IllegalArgumentException("Aguarde a confirmação do controle.");lastCommand.put(player.getUUID(),time);
                if(!request.get("source").getAsString().equals(url))throw new IllegalArgumentException("O endereço do embed mudou.");
                if(!request.has("mediaSession")||!request.get("mediaSession").getAsString().equals(timeline.session))throw new IllegalArgumentException("A sessão de mídia mudou. Estado atualizado.");
                var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),BoardPlane.horizontal(desk.getBlockState())?net.minecraft.core.Direction.UP:desk.getBlockState().getValue(DeskBlock.FACING),pos,false);
                if(net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(player,player.level(),net.minecraft.world.InteractionHand.MAIN_HAND,hit)!=net.minecraft.world.InteractionResult.PASS)throw new IllegalArgumentException("Interação bloqueada pela proteção do bloco.");
                if(player.level().getBlockEntity(pos)!=desk||!desk.moduleId.toString().equals(request.get("moduleId").getAsString()))throw new IllegalArgumentException("Módulo alterado durante a interação.");
                JsonObject current=store.element(workspace,element);
                if(states.get(key)!=timeline||desk.workspace==null||!store.resolve(desk.workspace).equals(workspace)||!store.canEdit(workspace,player.getUUID())||!player.mayInteract(player.level(),pos)||current==null||!current.has("url")||!current.get("url").getAsString().equals(url)||!DeskServer.get(server).elementOnSurface(player,pos,current))throw new IllegalArgumentException("Documento ou permissão alterados durante a interação.");
                timeline.command(request.get("command").getAsString(),request,request.get("mediaRevision").getAsLong(),time);if(timeline.snapshot(now()).get("playing").getAsBoolean()&&!request.get("command").getAsString().equals("scroll")&&!request.get("command").getAsString().equals("navigate"))parties.activate(key.workspace,key.element);broadcastBoard(key.workspace);
            }
            result=state(player,key);result.addProperty("ok",true);
        }catch(RuntimeException error){
            if(key!=null&&states.containsKey(key))result=state(player,key);result.addProperty("ok",false);result.addProperty("message",error.getMessage());
        }
        if(request.has("nonce"))result.add("nonce",request.get("nonce"));DeskServer.send(player,result);
    }
    private static UUID author(JsonObject e){return e!=null&&e.has("sourceAuthor")?UUID.fromString(e.get("sourceAuthor").getAsString()):null;}
    private void observe(ServerPlayer player,Key key,JsonObject e,BlockPos pos,UUID module){
        parties.ensureActive(key.workspace,key.element);parties.observe(player.getUUID(),key.workspace,author(e));
        if(parties.member(player.getUUID(),key.workspace))partyAnchors.putIfAbsent(player.getUUID(),new Anchor(pos.immutable(),module.toString(),player.level().dimension().toString()));
    }
    private JsonObject state(ServerPlayer player,Key key){
        JsonObject r=states.get(key).snapshot(now());r.addProperty("kind","mediaState");r.addProperty("workspace",key.workspace.toString());r.addProperty("elementId",key.element.toString());r.addProperty("editable",store.canEdit(key.workspace,player.getUUID()));
        r.addProperty("audioAllowed",parties.allowed(player.getUUID(),key.workspace,key.element,author(store.element(key.workspace,key.element))));
        return r;
    }
    private JsonObject partyState(ServerPlayer player){
        JsonObject r=new JsonObject();r.addProperty("kind","partyState");var m=parties.membership(player.getUUID());r.addProperty("member",m!=null);
        if(m!=null){r.addProperty("workspace",m.workspace().toString());r.addProperty("mode",m.mode().name());r.addProperty("explicit",m.explicit());UUID id=parties.active(m.workspace());
            if(id!=null){JsonObject e=store.element(m.workspace(),id);if(e!=null&&e.has("url")){r.add("element",e);r.addProperty("audioAllowed",parties.allowed(player.getUUID(),m.workspace(),id,author(e)));}}
            Anchor a=partyAnchors.get(player.getUUID());if(a!=null){r.addProperty("anchorPos",a.pos.asLong());r.addProperty("moduleId",a.module);r.addProperty("dimension",a.dimension);}
        }return r;
    }
    private void broadcast(Key key){
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(watchers.getOrDefault(p.getUUID(),new LinkedHashMap<>()).containsKey(key)||parties.member(p.getUUID(),key.workspace))DeskServer.send(p,state(p,key));
    }
    private void broadcastBoard(UUID workspace){for(Key k:List.copyOf(states.keySet()))if(k.workspace.equals(workspace))broadcast(k);for(ServerPlayer p:server.getPlayerList().getPlayers())if(parties.member(p.getUUID(),workspace))DeskServer.send(p,partyState(p));}
    /** Party membership is self-service consent; it never grants document edit rights. */
    public void party(ServerPlayer player,JsonObject request){
        JsonObject reply;try{
            String action=request.get("action").getAsString();
            if(action.equals("partyLeave")){var m=parties.membership(player.getUUID());UUID board=request.has("workspace")?UUID.fromString(request.get("workspace").getAsString()):m==null?null:m.workspace();if(board!=null){parties.leave(player.getUUID(),board);if(m!=null&&m.workspace().equals(board))partyAnchors.remove(player.getUUID());broadcastBoard(board);}reply=partyState(player);}
            else if(action.equals("partyPoll")){reply=partyState(player);var m=parties.membership(player.getUUID());if(m!=null){UUID id=parties.active(m.workspace());if(id!=null){Key k=new Key(m.workspace(),id);JsonObject e=store.element(k.workspace,k.element);if(e!=null&&e.has("url")){MediaTimeline t=states.get(k);if(t==null||!t.source.equals(e.get("url").getAsString())){if(t!=null)end(k,"Fonte alterada.");states.put(k,new MediaTimeline(e.get("url").getAsString(),now()));}DeskServer.send(player,state(player,k));}}}}
            else {
                BlockPos pos=BlockPos.of(request.get("pos").getAsLong());UUID workspace=UUID.fromString(request.get("workspace").getAsString());
                var hit=validateParty(player,request,pos);MediaParties.Mode mode=MediaParties.Mode.valueOf(request.get("mode").getAsString());
                var old=parties.membership(player.getUUID());UUID previous=old==null?null:old.workspace();boolean[] joined={false};
                ChecklistInteraction.dispatch(player,hit,()->{try{return validateParty(player,request,pos)!=null;}catch(RuntimeException changed){return false;}},()->{
                    var desk=(DeskBlockEntity)player.level().getBlockEntity(pos);UUID element=UUID.fromString(request.get("elementId").getAsString());
                    parties.join(player.getUUID(),workspace,mode);parties.ensureActive(workspace,element);partyAnchors.put(player.getUUID(),new Anchor(pos.immutable(),desk.moduleId.toString(),player.level().dimension().toString()));joined[0]=true;
                });
                if(!joined[0])throw new IllegalArgumentException("A proteção recusou a entrada na party.");
                if(previous!=null&&!previous.equals(workspace))broadcastBoard(previous);broadcastBoard(workspace);reply=partyState(player);
            }reply.addProperty("ok",true);
        }catch(RuntimeException error){reply=partyState(player);reply.addProperty("ok",false);reply.addProperty("message",error.getMessage());}
        if(request.has("nonce"))reply.add("nonce",request.get("nonce"));DeskServer.send(player,reply);
    }
    private net.minecraft.world.phys.BlockHitResult validateParty(ServerPlayer player,JsonObject request,BlockPos pos){
        UUID workspace=UUID.fromString(request.get("workspace").getAsString());
        if(player.isSpectator()||!player.level().hasChunkAt(pos)||!player.level().getWorldBorder().isWithinBounds(pos)||player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>100||!player.mayInteract(player.level(),pos)||DeskPlacement.chosenHand(player)!=null||player.getMainHandItem().get(net.minecraft.core.component.DataComponents.DYE)!=null||!(player.level().getBlockEntity(pos) instanceof DeskBlockEntity desk)||desk.workspace==null||!desk.moduleId.toString().equals(request.get("moduleId").getAsString())||!store.resolve(desk.workspace).equals(workspace))throw new IllegalArgumentException("Aproxime-se do quadro para entrar na party.");
        var hit=BoardInteraction.pick(player,pos,player.getLookAngle());if(hit==null)throw new IllegalArgumentException("Mire na superfície do quadro.");
        JsonObject e=store.element(workspace,UUID.fromString(request.get("elementId").getAsString()));if(e==null||!e.get("type").getAsString().equals("EMBED")||!DeskServer.get(server).elementOnSurface(player,pos,e))throw new IllegalArgumentException("Vídeo indisponível nessa superfície.");return hit;
    }
    public void changed(UUID workspace){for(Key k:List.copyOf(states.keySet()))if(store.resolve(k.workspace).equals(workspace)){JsonObject e=store.element(k.workspace,k.element);boolean embed=e!=null&&e.has("url");if(!embed||!e.get("url").getAsString().equals(states.get(k).source)){end(k,"Embed removido ou fonte alterada.");if(!embed)parties.removed(k.workspace,k.element);}}}
    private void end(Key key,String message){MediaTimeline old=states.remove(key);if(old==null)return;JsonObject r=old.snapshot(now());r.addProperty("kind","mediaClosed");r.addProperty("workspace",key.workspace.toString());r.addProperty("elementId",key.element.toString());r.addProperty("message",message);for(var viewer:watchers.entrySet()){var p=server.getPlayerList().getPlayer(viewer.getKey());if(p!=null&&viewer.getValue().containsKey(key))DeskServer.send(p,r);}}
    public void merged(UUID canonical){for(UUID board:parties.workspaces())if(store.resolve(board).equals(canonical))parties.end(board);for(Key k:List.copyOf(states.keySet()))if(store.resolve(k.workspace).equals(canonical))end(k,"Documentos unidos; reprodução reiniciada.");for(ServerPlayer p:server.getPlayerList().getPlayers())DeskServer.send(p,partyState(p));}
    public void tick(long tick){if(tick%20!=0)return;long now=now();
        for(UUID id:parties.trackedPlayers())if(server.getPlayerList().getPlayer(id)==null){parties.disconnect(id);partyAnchors.remove(id);}
        lastCommand.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(parties.membership(p.getUUID())!=null){DeskServer.send(p,partyState(p));var m=parties.membership(p.getUUID());UUID id=parties.active(m.workspace());if(id!=null){Key k=new Key(m.workspace(),id);JsonObject e=store.element(k.workspace,k.element);if(e!=null&&e.has("url")){MediaTimeline t=states.get(k);if(t==null||!t.source.equals(e.get("url").getAsString())){if(t!=null)end(k,"Fonte alterada.");states.put(k,new MediaTimeline(e.get("url").getAsString(),now()));}DeskServer.send(p,state(p,k));}}}
        worldReceipts.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
        for(var viewers=watchers.entrySet().iterator();viewers.hasNext();){var entry=viewers.next();var player=server.getPlayerList().getPlayer(entry.getKey());if(player==null){viewers.remove();lastCommand.remove(entry.getKey());continue;}
            for(var it=entry.getValue().entrySet().iterator();it.hasNext();){Watch watch=it.next().getValue();
                try{if(now-watch.lastSeen>5000||!player.level().hasChunkAt(watch.anchor)||player.distanceToSqr(watch.anchor.getX()+.5,watch.anchor.getY()+.5,watch.anchor.getZ()+.5)>4096||!(player.level().getBlockEntity(watch.anchor) instanceof DeskBlockEntity desk)||!desk.moduleId.equals(watch.module)||!store.resolve(desk.workspace).equals(watch.key.workspace)){it.remove();continue;}
                    JsonObject e=store.element(watch.key.workspace,watch.key.element);MediaTimeline current=states.get(watch.key);if(current==null){it.remove();continue;}if(e==null||!e.has("url")||!e.get("url").getAsString().equals(current.source)){end(watch.key,"Embed removido ou fonte alterada.");it.remove();continue;}
                    DeskServer.send(player,state(player,watch.key));
                }catch(RuntimeException unavailable){it.remove();}
            }
            if(entry.getValue().isEmpty())viewers.remove();
        }
        Set<Key> used=new HashSet<>();watchers.values().forEach(w->used.addAll(w.keySet()));if(states.size()>1024)states.keySet().removeIf(k->!used.contains(k)&&!k.element.equals(parties.active(k.workspace)));
    }
}
