package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.item.ItemStack;
import java.nio.file.*;
import java.util.UUID;

/** Storage/topology stress runs in a dedicated JVM, without a renderer or a client test scheduler. */
public final class DedicatedValidation implements ModInitializer {
    private final Path output=Path.of(System.getProperty("canvasdesk.projectRoot")).resolve("artifacts/dedicated");
    private final boolean reload=Boolean.getBoolean("canvasdesk.serverReload");
    private final UUID owner=UUID.nameUUIDFromBytes("CanvasDeskHeadlessOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private int ticks,stage;private UUID workspace;private ServerPlayer actor;private boolean finished;
    private double placementMillis,writeMillis;private long heapBefore;
    @Override public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(this::tick);}
    private void tick(MinecraftServer server) {
        if(finished||++ticks<20)return;
        try {
            Files.createDirectories(output);
            var level=server.overworld();var service=DeskServer.get(server);
            if(stage==0) {
                try{Class.forName("net.minecraft.client.Minecraft",false,getClass().getClassLoader());throw new AssertionError("Client classes present on dedicated validation classpath");}
                catch(ClassNotFoundException expected){}
                heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
                if(reload){workspace=UUID.fromString(JsonParser.parseString(Files.readString(output.resolve("summary.json"))).getAsJsonObject().get("workspace").getAsString());
                    level.getChunk(4,2);level.getChunk(5,2);stage=1;return;}
                actor=new ServerPlayer(server,level,new GameProfile(owner,"CanvasDeskLoad"),ClientInformation.createDefault());
            }
            if(reload) {
                if(++stage<20)return;
                require(service.store.elementCount(workspace)==10000,"Restart lost elements");
                var first=desk(server,0,0);require(service.store.resolve(first.workspace).equals(service.store.resolve(workspace)),"Restart lost module reference");
                require(level.getBlockState(first.getBlockPos()).getValue(DeskBlock.FACING)==Direction.SOUTH,"Restart lost orientation");
                require(desk(server,31,31).tileX==31*256&&desk(server,31,31).tileY==31*256,"Restart projection drift");
                require(service.store.view(workspace,owner,0,0,256,256).getAsJsonArray("elements").size()>0,"Restart payload region empty");
                JsonObject report=new JsonObject();report.addProperty("status","PASS");report.addProperty("elements",10000);report.addProperty("modules",1024);
                report.addProperty("clientClassesAbsent",true);write("restart-summary.json",report);finish(server,"restart-selftest.log");return;
            }
            if(stage<32) {
                long started=System.nanoTime();int row=stage;
                for(int column=0;column<32;column++) {
                    BlockPos p=new BlockPos(64+column,111-row,40);
                    level.setBlock(p,CanvasDesk.DESK.defaultBlockState().setValue(DeskBlock.FACING,Direction.SOUTH),3);
                    service.placed(level,(DeskBlockEntity)level.getBlockEntity(p),actor,new ItemStack(CanvasDesk.DESK_ITEM));
                }
                if(row==0)workspace=desk(server,0,0).workspace;
                placementMillis+=(System.nanoTime()-started)/1_000_000.0;stage++;return;
            }
            if(stage++<52)return;
            if(stage<=63) {
                int batch=stage-53;if(batch<10) {
                    JsonObject request=new JsonObject();request.addProperty("action","edit");request.addProperty("operationId",UUID.randomUUID().toString());JsonArray changes=new JsonArray();
                    for(int n=0;n<1000;n++) {
                        int i=batch*1000+n;JsonObject e=new JsonObject();e.addProperty("id",UUID.randomUUID().toString());e.addProperty("type",i%3==0?"TEXT":"RECTANGLE");
                        e.addProperty("x",(i%100)*81);e.addProperty("y",(i/100)*81);e.addProperty("w",48);e.addProperty("h",32);e.addProperty("text","Ação "+i);e.addProperty("fontSize",9);
                        JsonObject change=new JsonObject();change.addProperty("id",e.get("id").getAsString());change.addProperty("expectedRevision",-1);change.add("after",e);changes.add(change);
                    }
                    request.add("changes",changes);long started=System.nanoTime();JsonObject result=service.store.action(workspace,owner,request);
                    require(result.get("ok").getAsBoolean(),"Headless batch rejected: "+result);writeMillis+=(System.nanoTime()-started)/1_000_000.0;return;
                }
            }
            require(service.store.elementCount(workspace)==10000,"Headless element count");
            for(int row=0;row<32;row++)for(int col=0;col<32;col++){var tile=desk(server,col,row);require(service.store.resolve(tile.workspace).equals(service.store.resolve(workspace)),"Headless document split");require(tile.tileX==col*256&&tile.tileY==row*256,"Headless projection drift");}
            long query=System.nanoTime();int visible=service.store.view(workspace,owner,0,0,256,256).getAsJsonArray("elements").size();double queryMillis=(System.nanoTime()-query)/1_000_000.0;
            require(visible>0&&visible<100,"Region query loaded full document");
            JsonObject report=new JsonObject();report.addProperty("status","PASS");report.addProperty("workspace",workspace.toString());report.addProperty("modules",1024);report.addProperty("elements",10000);
            report.addProperty("modulePlacementMillis",placementMillis);report.addProperty("elementWriteMillis",writeMillis);report.addProperty("singleTileQueryMillis",queryMillis);report.addProperty("singleTileElements",visible);
            report.addProperty("heapBeforeBytes",heapBefore);report.addProperty("heapAfterBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());report.addProperty("clientClassesAbsent",true);
            report.addProperty("scope","Actual dedicated-server blocks, normal topology service, durable documents and JVM heap; no renderer; synthetic owner only for placement callbacks");
            server.saveEverything(false,true,true);write("summary.json",report);finish(server,"gameplay-selftest.log");
        }catch(Throwable error){try{Files.writeString(output.resolve(reload?"restart-selftest.log":"gameplay-selftest.log"),"FAIL "+error+"\n");}catch(Exception ignored){}CanvasDesk.LOGGER.error("Headless validation failed",error);finished=true;server.halt(false);}
    }
    private DeskBlockEntity desk(MinecraftServer server,int column,int row){return (DeskBlockEntity)server.overworld().getBlockEntity(new BlockPos(64+column,111-row,40));}
    private void write(String name,JsonObject value)throws Exception{Files.writeString(output.resolve(name),new GsonBuilder().setPrettyPrinting().create().toJson(value));}
    private void finish(MinecraftServer server,String log)throws Exception{Files.writeString(output.resolve(log),"PASS\n");finished=true;server.halt(false);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
