package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.editor.*;
import br.com.murillo.canvasdesk.client.media.BrowserTexture;
import br.com.murillo.canvasdesk.client.render.*;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;
import static br.com.murillo.canvasdesk.validation.EditorValidation.*;

/** One 180-second external watchdog owns this disposable client, server and browser descendants. */
public final class FeatureValidation {
    static final BlockPos BOARD=new BlockPos(8,80,8);
    static final Path ROOT=Path.of(System.getProperty("canvasdesk.projectRoot"));
    final Path output=Path.of(System.getProperty("canvasdesk.probeOutput",ROOT.resolve("artifacts/1.4.0/smoke").toString()));
    final List<String> checks=new ArrayList<>(),shots=new ArrayList<>();
    final String inputMode=System.getProperty("canvasdesk.nativeInputMode","all");
    JsonObject materials,video,web;
    public void run(ClientGameTestContext c){
        try{Files.createDirectories(output);c.runOnClient(m->m.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0));if(inputMode.equals("none"))require(c.computeOnClient(m->(org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(m.getWindow().handle())&org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN)!=0),"Validation window must stay hidden");write("RUNNING",null);
            try(var http=MediaFixture.start(ROOT);var world=c.worldBuilder().create()){
                c.waitFor(m->m.player!=null&&m.level!=null,200);var server=world.getServer();
                server.runOnServer(s->{var p=player(s);p.setGameMode(GameType.SURVIVAL);p.getAbilities().mayfly=true;p.getAbilities().flying=true;p.onUpdateAbilities();for(int x=5;x<13;x++)for(int z=5;z<13;z++)for(int y=79;y<84;y++)p.level().setBlock(new BlockPos(x,y,z),y==79?Blocks.STONE_BRICKS.defaultBlockState():Blocks.AIR.defaultBlockState(),3);fixture(s,BOARD,false,ItemStack.EMPTY);fixture(s,BOARD.east(),false,ItemStack.EMPTY);});
                c.waitFor(m->m.level.getBlockEntity(BOARD) instanceof DeskBlockEntity,200);aim(c,server,BOARD,128,128,3);click(c,BOARD,128,128);waitEditor(c);
                key(c,InputConstants.KEY_T,0);documentClick(c,50,45);
                c.runOnClient(m->{require(m.gui.screen().getFocused() instanceof InlineTextEditor,"Inline model lacks native focus");});
                Files.writeString(output.resolve("native-input.stage"),"INLINE: type ação then Enter then linha, then Ctrl+Enter");
                // The external Windows driver sends physical/native keyboard events, never Screen.charTyped.
                if(!inputMode.equals("all")){nativeText(c,"ação");key(c,InputConstants.KEY_RETURN,0);nativeText(c,"linha");key(c,InputConstants.KEY_RETURN,InputConstants.MOD_CONTROL);}
                waitServerNative(c,server);shot(c,"inline-native-windows");pass((inputMode.equals("all")?"Windows/SDL":"Native text-input callback")+" inline typing, accents, Enter and Ctrl+Enter reached durable server document");
                key(c,InputConstants.KEY_T,0);documentClick(c,50,110);nativeText(c,"Resize ç");c.getInput().resizeWindow(854,480);c.waitTicks(3);nativeText(c," ação");key(c,InputConstants.KEY_RETURN,InputConstants.MOD_CONTROL);
                waitServer(c,()->typeOrNull(server,"TEXT")!=null);shot(c,"editor-854");c.getInput().resizeWindow(1280,720);c.waitTicks(3);
                for(int scan:new int[]{InputConstants.KEY_N,InputConstants.KEY_C}){key(c,scan,0);documentClick(c,300,185);stage(scan==InputConstants.KEY_N?"NOTE: type Anotação ç":"CHECKLIST: type Anotação ç");if(!inputMode.equals("all"))nativeText(c,"Anotação ç");waitField(c,"Anotação ç");key(c,InputConstants.KEY_RETURN,0);nativeText(c,"segunda");key(c,InputConstants.KEY_RETURN,InputConstants.MOD_CONTROL);c.waitTicks(3);}
                pass("Resize restores inline focus; notes/checklists accept "+(inputMode.equals("all")?"Windows/SDL":"native callback")+" accented input and multiline content");
                key(c,InputConstants.KEY_U,0);documentClick(c,280,25);stage("LINK: type "+http.url("page"));if(!inputMode.equals("all"))nativeText(c,http.url("page"));waitField(c,http.url("page"));key(c,InputConstants.KEY_RETURN,InputConstants.MOD_CONTROL);waitServer(c,()->typeOrNull(server,"EMBED")!=null);JsonObject createdLink=type(server,"EMBED");server.runOnServer(s->remove(s,BOARD,createdLink.get("id").getAsString()));c.waitTicks(3);
                key(c,InputConstants.KEY_M,0);documentClick(c,20,20);stage("MATERIALS: type Construção ç");if(inputMode.equals("none"))nativeText(c,"Construção ç");waitField(c,"Construção ç");widget(c,"JSON");c.runOnClient(m->{try{for(var child:m.gui.screen().children())if(child instanceof MultiLineEditBox f){f.setValue(Files.readString(ROOT.resolve("src/test/resources/materials-29.json")));return;}throw new AssertionError("JSON input missing");}catch(java.io.IOException e){throw new RuntimeException(e);}});c.getInput().resizeWindow(640,480);c.waitTicks(3);c.runOnClient(m->{for(var child:m.gui.screen().children())if(child instanceof net.minecraft.client.gui.components.AbstractWidget w)require(w.getX()>=0&&w.getRight()<=m.getWindow().getGuiScaledWidth(),"Material control outside narrow screen");});shot(c,"materials-json-640x480");c.getInput().resizeWindow(1280,720);c.waitTicks(3);widget(c,"Importar");widget(c,"Salvar");
                waitServer(c,()->typeOrNull(server,"MATERIALS")!=null);materials=type(server,"MATERIALS");require(materials.getAsJsonArray("items").size()==29,"29-item import missing");c.waitTicks(3);shot(c,"materials-editor");c.getInput().resizeWindow(1920,1080);c.waitTicks(3);shot(c,"editor-1920-materials");c.runOnClient(m->{m.options.guiScale().set(3);m.resizeGui();});c.waitTicks(3);shot(c,"editor-1920-gui3");c.runOnClient(m->{m.options.guiScale().set(2);m.resizeGui();});c.getInput().resizeWindow(1280,720);c.waitTicks(3);
                close(c);c.waitFor(m->{var tile=SurfaceCache.interaction(BOARD);return tile!=null&&tile.byId().containsKey(materials.get("id").getAsString());},150);c.waitFor(m->ItemIconAtlas.get("minecraft:cherry_planks")!=null,100);c.waitTicks(3);shot(c,"materials-world");pass("29 materials imported atomically; native item atlas available in physical board and editor");
                video=primitive("EMBED","Vídeo compartilhado",264,20,232,130.5,9);video.addProperty("url",http.url("clip.webm"));web=primitive("EMBED","Site compartilhado",270,164,224,75,9);web.addProperty("url",http.url("page"));server.runOnServer(s->{edit(s,BOARD,video,-1);edit(s,BOARD,web,-1);});
                aim(c,server,BOARD,220,128,3);click(c,BOARD,220,128);waitEditor(c);c.runOnClient(m->((CanvasScreen)m.gui.screen()).focusEmbed(video.get("id").getAsString()));
                c.waitFor(m->{var e=BrowserTexture.byId(video.get("id").getAsString());return e!=null&&e.ready&&e.observedReady&&e.state!=null;},600);
                shot(c,"embedded-video-real");var id=video.get("id").getAsString();widget(c,"▶");c.waitFor(m->BrowserTexture.byId(id).observedPosition>1,160);
                widget(c,"Ⅱ");c.waitFor(m->!BrowserTexture.byId(id).state.get("playing").getAsBoolean(),100);double paused=c.computeOnClient(m->BrowserTexture.byId(id).observedPosition);c.waitTicks(12);require(Math.abs(c.computeOnClient(m->BrowserTexture.byId(id).observedPosition)-paused)<.5,"Paused video drifted");
                c.runOnClient(m->BrowserTexture.byId(id).control("seek",2.0,null,null,null));c.waitFor(m->Math.abs(BrowserTexture.byId(id).observedPosition-2)<.5,100);browserClick(c,25,672);c.waitFor(m->BrowserTexture.byId(id).state.get("playing").getAsBoolean()&&BrowserTexture.byId(id).observedPosition>2.7,150);browserClick(c,25,672);c.waitFor(m->!BrowserTexture.byId(id).state.get("playing").getAsBoolean(),150);pass("Real Chromium decoder, panel buttons and native video controls; play, pause, seek and >500ms drift correction");
                key(c,InputConstants.KEY_ESCAPE,0);c.runOnClient(m->((CanvasScreen)m.gui.screen()).focusEmbed(web.get("id").getAsString()));c.waitFor(m->BrowserTexture.byId(web.get("id").getAsString())!=null&&BrowserTexture.byId(web.get("id").getAsString()).ready,400);c.waitTicks(10);shot(c,"embedded-site-real");
                int beforeLink=c.computeOnClient(m->BrowserTexture.byId(web.get("id").getAsString()).loadedPages);browserClick(c,120,247);c.waitFor(m->BrowserTexture.byId(web.get("id").getAsString()).state.get("navigationUrl").getAsString().endsWith("/next"),100);
                c.waitFor(m->BrowserTexture.byId(web.get("id").getAsString()).loadedPages>beforeLink,200);c.waitTicks(3);browserClick(c,120,160);nativeText(c,"RascunhoLocal ç 140");c.waitTicks(5);shot(c,"embedded-site-local-input");browserClick(c,380,155);c.waitTicks(15);require(c.computeOnClient(m->BrowserTexture.byId(web.get("id").getAsString()).state.get("navigationUrl").getAsString().endsWith("/next")),"Form URL leaked into shared navigation");c.runOnClient(m->BrowserTexture.byId(web.get("id").getAsString()).control("scroll",null,null,0.0,.7));c.waitFor(m->BrowserTexture.byId(web.get("id").getAsString()).state.get("scrollY").getAsDouble()==.7,100);shot(c,"embedded-site-scroll");
                var failed=c.computeOnClient(m->BrowserTexture.byId(web.get("id").getAsString()));c.runOnClient(m->failed.process.destroyForcibly());c.waitFor(m->failed.closed,100);shot(c,"browser-helper-failure");key(c,InputConstants.KEY_ESCAPE,0);
                c.runOnClient(m->{var screen=(CanvasScreen)m.gui.screen();var state=(JsonObject)read(screen,"state");var previous=BrowserTexture.get(state.get("workspace").getAsString(),id);if(previous!=null)BrowserTexture.reopen(previous);screen.focusEmbed(id);var fresh=BrowserTexture.byId(id);require(fresh!=null,"Focused helper was not allocated");BrowserTexture.cancel(fresh);require(fresh.closed,"Cancel did not stop helper");});key(c,InputConstants.KEY_ESCAPE,0);pass("Native site link/input, private form navigation, shared scroll; helper crash/cancellation leave editor responsive");close(c);c.waitTicks(5);shot(c,"world-embeds-contour");
                server.runOnServer(s->fixture(s,BOARD.above(),false,ItemStack.EMPTY));aim(c,server,BOARD,128,128,3);c.waitTicks(15);shot(c,"world-L-contour");
                server.runOnServer(s->{for(var pos:List.of(BOARD.east(2),BOARD.east(2).above(),BOARD.east(2).above(2),BOARD.east().above(2),BOARD.above(2)))fixture(s,pos,false,ItemStack.EMPTY);});aim(c,server,BOARD,128,-128,6);c.waitTicks(20);shot(c,"world-ring-contour");
                var reference=server.computeOnServer(s->{var stack=new ItemStack(br.com.murillo.canvasdesk.CanvasDesk.DRAWING_DESK_ITEM);stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(((DeskBlockEntity)player(s).level().getBlockEntity(BOARD)).reference()));return stack;});var flat=BOARD.east(8);server.runOnServer(s->fixture(s,flat,true,reference));c.waitFor(m->m.level.getBlockEntity(flat) instanceof DeskBlockEntity,100);aim(c,server,flat,128,128,3);c.runOnClient(m->m.player.setYRot(180));c.waitFor(m->SurfaceCache.interaction(flat)!=null,100);c.waitTicks(5);shot(c,"world-horizontal-materials");pass("1280/854/1920 captures, GUI 2/3, L/ring physical contours and horizontal native material icons");
                try{Files.deleteIfExists(output.resolve("native-input.stage"));}catch(Exception ignored){}
            }
            checks.add("PASS");write("PASS",null);
        }catch(Throwable e){checks.add("FAIL "+e);try{shot(c,"failure");write("FAIL",e.toString());}catch(Exception ignored){}throw new RuntimeException(e);}
    }
    private void waitServerNative(ClientGameTestContext c,TestServerContext server){for(int i=0;i<1600;i++){var text=typeOrNull(server,"TEXT");if(text!=null&&text.get("text").getAsString().equals("ação\nlinha")&&c.computeOnClient(m->{var inline=(InlineTextEditor)read(m.gui.screen(),"inline");return inline!=null&&!inline.active;}))return;c.waitTicks(1);}throw new AssertionError("Windows typing/Ctrl+Enter was not received as ação\\nlinha");}
    private static void browserClick(ClientGameTestContext c,int px,int py){c.runOnClient(m->{var panel=read(m.gui.screen(),"browserPanel");m.gui.screen().mouseMoved(((Integer)read(panel,"left"))+((Integer)read(panel,"bw"))/2.0,((Integer)read(panel,"top"))+((Integer)read(panel,"bh"))/2.0);});c.waitTicks(5);c.runOnClient(m->{var panel=read(m.gui.screen(),"browserPanel");double x=((Integer)read(panel,"left"))+px*((Integer)read(panel,"bw"))/1280.0,y=((Integer)read(panel,"top"))+py*((Integer)read(panel,"bh"))/720.0;var screen=m.gui.screen();screen.mouseMoved(x,y);screen.mouseClicked(mouse(x,y),false);screen.mouseReleased(mouse(x,y));});c.waitTicks(3);}
    private static void nativeText(ClientGameTestContext c,String text){c.runOnClient(m->m.keyboardHandler.textInput(m.getWindow().handle(),text));c.waitTicks(2);}
    private void stage(String value)throws Exception{Files.writeString(output.resolve("native-input.stage"),value);}
    private static void waitField(ClientGameTestContext c,String value){c.waitFor(m->m.gui.screen().children().stream().anyMatch(w->w instanceof MultiLineEditBox f&&f.getValue().equals(value)||w instanceof net.minecraft.client.gui.components.EditBox box&&box.getValue().equals(value)),900);}
    private void pass(String text)throws Exception{checks.add("PASS "+text);write("RUNNING",null);}
    private void shot(ClientGameTestContext c,String name)throws Exception{Path path=output.resolve(name+".png");Files.copy(c.takeScreenshot(name),path,StandardCopyOption.REPLACE_EXISTING);shots.add(path.toString());}
    private void write(String status,String error)throws Exception{Map<String,Object> data=new LinkedHashMap<>();data.put("status",status);data.put("nativeInputMode",inputMode);data.put("checks",checks);data.put("screenshots",shots);data.put("failure",error);Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(data));Files.writeString(output.resolve("editor-selftest.log"),String.join("\n",checks)+"\n");}
}
