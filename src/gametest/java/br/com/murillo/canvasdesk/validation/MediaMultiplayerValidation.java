package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.CanvasDeskClient;
import br.com.murillo.canvasdesk.client.WorldEmbedInput;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.client.editor.*;
import br.com.murillo.canvasdesk.client.media.BrowserTexture;
import br.com.murillo.canvasdesk.client.media.MediaPartyClient;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;
import static br.com.murillo.canvasdesk.validation.EditorValidation.*;

/** Two independent client processes, genuine TCP payloads and native Chromium decoders. */
final class MediaMultiplayerValidation {
    private static final BlockPos BOARD=new BlockPos(8,80,8);
    private final Path root=Path.of(System.getProperty("canvasdesk.projectRoot"));
    private final Path output=Path.of(System.getProperty("canvasdesk.probeOutput"));
    private JsonObject data;private final List<String> checks=new ArrayList<>();
    void run(ClientGameTestContext c,String role){try{Files.createDirectories(output);c.runOnClient(m->m.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0));require(c.computeOnClient(m->(org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(m.getWindow().handle())&org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN)!=0),"Validation window must stay hidden");if(role.equals("mediaOwner"))owner(c);else peer(c);Files.writeString(output.resolve(role+".log"),"PASS\n");}catch(Throwable e){try{Files.writeString(output.resolve(role+".log"),"FAIL "+e+"\n");}catch(Exception ignored){}throw new RuntimeException(e);}}
    private void owner(ClientGameTestContext c)throws Exception {
        Properties properties=new Properties();properties.setProperty("online-mode","false");properties.setProperty("server-ip","127.0.0.1");properties.setProperty("white-list","false");properties.setProperty("max-players","4");
        properties.setProperty("spawn-protection","0");
        try(var http=MediaFixture.start(root);var server=c.worldBuilder().createServer(properties);var connection=server.connect()){
            c.waitFor(m->m.player!=null&&m.level!=null,400);
            server.runOnServer(s->s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),"whitelist off"));
            server.runOnServer(s->{var p=player(s);p.setGameMode(GameType.SURVIVAL);p.getAbilities().mayfly=true;p.getAbilities().flying=true;p.onUpdateAbilities();for(int x=5;x<=13;x++)for(int z=5;z<=12;z++)for(int y=79;y<84;y++)p.level().setBlock(new BlockPos(x,y,z),y==79?Blocks.STONE_BRICKS.defaultBlockState():Blocks.AIR.defaultBlockState(),3);fixture(s,BOARD,false,ItemStack.EMPTY);fixture(s,BOARD.east(),false,ItemStack.EMPTY);});
            var video=primitive("EMBED","Sincronização real",20,20,220,123.75,9);video.addProperty("url",http.url("stream"));var web=primitive("EMBED","Navegação compartilhada",270,20,220,160,9);web.addProperty("url",http.url("page"));server.runOnServer(s->{edit(s,BOARD,video,-1);edit(s,BOARD,web,-1);});
            c.waitFor(m->m.level.getBlockEntity(BOARD) instanceof DeskBlockEntity,200);aim(c,server,BOARD,128,200,3);click(c,BOARD,128,200);waitEditor(c);
            data=server.computeOnServer(s->{var desk=(DeskBlockEntity)player(s).level().getBlockEntity(BOARD);JsonObject d=new JsonObject();d.addProperty("workspace",desk.workspace.toString());d.addProperty("moduleId",desk.moduleId.toString());d.addProperty("port",s.getPort());d.add("video",video);d.add("web",web);d.addProperty("next",http.url("next"));return d;});
            c.runOnClient(m->((CanvasScreen)m.gui.screen()).focusEmbed(video.get("id").getAsString()));waitVideo(c);
            control(c,"video","play",0.0,null,null,0,true);c.waitFor(m->entry("video").observedPosition>1,150);
            Files.writeString(output.resolve("connection.json"),data.toString());
            boolean joined=false;for(int wait=0;wait<1300;wait++){if(server.computeOnServer(s->s.getPlayerList().getPlayers().size()==2)){joined=true;break;}if(wait>0&&wait%400==0)control(c,"video","seek",0.0,null,null,0,true);c.waitTicks(1);}require(joined,"Second client did not connect within bounded startup wait");
            server.runOnServer(s->{var p=s.getPlayerList().getPlayers().stream().filter(v->v.getScoreboardName().equals("CanvasDeskPeer")).findFirst().orElseThrow();p.getAbilities().mayfly=true;p.getAbilities().flying=true;p.onUpdateAbilities();p.teleportTo(p.level(),8.5,80,11.5,Set.of(),180,20,false);});
            c.waitFor(m->Files.exists(output.resolve("peer-reader.marker")),600);checks.add("PASS visitor audio blocked until explicit board party join; reader control still rejected");
            UUID peer=server.computeOnServer(s->s.getPlayerList().getPlayers().stream().filter(p->p.getScoreboardName().equals("CanvasDeskPeer")).findFirst().orElseThrow().getUUID());
            server.runOnServer(s->{JsonObject invite=new JsonObject();invite.addProperty("action","invite");invite.addProperty("player",peer.toString());var result=DeskServer.get(s).store.action(UUID.fromString(data.get("workspace").getAsString()),player(s).getUUID(),invite);require(result.get("ok").getAsBoolean(),"Invite rejected");});
            c.waitFor(m->Files.exists(output.resolve("peer-controlled.marker")),400);c.waitFor(m->entry("video").state!=null&&!entry("video").state.get("playing").getAsBoolean()&&Math.abs(entry("video").observedPosition-18)<.5,200);checks.add("PASS invited peer world-click pause without editor and seek reached owner native decoder within 500ms");
            control(c,"video","seek",40.0,null,null,-1,false);require(Math.abs(c.computeOnClient(m->entry("video").state.get("position").getAsDouble())-18)<.5,"Stale command overwrote shared position");checks.add("PASS concurrent stale revision rejected without overwrite");
            c.waitFor(m->entry("web")!=null&&entry("web").state!=null,200);control(c,"web","navigate",null,data.get("next").getAsString(),null,0,true);control(c,"web","scroll",null,null,.65,0,true);Files.writeString(output.resolve("navigation.marker"),"ready");
            c.waitFor(m->Files.exists(output.resolve("peer-reconnected.marker")),500);checks.add("PASS shared address/scroll, disconnect and real reconnection retain transient session");
            Files.copy(c.takeScreenshot("media-owner"),output.resolve("owner.png"),StandardCopyOption.REPLACE_EXISTING);
            var oldEntry=c.computeOnClient(m->entry("video"));server.runOnServer(s->{var updated=video.deepCopy();updated.addProperty("url",http.url("clip.webm?changed=1"));var desk=(DeskBlockEntity)player(s).level().getBlockEntity(BOARD);var before=DeskServer.get(s).store.element(desk.workspace,UUID.fromString(video.get("id").getAsString()));edit(s,BOARD,updated,before.get("revision").getAsLong());});c.waitFor(m->oldEntry.closed,100);checks.add("PASS source change explicitly terminates the old client media session");
            JsonObject summary=new JsonObject();summary.addProperty("status","PASS");summary.addProperty("actualClients",2);summary.addProperty("hiddenWindows",true);JsonArray list=new JsonArray();checks.forEach(list::add);summary.add("checks",list);Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(summary));
            c.waitFor(m->Files.exists(output.resolve("mediaPeer.log")),100);require(Files.readString(output.resolve("mediaPeer.log")).strip().equals("PASS"),"Peer did not finish");
        }
    }
    private void peer(ClientGameTestContext c)throws Exception {
        c.waitFor(m->Files.exists(output.resolve("connection.json")),1000);data=JsonParser.parseString(Files.readString(output.resolve("connection.json"))).getAsJsonObject();connect(c);
        look(c,128,200);click(c,BOARD,128,200);waitEditor(c);c.runOnClient(m->((CanvasScreen)m.gui.screen()).focusEmbed(data.getAsJsonObject("video").get("id").getAsString()));waitVideo(c);
        require(c.computeOnClient(m->!entry("video").editable),"Reader gained control");c.waitFor(m->Math.abs(entry("video").observedPosition-entry("video").projected().get("position").getAsDouble())<.5,150);
        require(!c.computeOnClient(m->MediaPartyClient.member(data.get("workspace").getAsString())),"Visitor auto-joined party");
        require(c.computeOnClient(m->!entry("video").state.get("audioAllowed").getAsBoolean()&&entry("video").audioBlocked),"Visitor audio was not blocked");
        widget(c,"Party");widget(c,"Ouvir continuamente");c.waitFor(m->MediaPartyClient.member(data.get("workspace").getAsString()),200);widget(c,"Voltar");
        require(!c.computeOnClient(m->entry("video").editable),"Party membership granted editor ACL");
        control(c,"video","seek",45.0,null,null,0,false);Files.writeString(output.resolve("peer-reader.marker"),"PASS");
        c.waitFor(m->entry("video").editable,200);key(c,com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE,0);close(c);look(c,128,80);c.runOnClient(m->SurfaceCache.request(BOARD));c.waitFor(m->SurfaceCache.interaction(BOARD)!=null&&SurfaceCache.interaction(BOARD).editable(),200);c.runOnClient(m->m.options.keyUse.setDown(true));click(c,BOARD,128,80);c.waitFor(m->entry("video").state!=null&&!entry("video").state.get("playing").getAsBoolean(),200);require(c.computeOnClient(m->m.gui.screen()==null),"World media press opened editor on peer");c.runOnClient(m->m.options.keyUse.setDown(false));c.waitTicks(3);control(c,"video","seek",18.0,null,null,0,true);c.waitFor(m->Math.abs(entry("video").observedPosition-18)<.5,150);Files.writeString(output.resolve("peer-controlled.marker"),"PASS");
        c.waitFor(m->Files.exists(output.resolve("navigation.marker")),400);c.waitFor(m->entry("web")!=null&&entry("web").state!=null&&entry("web").state.get("navigationUrl").getAsString().equals(data.get("next").getAsString())&&Math.abs(entry("web").state.get("scrollY").getAsDouble()-.65)<.001,200);
        Files.copy(c.takeScreenshot("media-peer"),output.resolve("peer.png"),StandardCopyOption.REPLACE_EXISTING);String session=c.computeOnClient(m->entry("video").state.get("mediaSession").getAsString());
        c.runOnClient(m->m.disconnect(new TitleScreen(),false));c.waitFor(m->m.level==null,100);connect(c);look(c,128,200);click(c,BOARD,128,200);waitEditor(c);c.runOnClient(m->((CanvasScreen)m.gui.screen()).focusEmbed(data.getAsJsonObject("video").get("id").getAsString()));waitVideo(c);
        require(!c.computeOnClient(m->MediaPartyClient.member(data.get("workspace").getAsString())),"Reconnect restored guest consent");
        c.waitFor(m->entry("video")!=null&&entry("video").state.get("mediaSession").getAsString().equals(session)&&!entry("video").state.get("playing").getAsBoolean()&&Math.abs(entry("video").observedPosition-18)<.5,200);Files.writeString(output.resolve("peer-reconnected.marker"),"PASS");
        c.runOnClient(m->m.disconnect(new TitleScreen(),false));
    }
    private void connect(ClientGameTestContext c){String address="127.0.0.1:"+data.get("port").getAsInt();c.runOnClient(m->ConnectScreen.startConnecting(new TitleScreen(),m,ServerAddress.parseString(address),new ServerData("Canvas Desk media test",address,ServerData.Type.OTHER),false,null));c.waitFor(m->m.player!=null&&m.level!=null&&m.gui.screen()==null&&m.level.getBlockEntity(BOARD) instanceof DeskBlockEntity&&m.player.distanceToSqr(8.5,80,11.5)<9,400);}
    private void look(ClientGameTestContext c,double x,double y){c.runOnClient(m->{var point=BoardPlane.surfacePoint(m.level.getBlockState(BOARD),x/256,y/256).add(net.minecraft.world.phys.Vec3.atLowerCornerOf(BOARD));var delta=point.subtract(m.player.getEyePosition());m.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));m.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));});c.waitTicks(3);}
    private BrowserTexture.Entry entry(String type){return BrowserTexture.byId(data.getAsJsonObject(type).get("id").getAsString());}
    private void waitVideo(ClientGameTestContext c){c.waitFor(m->{var e=entry("video");return e!=null&&e.state!=null&&e.ready&&e.observedReady;},400);}
    private void control(ClientGameTestContext c,String type,String command,Double position,String url,Double scroll,int revisionDelta,boolean ok){String nonce=UUID.randomUUID().toString();c.runOnClient(m->{var e=entry(type);var r=new JsonObject();r.addProperty("action","mediaControl");r.addProperty("workspace",e.workspace);r.addProperty("elementId",e.elementId);r.addProperty("moduleId",data.get("moduleId").getAsString());r.addProperty("pos",BOARD.asLong());r.addProperty("source",e.url);r.add("mediaSession",e.state.get("mediaSession"));r.addProperty("mediaRevision",e.state.get("mediaRevision").getAsLong()+revisionDelta);r.addProperty("command",command);r.addProperty("nonce",nonce);if(position!=null)r.addProperty("position",position);if(url!=null)r.addProperty("url",url);if(scroll!=null){r.addProperty("scrollX",0);r.addProperty("scrollY",scroll);}CanvasDeskClient.send(r);});c.waitFor(m->BrowserTexture.receipt(nonce)!=null,150);require(c.computeOnClient(m->BrowserTexture.receipt(nonce).get("ok").getAsBoolean())==ok,"Unexpected media control acknowledgement: "+command);c.waitTicks(3);}
}
