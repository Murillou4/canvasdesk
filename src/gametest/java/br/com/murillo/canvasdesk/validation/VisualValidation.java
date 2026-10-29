package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.server.DeskServer;
import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.nio.file.*;
import java.util.*;

/** Camera evidence supplements state assertions: readable front faces at a known three-block distance. */
public final class VisualValidation {
    private final Path output=Path.of(System.getProperty("canvasdesk.projectRoot")).resolve("artifacts/visual");
    public void run(ClientGameTestContext context) {
        try {
            Files.createDirectories(output);
            try(var world=context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                context.runOnClient(c->{c.options.framerateLimit().set(30);c.options.renderDistance().set(3);c.options.simulationDistance().set(5);});
                world.getServer().runOnServer(s->{GameplayChecks.setupRectangle(s);GameplayChecks.expandRing(s);GameplayChecks.setupOrientations(s);
                    var actor=GameplayChecks.player(s);var store=DeskServer.get(s).store;
                    JsonObject title=element("TEXT","Quadro de tarefas",24,24,620,60,28);
                    JsonObject checklist=element("CHECKLIST","Revisão concluída\nAção e conexão salvas\nCompartilhar o quadro",20,100,600,240,24);
                    JsonArray checks=new JsonArray();checks.add(true);checks.add(false);checks.add(false);checklist.add("checks",checks);
                    GameplayChecks.success(store.action(GameplayChecks.workspace(),actor.getUUID(),edit(title,checklist)));
                    int i=0;for(Direction facing:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}){
                        var desk=(DeskBlockEntity)actor.level().getBlockEntity(new BlockPos(35+6*i++,80,15));
                        JsonObject label=element("TEXT",facing.name()+" 123",24,24,480,70,28);
                        GameplayChecks.success(store.action(desk.workspace,actor.getUUID(),edit(label)));
                    }
                });
                world.getServer().runCommand("time set noon");world.getServer().runCommand("weather clear");
                context.waitTicks(30);world.getServer().runOnServer(GameplayChecks::assertOrientations);
                world.getServer().runCommand("tp @a 16.5 80 18.5 180 0");context.waitTicks(80);
                world.getConnection().waitForChunksRender(false,1800);SurfaceChecks.waitForContent(context,GameplayChecks.PRIMARY,"Quadro");shot(context,"board-three-blocks-1280x720");
                context.getInput().resizeWindow(854,480);context.waitTicks(10);shot(context,"board-three-blocks-854x480");
                context.getInput().resizeWindow(1280,720);context.waitTicks(10);
                world.getServer().runOnServer(s->GameplayChecks.open(s,GameplayChecks.PRIMARY));context.waitForScreen(CanvasScreen.class);
                shot(context,"school-board-editor");context.runOnClient(c->c.setScreenAndShow(null));
                int i=0;for(Direction facing:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}){
                    int x=35+6*i++;
                    world.getServer().runCommand("tp @a "+(x+.5+3*facing.getStepX())+" 80 "+(15.5+3*facing.getStepZ())+" "+switch(facing){case NORTH->0;case EAST->90;case SOUTH->180;default->270;}+" 0");
                    BlockPos target=new BlockPos(x,80,15);
                    context.waitFor(c->c.level.getBlockEntity(target) instanceof DeskBlockEntity,1800);
                    context.waitTicks(60);world.getConnection().waitForChunksRender(false,1800);
                    SurfaceChecks.waitForContent(context,target,facing.name());shot(context,"orientation-"+facing.name().toLowerCase(Locale.ROOT));
                }
            }
            Files.writeString(output.resolve("visual-selftest.log"),"PASS three-block cameras at 1280x720 and 854x480; four readable orientation labels; editor\nPASS\n");
        }catch(Throwable error){try{Files.writeString(output.resolve("visual-selftest.log"),"FAIL "+error+"\n");}catch(Exception ignored){}throw new RuntimeException(error);}
    }
    private void shot(ClientGameTestContext context,String name)throws Exception{Files.copy(context.takeScreenshot(name),output.resolve(name+".png"),StandardCopyOption.REPLACE_EXISTING);}
    private static JsonObject element(String type,String text,double x,double y,double w,double h,double size){JsonObject e=new JsonObject();e.addProperty("id",UUID.randomUUID().toString());e.addProperty("type",type);e.addProperty("text",text);e.addProperty("x",x);e.addProperty("y",y);e.addProperty("w",w);e.addProperty("h",h);e.addProperty("fontSize",size);return e;}
    private static JsonObject edit(JsonObject...elements){JsonObject request=new JsonObject();request.addProperty("action","edit");request.addProperty("operationId",UUID.randomUUID().toString());JsonArray changes=new JsonArray();for(JsonObject e:elements){JsonObject c=new JsonObject();c.addProperty("id",e.get("id").getAsString());c.addProperty("expectedRevision",-1);c.add("after",e);changes.add(c);}request.add("changes",changes);return request;}
}
