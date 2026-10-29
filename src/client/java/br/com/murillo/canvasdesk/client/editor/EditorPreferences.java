package br.com.murillo.canvasdesk.client.editor;

import br.com.murillo.canvasdesk.CanvasDesk;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.*;
import net.minecraft.util.RandomSource;
import java.nio.file.*;
import java.util.concurrent.*;

/** Local preferences and exceptional unsent drafts. No sound settings in the game are changed. */
public final class EditorPreferences {
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("canvasdesk-client.json");
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"CanvasDesk-client-files");t.setDaemon(true);return t;});
    private static boolean sounds=load();private static long lastSound;
    private EditorPreferences(){}
    private static boolean load(){try{return JsonParser.parseString(Files.readString(FILE)).getAsJsonObject().get("sounds").getAsBoolean();}catch(Exception e){return false;}}
    public static boolean sounds(){return sounds;}
    public static void toggleSounds(){sounds=!sounds;JsonObject o=new JsonObject();o.addProperty("sounds",sounds);write(FILE,o);}
    private static void write(Path path,JsonObject o){String data=o.toString();IO.execute(()->{try{Files.createDirectories(path.getParent());Files.writeString(path,data);}catch(Exception e){CanvasDesk.LOGGER.warn("Could not save client file {}",path,e);}});}
    public static void recover(String workspace,JsonObject draft){JsonObject o=new JsonObject();o.addProperty("workspace",workspace);o.add("draft",draft.deepCopy());write(FILE.resolveSibling("canvasdesk-unsaved-text.json"),o);}
    public static void feedback(boolean typing){
        long now=System.currentTimeMillis();if(!sounds||now-lastSound<(typing?125:70))return;lastSound=now;
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(SoundEvents.UI_BUTTON_CLICK.value().location(),SoundSource.BLOCKS,typing?.06f:.12f,typing?1.8f:1.15f,RandomSource.create(),false,0,SimpleSoundInstance.Attenuation.NONE,0,0,0,true));
    }
}
