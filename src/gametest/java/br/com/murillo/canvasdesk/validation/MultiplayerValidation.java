package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.client.CanvasDeskClient;
import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

/** Two independent Minecraft processes exchange genuine payloads over a loopback connection. */
public final class MultiplayerValidation {
    private final Path output=Path.of(System.getProperty("canvasdesk.projectRoot")).resolve("artifacts/multiplayer");
    public void run(ClientGameTestContext context,String role) {
        try {
            Files.createDirectories(output);
            if(role.equals("primary"))primary(context);else peer(context);
            Files.writeString(output.resolve(role+".log"),"PASS\n");
        }catch(Throwable error){try{Files.writeString(output.resolve(role+".log"),"FAIL "+error+"\n");}catch(Exception ignored){}
            throw new RuntimeException(error);}
    }
    private void primary(ClientGameTestContext context) throws Exception {
        Properties properties=new Properties();properties.setProperty("online-mode","false");properties.setProperty("server-ip","127.0.0.1");properties.setProperty("white-list","false");properties.setProperty("max-players","4");
        try(var server=context.worldBuilder().createServer(properties);var connection=server.connect()) {
            connection.waitForChunksDownload();
            server.runOnServer(s->s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),"whitelist off"));
            context.runOnClient(c->{c.options.framerateLimit().set(30);c.options.renderDistance().set(3);c.options.simulationDistance().set(5);});
            server.runOnServer(GameplayChecks::setupRectangle);context.waitTicks(25);
            int port=server.computeOnServer(s->s.getPort());Files.writeString(output.resolve("port.txt"),Integer.toString(port));
            waitServer(context,server,s->s.getPlayerList().getPlayers().size()==2,"second real client connection");
            context.waitFor(c->Files.exists(output.resolve("peer-joined.marker")),1800);
            UUID peer=server.computeOnServer(s->s.getPlayerList().getPlayers().stream().filter(p->p.getScoreboardName().equals("CanvasDeskPeer")).findFirst().orElseThrow().getUUID());
            UUID workspace=GameplayChecks.workspace();
            server.runOnServer(s->{for(var p:s.getPlayerList().getPlayers()){
                p.teleportTo(16.5,80,18.5);DeskServer.get(s).open(p,(br.com.murillo.canvasdesk.block.DeskBlockEntity)p.level().getBlockEntity(GameplayChecks.PRIMARY));}});
            context.waitForScreen(CanvasScreen.class);context.waitTicks(10);
            send(context,edit("Owner network edit",18));
            context.waitFor(c->state(c).getAsJsonArray("elements").size()==1,600);
            // Wait for the peer to prove it received the read-only document before inviting it.
            context.waitFor(c->Files.exists(output.resolve("peer-read-only.marker")),2400);
            JsonObject invite=action("invite");invite.addProperty("player",peer.toString());send(context,invite);
            waitServer(context,server,s->GameplayChecks.count(s,workspace)==2,"peer network edit");
            context.waitFor(c->state(c).getAsJsonArray("elements").size()==2,600);
            JsonObject ownerBefore=context.computeOnClient(c->state(c).getAsJsonArray("elements").get(0).getAsJsonObject().deepCopy());
            JsonObject update=update(ownerBefore,"Owner revised",36);send(context,update);waitAck(context,update,true);
            Files.writeString(output.resolve("owner-updated.marker"),"PASS");
            context.waitFor(c->Files.exists(output.resolve("peer-conflict.marker")),1200);
            JsonObject revoke=action("revoke");revoke.addProperty("player",peer.toString());send(context,revoke);
            context.waitFor(c->Files.exists(output.resolve("peer-revoked.marker")),1200);
            server.runOnServer(s->GameplayChecks.require(!GameplayChecks.view(s,workspace,peer).get("editable").getAsBoolean(),"Revoked network peer retained edit permission"));
            context.runOnClient(c->c.setScreenAndShow(null));Files.writeString(output.resolve("merge-start.marker"),"PASS");
            context.waitFor(c->Files.exists(output.resolve("peer-closed.marker")),1200);context.waitTicks(5);
            server.runOnServer(s->{GameplayChecks.prepareRealPeerMerge(s,peer);for(var p:s.getPlayerList().getPlayers()){
                BlockPos pos=p.getUUID().equals(peer)?GameplayChecks.FOREIGN:GameplayChecks.PRIMARY;
                DeskServer.get(s).open(p,(br.com.murillo.canvasdesk.block.DeskBlockEntity)p.level().getBlockEntity(pos));}});
            context.waitForScreen(CanvasScreen.class);JsonObject approval=context.computeOnClient(c->{
                JsonObject request=action("approveMerge");request.addProperty("requestId",state(c).getAsJsonArray("merges").get(0).getAsJsonObject().get("id").getAsString());return request;});
            send(context,approval);waitAck(context,approval,true);
            server.runOnServer(s->GameplayChecks.require(!DeskServer.get(s).store.resolve(workspace).equals(DeskServer.get(s).store.resolve(GameplayChecks.foreignWorkspace())),"One real owner must not complete the union"));
            Files.writeString(output.resolve("owner-approved.marker"),"PASS");
            waitServer(context,server,s->DeskServer.get(s).store.resolve(workspace).equals(DeskServer.get(s).store.resolve(GameplayChecks.foreignWorkspace())),"second real owner approval");
            context.waitFor(c->Files.exists(output.resolve("peer.log")),1200);
            GameplayChecks.require(Files.readString(output.resolve("peer.log")).strip().equals("PASS"),"Peer must complete its real UI checks");
            server.runOnServer(s->GameplayChecks.require(GameplayChecks.view(s,workspace,peer).get("owner").getAsBoolean(),"Real peer coproperty must survive the union"));
            Files.copy(context.takeScreenshot("two-client-owner"),output.resolve("owner.png"),StandardCopyOption.REPLACE_EXISTING);
            JsonObject report=new JsonObject();report.addProperty("status","PASS");report.addProperty("actualClients",2);
            report.addProperty("boundary","Two independent Minecraft clients, dedicated test server, real network payloads and live editor state");
            report.addProperty("checks","Read-only peer; owner edit received remotely; invitation; peer fontSize=24 edit synchronized to owner; explicit stale-revision conflict; revocation; two real owners approve physical union; coproperty");
            Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            context.runOnClient(c->c.setScreenAndShow(null));
        }
    }
    private void peer(ClientGameTestContext context) throws Exception {
        context.waitFor(c->Files.exists(output.resolve("port.txt")),2400);String address="127.0.0.1:"+Files.readString(output.resolve("port.txt")).strip();
        context.runOnClient(c->ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString(address),new ServerData("Canvas Desk disposable test",address,ServerData.Type.OTHER),false,null));
        context.waitFor(c->c.player!=null&&c.level!=null&&c.gui.screen()==null,1800);
        context.runOnClient(c->{c.options.framerateLimit().set(30);c.options.renderDistance().set(3);c.options.simulationDistance().set(5);});
        Files.writeString(output.resolve("peer-joined.marker"),"PASS");context.waitForScreen(CanvasScreen.class);
        context.waitFor(c->state(c).getAsJsonArray("elements").size()==1,1200);
        context.runOnClient(c->GameplayChecks.require(!state(c).get("editable").getAsBoolean(),"Uninvited real peer must be read only"));
        JsonObject stale=context.computeOnClient(c->state(c).getAsJsonArray("elements").get(0).getAsJsonObject().deepCopy());
        Files.copy(context.takeScreenshot("two-client-read-only"),output.resolve("peer-read-only.png"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(output.resolve("peer-read-only.marker"),"PASS");
        context.waitFor(c->state(c).get("editable").getAsBoolean(),1200);
        send(context,edit("Ação compartilhada: fonte 24",24));
        context.waitFor(c->state(c).getAsJsonArray("elements").size()==2,1200);
        context.waitFor(c->Files.exists(output.resolve("owner-updated.marker")),1200);
        JsonObject conflict=update(stale,"Stale peer overwrite",48);send(context,conflict);waitAck(context,conflict,false);
        context.runOnClient(c->GameplayChecks.require(state(c).get("message").getAsString().contains("Conflito"),"Stale network edit must show an explicit conflict"));
        Files.writeString(output.resolve("peer-conflict.marker"),"PASS");
        context.waitFor(c->!state(c).get("editable").getAsBoolean(),1200);
        Files.copy(context.takeScreenshot("two-client-revoked"),output.resolve("peer-revoked.png"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(output.resolve("peer-revoked.marker"),"PASS");
        context.waitFor(c->Files.exists(output.resolve("merge-start.marker")),1200);context.runOnClient(c->c.setScreenAndShow(null));
        Files.writeString(output.resolve("peer-closed.marker"),"PASS");context.waitForScreen(CanvasScreen.class);
        context.waitFor(c->Files.exists(output.resolve("owner-approved.marker")),1200);
        JsonObject approve=context.computeOnClient(c->{JsonObject request=action("approveMerge");request.addProperty("requestId",state(c).getAsJsonArray("merges").get(0).getAsJsonObject().get("id").getAsString());return request;});
        send(context,approve);waitAck(context,approve,true);
        context.runOnClient(c->GameplayChecks.require(state(c).getAsJsonArray("owners").size()==2&&state(c).get("owner").getAsBoolean(),"Peer must become co-owner after both network approvals"));
        Files.copy(context.takeScreenshot("two-client-co-owner"),output.resolve("peer-co-owner.png"),StandardCopyOption.REPLACE_EXISTING);
        context.runOnClient(c->c.disconnectWithSavingScreen());
    }
    private static JsonObject state(net.minecraft.client.Minecraft client) {
        if(!(client.gui.screen() instanceof CanvasScreen screen))return new JsonObject();
        try{var field=CanvasScreen.class.getDeclaredField("state");field.setAccessible(true);return (JsonObject)field.get(screen);}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    private static JsonObject action(String name){JsonObject r=new JsonObject();r.addProperty("action",name);r.addProperty("operationId",UUID.randomUUID().toString());return r;}
    private static JsonObject edit(String text,int size) {
        JsonObject element=new JsonObject();element.addProperty("id",UUID.randomUUID().toString());element.addProperty("type","TEXT");
        element.addProperty("x",20);element.addProperty("y",size==24?80:20);element.addProperty("w",450);element.addProperty("h",80);
        element.addProperty("text",text);element.addProperty("fontSize",size);
        JsonObject change=new JsonObject();change.addProperty("id",element.get("id").getAsString());change.addProperty("expectedRevision",-1);change.add("after",element);
        JsonArray changes=new JsonArray();changes.add(change);JsonObject request=action("edit");request.add("changes",changes);return request;
    }
    private static void send(ClientGameTestContext context,JsonObject request)throws Exception{context.runOnClient(c->CanvasDeskClient.send(request));}
    private static JsonObject update(JsonObject before,String text,int size){JsonObject after=before.deepCopy();after.addProperty("text",text);after.addProperty("fontSize",size);JsonObject c=new JsonObject();c.addProperty("id",before.get("id").getAsString());c.addProperty("expectedRevision",before.get("revision").getAsLong());c.add("after",after);JsonArray changes=new JsonArray();changes.add(c);JsonObject r=action("edit");r.add("changes",changes);return r;}
    private static void waitAck(ClientGameTestContext context,JsonObject request,boolean ok){context.waitFor(c->{JsonObject view=state(c);return view.has("ackOperation")&&view.get("ackOperation").getAsString().equals(request.get("operationId").getAsString());},1200);context.runOnClient(c->GameplayChecks.require(state(c).get("ok").getAsBoolean()==ok,"Network operation acknowledgement"));}
    private static void waitServer(ClientGameTestContext context,TestServerContext server,Predicate<net.minecraft.server.MinecraftServer> predicate,String what)throws Exception {
        for(int i=0;i<2400;i++){if(server.computeOnServer(predicate::test))return;context.waitTicks(2);}throw new AssertionError("Timed out: "+what);
    }
}
