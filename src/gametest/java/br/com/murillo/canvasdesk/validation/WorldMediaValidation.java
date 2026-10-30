package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.*;
import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import br.com.murillo.canvasdesk.client.media.BrowserTexture;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import static br.com.murillo.canvasdesk.validation.EditorValidation.*;

/** Real vanilla clicks and hidden, muted native video; never uses OS mouse/keyboard. */
final class WorldMediaValidation {
    private final Path root=Path.of(System.getProperty("canvasdesk.projectRoot"));
    private final Path output=Path.of(System.getProperty("canvasdesk.probeOutput"));
    private final List<String> checks=new ArrayList<>(),shots=new ArrayList<>();
    private final AtomicReference<String> protection=new AtomicReference<>();
    private final UUID foreignOwner=UUID.randomUUID();
    private JsonObject video,flat,foreign;
    void run(ClientGameTestContext c){
        try{
            require(Boolean.getBoolean("canvasdesk.browserSilent"),"Browser must be muted before startup");
            Files.createDirectories(output);write("RUNNING",null);
            c.runOnClient(m->{m.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);require((org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(m.getWindow().handle())&org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN)!=0,"Window must stay hidden");});
            UseBlockCallback.EVENT.register((p,l,hand,hit)->{if(!l.isClientSide()&&hit.getBlockPos().equals(V)){String mode=protection.getAndSet(null);if("cancel".equals(mode))return InteractionResult.FAIL;if("replace".equals(mode))l.setBlock(V,Blocks.CHEST.defaultBlockState(),3);}return InteractionResult.PASS;});
            try(var http=MediaFixture.start(root);var world=c.worldBuilder().create()){
                c.waitFor(m->m.player!=null&&m.level!=null,WAIT);var server=world.getServer();
                video=primitive("EMBED","Clique para reproduzir",20,20,220,123.75,9);video.addProperty("url",http.url("clip.webm"));flat=video.deepCopy();flat.addProperty("id",UUID.randomUUID().toString());foreign=video.deepCopy();foreign.addProperty("id",UUID.randomUUID().toString());
                server.runOnServer(this::setup);c.waitFor(m->m.level.getBlockEntity(V) instanceof DeskBlockEntity&&m.level.getBlockEntity(H) instanceof DeskBlockEntity&&m.level.getBlockEntity(R) instanceof DeskBlockEntity,WAIT);
                for(var pos:List.of(V,H)){
                    var e=pos.equals(V)?video:flat;aim(c,server,pos,120,80,3);cache(c,pos,e);
                    long before=revision(server,pos,UUID.fromString(e.get("id").getAsString()));
                    String op=press(c,pos,120,80);ack(c,op,true);playing(c,pos,e,true);
                    long mediaRevision=state(c,pos,e).get("mediaRevision").getAsLong();
                    for(int i=0;i<4;i++){click(c,pos,120,80);c.waitTicks(2);}
                    require(state(c,pos,e).get("mediaRevision").getAsLong()==mediaRevision,"Held button repeated toggle");
                    require(c.computeOnClient(m->m.gui.screen()==null),"World video click opened editor");
                    release(c);op=press(c,pos,120,80);ack(c,op,true);playing(c,pos,e,false);release(c);
                    var replay=request(c,pos,e);send(c,replay,true);long once=state(c,pos,e).get("mediaRevision").getAsLong();
                    c.runOnClient(m->CanvasDeskClient.send(replay));c.waitTicks(5);require(state(c,pos,e).get("mediaRevision").getAsLong()==once,"Duplicate operation toggled twice");
                    require(revision(server,pos,UUID.fromString(e.get("id").getAsString()))==before,"Playback wrote document revision");shot(c,pos.equals(V)?"vertical-world-control":"horizontal-world-control");
                }
                pass("vertical and horizontal real vanilla presses: play/pause, held-button suppression, deduplication and unchanged document revision");
                aim(c,server,V,120,80,3);cache(c,V,video);c.waitFor(m->{var e=BrowserTexture.byId(video.get("id").getAsString());return e!=null&&e.ready&&e.observedReady;},400);
                c.waitFor(m->BrowserTexture.byId(video.get("id").getAsString()).observedPosition>1,120);
                ack(c,press(c,V,120,80),true);playing(c,V,video,false);release(c);
                c.waitTicks(8);double paused=c.computeOnClient(m->BrowserTexture.byId(video.get("id").getAsString()).observedPosition);c.waitTicks(12);require(Math.abs(c.computeOnClient(m->BrowserTexture.byId(video.get("id").getAsString()).observedPosition)-paused)<.5,"Native decoder kept playing after world pause");
                shot(c,"native-video-paused-in-world");pass("world click pauses the real native decoder without any screen");
                c.runOnClient(m->m.options.keyShift.setDown(true));server.runOnServer(s->player(s).setShiftKeyDown(true));c.waitTicks(2);click(c,V,120,80);waitEditor(c);close(c);c.runOnClient(m->m.options.keyShift.setDown(false));server.runOnServer(s->player(s).setShiftKeyDown(false));c.waitTicks(2);
                pass("sneak-click still opens editor");
                var note=primitive("NOTE","Acima do vídeo",90,60,60,40,9);server.runOnServer(s->edit(s,V,note,-1));c.runOnClient(m->SurfaceCache.request(V));c.waitFor(m->SurfaceCache.interaction(V).byId().containsKey(note.get("id").getAsString()),WAIT);
                click(c,V,120,80);waitEditor(c);close(c);require(!state(c,V,video).get("playing").getAsBoolean(),"Click passed through overlaid note");server.runOnServer(s->remove(s,V,note.get("id").getAsString()));c.runOnClient(m->SurfaceCache.request(V));c.waitFor(m->!SurfaceCache.interaction(V).byId().containsKey(note.get("id").getAsString()),WAIT);
                pass("overlaid content blocks the video interaction");
                for(String mode:List.of("cancel","replace")){
                    aim(c,server,V,120,80,3);cache(c,V,video);protection.set(mode);ack(c,press(c,V,120,80),false);release(c);require(c.computeOnClient(m->m.gui.screen()==null),"Protection opened replacement UI");require(!state(c,V,video).get("playing").getAsBoolean(),"Protection allowed video control");
                    if(mode.equals("replace")){server.runOnServer(s->{var item=new ItemStack(CanvasDesk.DESK_ITEM);var tag=new CompoundTag();tag.putString("workspace",workspace(cachedWorkspace).toString());item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));fixture(s,V,false,item);});c.waitFor(m->m.level.getBlockEntity(V) instanceof DeskBlockEntity,WAIT);}
                }
                pass("vanilla/Fabric cancellation and replaced anchor reject control without opening another UI");
                aim(c,server,V,120,80,3);cache(c,V,video);var valid=request(c,V,video);
                for(String field:List.of("expectedRevision","moduleId","tileX","source")){var bad=valid.deepCopy();bad.addProperty("operationId",UUID.randomUUID().toString());switch(field){case "expectedRevision"->bad.addProperty(field,-1);case "moduleId"->bad.addProperty(field,UUID.randomUUID().toString());case "tileX"->bad.addProperty(field,999);default->bad.addProperty(field,http.url("clip.webm?other"));}send(c,bad,false);}
                server.runOnServer(s->player(s).level().setBlock(V.south(),Blocks.STONE_BRICKS.defaultBlockState(),3));c.waitTicks(3);sendFresh(c,valid,false);server.runOnServer(s->player(s).level().setBlock(V.south(),Blocks.AIR.defaultBlockState(),3));aim(c,server,V,120,80,8);sendFresh(c,request(c,V,video),false);
                pass("stale revision, module, projection, source, foreground obstacle and reach are revalidated");
                aim(c,server,R,120,80,3);cache(c,R,foreign);send(c,request(c,R,foreign),false);click(c,R,120,80);waitEditor(c);require(c.computeOnClient(m->!((JsonObject)read(m.gui.screen(),"state")).get("editable").getAsBoolean()),"Reader gained write permission");c.runOnClient(m->m.setScreenAndShow(null));c.waitTicks(3);
                server.runOnServer(s->{var r=new JsonObject();r.addProperty("action","invite");r.addProperty("player",player(s).getUUID().toString());require(DeskServer.get(s).store.action(((DeskBlockEntity)player(s).level().getBlockEntity(R)).workspace,foreignOwner,r).get("ok").getAsBoolean(),"Invite failed");});c.runOnClient(m->SurfaceCache.request(R));c.waitFor(m->SurfaceCache.interaction(R).editable(),WAIT);ack(c,press(c,R,120,80),true);release(c);playing(c,R,foreign,true);
                pass("reader opens read-only editor, forged control rejected, invited editor controls directly");
            }
            checks.add("PASS");write("PASS",null);
        }catch(Throwable error){checks.add("FAIL "+error);try{shot(c,"failure");write("FAIL",error.toString());}catch(Exception ignored){}throw new RuntimeException(error);}
        finally{protection.set(null);c.runOnClient(m->{m.options.keyUse.setDown(false);m.options.keyShift.setDown(false);});}
    }
    private String cachedWorkspace;
    private static UUID workspace(String value){return UUID.fromString(value);}
    private void setup(MinecraftServer s){var p=player(s);p.setGameMode(GameType.SURVIVAL);p.getAbilities().mayfly=true;p.getAbilities().flying=true;p.onUpdateAbilities();for(int x=5;x<28;x++)for(int z=5;z<13;z++)for(int y=79;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),y==79?Blocks.STONE_BRICKS.defaultBlockState():Blocks.AIR.defaultBlockState(),3);fixture(s,V,false,ItemStack.EMPTY);fixture(s,V.east(),false,ItemStack.EMPTY);fixture(s,H,true,ItemStack.EMPTY);edit(s,V,video,-1);edit(s,H,flat,-1);cachedWorkspace=((DeskBlockEntity)p.level().getBlockEntity(V)).workspace.toString();var tag=new CompoundTag();tag.putString("workspace",DeskServer.get(s).store.create(foreignOwner).toString());var item=new ItemStack(CanvasDesk.DESK_ITEM);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));fixture(s,R,false,item);require(DeskServer.get(s).store.action(((DeskBlockEntity)p.level().getBlockEntity(R)).workspace,foreignOwner,editRequest(foreign,-1)).get("ok").getAsBoolean(),"Foreign seed failed");}
    private void cache(ClientGameTestContext c,BlockPos pos,JsonObject e){c.runOnClient(m->SurfaceCache.request(pos));c.waitFor(m->{var tile=SurfaceCache.interaction(pos);return tile!=null&&tile.byId().containsKey(e.get("id").getAsString())&&m.level.getBlockEntity(pos) instanceof DeskBlockEntity d&&tile.moduleId().equals(d.moduleId.toString());},WAIT);}
    private String press(ClientGameTestContext c,BlockPos pos,double x,double y){String op=c.computeOnClient(m->{m.options.keyUse.setDown(true);var state=m.level.getBlockState(pos);var point=BoardPlane.surfacePoint(state,x/256,y/256).add(Vec3.atLowerCornerOf(pos));var hit=new BlockHitResult(point,BoardPlane.horizontal(state)?Direction.UP:state.getValue(DeskBlock.FACING),pos,false);m.hitResult=hit;m.gameMode.useItemOn(m.player,InteractionHand.MAIN_HAND,hit);return (String)read(WorldEmbedInput.class,"pending");});require(!op.isEmpty(),"Real click did not produce world media request");return op;}
    private void release(ClientGameTestContext c){c.runOnClient(m->m.options.keyUse.setDown(false));c.waitTicks(3);}
    private JsonObject request(ClientGameTestContext c,BlockPos pos,JsonObject e){return c.computeOnClient(m->{var tile=SurfaceCache.interaction(pos);var live=tile.byId().get(e.get("id").getAsString());var r=new JsonObject();r.addProperty("action","worldMedia");r.addProperty("operationId",UUID.randomUUID().toString());r.addProperty("workspace",tile.workspace());r.addProperty("moduleId",tile.moduleId());r.addProperty("pos",pos.asLong());r.addProperty("tileX",tile.x());r.addProperty("tileY",tile.y());r.addProperty("elementId",live.id);r.addProperty("source",live.url);r.addProperty("expectedRevision",live.revision);r.addProperty("yaw",m.player.getYRot());r.addProperty("pitch",m.player.getXRot());return r;});}
    private void send(ClientGameTestContext c,JsonObject r,boolean ok){c.runOnClient(m->CanvasDeskClient.send(r));ack(c,r.get("operationId").getAsString(),ok);c.waitTicks(3);}
    private void sendFresh(ClientGameTestContext c,JsonObject r,boolean ok){r=r.deepCopy();r.addProperty("operationId",UUID.randomUUID().toString());send(c,r,ok);}
    private void ack(ClientGameTestContext c,String op,boolean ok){c.waitFor(m->BrowserTexture.receipt(op)!=null,WAIT);require(c.computeOnClient(m->BrowserTexture.receipt(op).get("ok").getAsBoolean())==ok,"Unexpected world ACK: "+c.computeOnClient(m->BrowserTexture.receipt(op)));}
    private JsonObject state(ClientGameTestContext c,BlockPos pos,JsonObject e){return c.computeOnClient(m->{var tile=SurfaceCache.interaction(pos);return (JsonObject)((Map<?,?>)read(WorldEmbedInput.class,"states")).get(tile.workspace()+"/"+e.get("id").getAsString());});}
    private void playing(ClientGameTestContext c,BlockPos pos,JsonObject e,boolean expected){for(int i=0;i<WAIT;i++){var state=state(c,pos,e);if(state!=null&&state.get("playing").getAsBoolean()==expected)return;c.waitTick();}throw new AssertionError("World playback state not received");}
    private void pass(String text)throws Exception{checks.add("PASS "+text);write("RUNNING",null);}
    private void shot(ClientGameTestContext c,String name)throws Exception{Path target=output.resolve(name+".png");Files.copy(c.takeScreenshot(name),target,StandardCopyOption.REPLACE_EXISTING);shots.add(target.toString());}
    private void write(String status,String failure)throws Exception{var r=new LinkedHashMap<String,Object>();r.put("status",status);r.put("checks",checks);r.put("screenshots",shots);r.put("failure",failure);r.put("hidden",true);r.put("silent",true);Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r));Files.writeString(output.resolve("editor-selftest.log"),String.join("\n",checks)+"\n");}
}
