package br.com.murillo.canvasdesk.validation;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import java.nio.file.*;
import java.util.*;

/** Runs in a normal Minecraft client, with the gametest scheduler disabled. Never ships in the mod JAR. */
public final class PerformanceProbe implements ClientModInitializer {
    private long entered,lastSample,closing;
    private final List<Integer> fps=new ArrayList<>();
    private final List<Double> serverMillis=new ArrayList<>();
    @Override public void onInitializeClient() {
        if(!Boolean.getBoolean("canvasdesk.performanceProbe"))return;
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            long now=System.currentTimeMillis();
            if(closing!=0){if(client.level==null&&now-closing>1500)client.stop();return;}
            if(client.player==null||client.level==null)return;
            if(entered==0){
                entered=now;client.options.framerateLimit().set(30);client.options.renderDistance().set(3);
                client.options.guiScale().set(2);client.resizeGui();
                var server=client.getSingleplayerServer();if(server!=null)server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"tp @a 16.5 80 18.5 180 0"));
            }
            if(now-entered<12000||now-lastSample<1000)return;
            lastSample=now;fps.add(client.getFps());
            var server=client.getSingleplayerServer();if(server!=null)serverMillis.add(server.getAverageTickTimeNanos()/1_000_000.0);
            if(fps.size()<12)return;
            try{
                Path output=Path.of(System.getProperty("canvasdesk.projectRoot")).resolve("artifacts/performance");Files.createDirectories(output);
                var sorted=new ArrayList<>(fps);Collections.sort(sorted);int median=sorted.get(sorted.size()/2),minimum=sorted.getFirst();
                JsonObject report=new JsonObject();report.addProperty("status",median>=25&&minimum>=20?"PASS":"FAIL");
                report.addProperty("mode","Normal client; gametest scheduler disabled; one window; 854x480; 3 chunks; 30 FPS cap");
                report.addProperty("medianFps",median);report.addProperty("minimumFps",minimum);report.add("fpsSamples",new Gson().toJsonTree(fps));
                report.add("serverTickMillis",new Gson().toJsonTree(serverMillis));report.addProperty("jvmHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
                Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
                Files.writeString(output.resolve("performance-selftest.log"),report.get("status").getAsString()+"\n");
                Screenshot.grab(client,false);
                closing=now;client.disconnectWithSavingScreen();
            }catch(Exception error){throw new RuntimeException(error);}
        });
    }
}
