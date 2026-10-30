package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.*;
import br.com.murillo.canvasdesk.client.editor.*;
import br.com.murillo.canvasdesk.client.media.*;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import static br.com.murillo.canvasdesk.validation.EditorValidation.*;

/** Genuine hidden client, real native DOM, in-memory clipboard ports and no Windows input/audio. */
final class PartyValidation {
    private final Path root=Path.of(System.getProperty("canvasdesk.projectRoot")),out=Path.of(System.getProperty("canvasdesk.probeOutput"));
    private final List<String> checks=new ArrayList<>(),shots=new ArrayList<>();
    private JsonObject video;
    private final AtomicBoolean cancel=new AtomicBoolean();
    void run(ClientGameTestContext c){
        try{
            Files.createDirectories(out);require(Boolean.getBoolean("canvasdesk.browserSilent"),"Silent browser required");
            c.runOnClient(m->{m.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);require((org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(m.getWindow().handle())&org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN)!=0,"Window must be hidden");});write("RUNNING",null);
            UseBlockCallback.EVENT.register((p,l,h,hit)->!l.isClientSide()&&hit.getBlockPos().equals(V)&&cancel.getAndSet(false)?InteractionResult.FAIL:InteractionResult.PASS);
            try(var http=MediaFixture.start(root);var world=c.worldBuilder().create()){
                var server=world.getServer();c.waitFor(m->m.player!=null&&m.level!=null,WAIT);
                video=primitive("EMBED","Fonte preservada",20,20,220,123.75,9);video.addProperty("url",http.url("clip.webm"));
                server.runOnServer(s->{var p=player(s);p.setGameMode(GameType.SURVIVAL);p.getAbilities().mayfly=true;p.getAbilities().flying=true;p.onUpdateAbilities();for(int x=5;x<14;x++)for(int z=5;z<14;z++)for(int y=79;y<85;y++)p.level().setBlock(new BlockPos(x,y,z),y==79?Blocks.STONE_BRICKS.defaultBlockState():Blocks.AIR.defaultBlockState(),3);fixture(s,V,false,ItemStack.EMPTY);fixture(s,V.east(),false,ItemStack.EMPTY);edit(s,V,video,-1);});
                c.waitFor(m->m.level.getBlockEntity(V) instanceof DeskBlockEntity,WAIT);aim(c,server,V,120,80,3);c.runOnClient(m->SurfaceCache.request(V));
                c.waitFor(m->SurfaceCache.interaction(V)!=null&&MediaPartyClient.participates(SurfaceCache.interaction(V).workspace(),m.player.getUUID().toString()),WAIT);
                String board=c.computeOnClient(m->SurfaceCache.interaction(V).workspace());var target=c.computeOnClient(m->new MediaPartyClient.Target(board,video.get("id").getAsString(),V,SurfaceCache.interaction(V).moduleId()));
                c.waitFor(m->entry()!=null&&entry().ready&&entry().observedReady,400);require(c.computeOnClient(m->entry().audioBlocked),"Test helper not muted");
                c.runOnClient(m->MediaPartyClient.leave(board));c.waitFor(m->!MediaPartyClient.member(board),WAIT);c.waitTicks(3);click(c,V,120,80);c.waitFor(m->m.gui.screen() instanceof PartyScreen,WAIT);shot(c,"world-party-menu");widget(c,"Ouvir por proximidade");c.waitFor(m->MediaPartyClient.member(board)&&!MediaPartyClient.continuous(),WAIT);key(c,InputConstants.KEY_ESCAPE,0);
                c.runOnClient(m->m.options.keyUse.setDown(true));click(c,V,120,80);c.waitFor(m->entry()!=null&&entry().state!=null&&entry().state.get("playing").getAsBoolean(),WAIT);c.runOnClient(m->m.options.keyUse.setDown(false));c.waitTicks(3);require(c.computeOnClient(m->m.gui.screen()==null),"Direct play opened editor");
                aim(c,server,V,120,80,10);require(c.computeOnClient(m->MediaPartyClient.gain(entry())>.3&&MediaPartyClient.gain(entry())<.7),"Proximity attenuation missing");aim(c,server,V,120,80,20);require(c.computeOnClient(m->entry()==null||MediaPartyClient.gain(entry())==0),"Far proximity listener not silent");
                aim(c,server,V,120,80,3);c.runOnClient(m->MediaPartyClient.join(target,true));c.waitFor(m->MediaPartyClient.continuous(),WAIT);aim(c,server,V,120,80,90);c.waitTicks(20);require(c.computeOnClient(m->entry()!=null&&!entry().closed&&entry().process.isAlive()&&MediaPartyClient.gain(entry())==1),"Continuous audio helper disappeared at distance");
                pass("author auto-consent, world join popup, proximity attenuation and continuous helper beyond normal viewing range");
                aim(c,server,V,120,80,3);c.waitFor(m->m.gui.screen()==null&&m.level.getBlockEntity(V) instanceof DeskBlockEntity,WAIT);c.runOnClient(m->m.options.keyShift.setDown(true));server.runOnServer(s->player(s).setShiftKeyDown(true));c.waitTicks(3);click(c,V,120,80);waitEditor(c);c.runOnClient(m->m.options.keyShift.setDown(false));server.runOnServer(s->player(s).setShiftKeyDown(false));c.waitTicks(2);c.runOnClient(m->((CanvasScreen)m.gui.screen()).focusEmbed(video.get("id").getAsString()));c.waitFor(m->entry()!=null&&entry().ready,400);
                widget(c,"Party");require(c.computeOnClient(m->read(m.gui.screen(),"dialog").toString().equals("PARTY")),"Browser party button did not open popup");widget(c,"Voltar");
                var old=c.computeOnClient(m->entry());c.runOnClient(m->old.process.destroyForcibly());c.waitFor(m->old.closed,WAIT);
                setAddress(c,http.url("clip.webm?troca=1"));waitServer(c,()->element(server,V,UUID.fromString(video.get("id").getAsString())).get("url").getAsString().endsWith("?troca=1"));
                var after=element(server,V,UUID.fromString(video.get("id").getAsString()));require(after.get("id").equals(video.get("id"))&&after.get("text").equals(video.get("text"))&&after.get("x").equals(video.get("x"))&&after.get("w").equals(video.get("w")),"URL change recreated or resized element");
                setAddress(c,"endereço inválido");c.waitTicks(3);c.runOnClient(m->{JsonObject r=new JsonObject();r.addProperty("action","view");r.addProperty("minX",0);r.addProperty("minY",0);r.addProperty("maxX",512);r.addProperty("maxY",256);CanvasDeskClient.send(r);});c.waitTicks(4);require(c.computeOnClient(m->((EditBox)read(panel(c),"address")).getValue().equals("endereço inválido")),"Remote update overwrote URL draft");
                pass("URL changed durably after helper failure, preserving element identity/title/geometry; invalid draft survives server refresh");
                setAddress(c,http.url("page"));c.waitFor(m->entry()!=null&&entry().url.equals(http.url("page"))&&entry().ready&&entry().loadedPages>0,400);c.waitTicks(3);browserClick(c,120,350);
                String prefix="ação ç — 日本語🙂",tail="linha ".repeat(170);String text=prefix+"\n"+tail;nativeText(c,prefix);key(c,InputConstants.KEY_RETURN,0);
                c.runOnClient(m->{for(int i=0;i<1500;i++)m.gui.screen().mouseMoved(200+i%50,190);m.keyboardHandler.textInput(m.getWindow().handle(),tail);});probe(c,text);
                AtomicReference<String> clipboard=new AtomicReference<>("colado ç\nclipboard local");c.runOnClient(m->{set(panel(c),"clipboardSource",(Supplier<String>)clipboard::get);set(panel(c),"clipboardTarget",(Consumer<String>)clipboard::set);});
                key(c,InputConstants.KEY_A,InputConstants.MOD_CONTROL);key(c,InputConstants.KEY_V,InputConstants.MOD_CONTROL);probe(c,clipboard.get());key(c,InputConstants.KEY_A,InputConstants.MOD_CONTROL);key(c,InputConstants.KEY_C,InputConstants.MOD_CONTROL);String copied=clipboard.get();c.waitFor(m->entry().clipboardReceiver==null,WAIT);require(clipboard.get().equals(copied),"Native copy changed content");key(c,InputConstants.KEY_X,InputConstants.MOD_CONTROL);probe(c,"");
                nativeText(c,"ação");key(c,InputConstants.KEY_RETURN,0);nativeText(c,"linha");probe(c,"ação\nlinha");c.getInput().resizeWindow(854,480);c.waitTicks(3);nativeText(c," após resize");probe(c,"ação\nlinha após resize");shot(c,"browser-typing-native-dom");
                pass("ordered native DOM text under mouse flood; accents/Unicode, Enter, in-memory clipboard copy/cut/paste and resize focus");
                key(c,InputConstants.KEY_ESCAPE,0);close(c);aim(c,server,V,120,80,3);c.runOnClient(m->MediaPartyClient.leave(board));c.waitFor(m->!MediaPartyClient.member(board),WAIT);cancel.set(true);c.runOnClient(m->MediaPartyClient.join(target,false));c.waitTicks(6);require(c.computeOnClient(m->!MediaPartyClient.member(board)),"Protection allowed party join");pass("leaving immediately silences locally and protection callback blocks new consent");
            }
            checks.add("PASS");write("PASS",null);
        }catch(Throwable error){checks.add("FAIL "+error);try{shot(c,"failure");write("FAIL",error.toString());}catch(Exception ignored){}throw new RuntimeException(error);}
        finally{c.runOnClient(m->{m.options.keyUse.setDown(false);m.options.keyShift.setDown(false);});}
    }
    private BrowserTexture.Entry entry(){return BrowserTexture.byId(video.get("id").getAsString());}
    private static Object panel(ClientGameTestContext c){return read(net.minecraft.client.Minecraft.getInstance().gui.screen(),"browserPanel");}
    private void setAddress(ClientGameTestContext c,String value){c.runOnClient(m->{var f=(EditBox)read(panel(c),"address");m.gui.screen().mouseClicked(mouse(f.getX()+4,f.getY()+5),false);m.gui.screen().mouseReleased(mouse(f.getX()+4,f.getY()+5));});key(c,InputConstants.KEY_A,InputConstants.MOD_CONTROL);nativeText(c,value);require(c.computeOnClient(m->((EditBox)read(panel(c),"address")).getValue().equals(value)),"Address did not accept native typing");key(c,InputConstants.KEY_RETURN,0);c.waitTicks(3);}
    private static void nativeText(ClientGameTestContext c,String value){c.runOnClient(m->m.keyboardHandler.textInput(m.getWindow().handle(),value));c.waitTicks(2);}
    private void probe(ClientGameTestContext c,String expected){c.runOnClient(m->{entry().inputProbe=null;var q=new JsonObject();q.addProperty("cmd","probeInput");entry().send(q);});c.waitFor(m->entry().inputProbe!=null,500);require(c.computeOnClient(m->entry().inputProbe.get("value").getAsString().equals(expected)),"Browser DOM differs from typed text: "+c.computeOnClient(m->entry().inputProbe.get("value").getAsString().substring(0,Math.min(30,entry().inputProbe.get("value").getAsString().length()))));}
    private static void browserClick(ClientGameTestContext c,int px,int py){c.runOnClient(m->{Object p=panel(c);double x=(Integer)read(p,"left")+px*(Integer)read(p,"bw")/1280.0,y=(Integer)read(p,"top")+py*(Integer)read(p,"bh")/720.0;m.gui.screen().mouseMoved(x,y);m.gui.screen().mouseClicked(mouse(x,y),false);m.gui.screen().mouseReleased(mouse(x,y));});c.waitTicks(3);}
    private static void set(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new RuntimeException(e);}}
    private void pass(String s)throws Exception{checks.add("PASS "+s);write("RUNNING",null);}
    private void shot(ClientGameTestContext c,String name)throws Exception{Path p=out.resolve(name+".png");Files.copy(c.takeScreenshot(name),p,StandardCopyOption.REPLACE_EXISTING);shots.add(p.toString());}
    private void write(String status,String failure)throws Exception{var r=new LinkedHashMap<String,Object>();r.put("status",status);r.put("checks",checks);r.put("screenshots",shots);r.put("hidden",true);r.put("silent",true);r.put("failure",failure);Files.writeString(out.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r));Files.writeString(out.resolve("editor-selftest.log"),String.join("\n",checks)+"\n");}
}
