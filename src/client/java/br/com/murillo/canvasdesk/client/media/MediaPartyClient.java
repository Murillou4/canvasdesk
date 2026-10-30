package br.com.murillo.canvasdesk.client.media;

import br.com.murillo.canvasdesk.client.CanvasDeskClient;
import br.com.murillo.canvasdesk.client.editor.CanvasElementView;
import br.com.murillo.canvasdesk.core.MediaParties;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.UUID;

/** Self-consent is independent from the right to change a shared document. */
public final class MediaPartyClient {
    public record Target(String workspace,String element,BlockPos pos,String module) {}
    private static JsonObject current=new JsonObject();
    private static boolean leaving;
    private static long lastPoll;
    public static String message="";
    private MediaPartyClient(){}
    public static boolean member(String workspace){return !leaving&&current.has("member")&&current.get("member").getAsBoolean()&&current.get("workspace").getAsString().equals(workspace);}
    public static boolean participates(String workspace,String author){var p=Minecraft.getInstance().player;return member(workspace)&&(current.get("explicit").getAsBoolean()||p!=null&&p.getUUID().toString().equals(author));}
    public static boolean continuous(){return current.has("mode")&&current.get("mode").getAsString().equals("CONTINUOUS");}
    public static double gain(BrowserTexture.Entry entry){
        var c=Minecraft.getInstance();if(c.player==null||!member(entry.workspace)||!current.has("element")||!current.getAsJsonObject("element").get("id").getAsString().equals(entry.elementId)||!current.getAsJsonObject("element").get("url").getAsString().equals(entry.url)||!current.has("audioAllowed")||!current.get("audioAllowed").getAsBoolean())return 0;
        if(continuous())return 1;
        if(!current.has("anchorPos")||!current.get("dimension").getAsString().equals(c.level.dimension().toString()))return 0;
        var pos=BlockPos.of(current.get("anchorPos").getAsLong());return MediaParties.gain(MediaParties.Mode.PROXIMITY,Math.sqrt(c.player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)));
    }
    public static boolean keepAlive(String workspace,String element){
        if(!member(workspace)||!current.has("element")||!current.getAsJsonObject("element").get("id").getAsString().equals(element))return false;
        if(continuous())return true;var c=Minecraft.getInstance();if(c.player==null||c.level==null||!current.has("anchorPos")||!current.get("dimension").getAsString().equals(c.level.dimension().toString()))return false;
        var pos=BlockPos.of(current.get("anchorPos").getAsLong());return c.player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<256;
    }
    public static void join(Target target,boolean continuous){
        message="Entrando…";JsonObject r=new JsonObject();r.addProperty("action","partyJoin");r.addProperty("nonce",UUID.randomUUID().toString());r.addProperty("workspace",target.workspace);r.addProperty("elementId",target.element);r.addProperty("pos",target.pos.asLong());r.addProperty("moduleId",target.module);r.addProperty("mode",continuous?"CONTINUOUS":"PROXIMITY");CanvasDeskClient.send(r);
    }
    public static void leave(){leave(current.has("workspace")?current.get("workspace").getAsString():"");}
    public static void leave(String workspace){leaving=true;message="Som desligado.";BrowserTexture.silence();JsonObject r=new JsonObject();r.addProperty("action","partyLeave");if(!workspace.isBlank())r.addProperty("workspace",workspace);r.addProperty("nonce",UUID.randomUUID().toString());CanvasDeskClient.send(r);}
    public static void accept(JsonObject state){
        BrowserTexture.accept(state);
        if(leaving&&!state.has("nonce"))return;
        current=state.deepCopy();leaving=false;
        message=state.has("ok")&&!state.get("ok").getAsBoolean()?state.get("message").getAsString():member(state.has("workspace")?state.get("workspace").getAsString():"")?(continuous()?"Ouvindo · contínuo":"Ouvindo · proximidade"):"Você não está ouvindo.";
    }
    public static void tick(Minecraft c){
        if(c.player==null||c.level==null)return;
        if(!leaving&&current.has("member")&&current.get("member").getAsBoolean()){
            if(current.has("element")&&current.has("anchorPos")&&keepAlive(current.get("workspace").getAsString(),current.getAsJsonObject("element").get("id").getAsString()))BrowserTexture.pin(current.get("workspace").getAsString(),new CanvasElementView(current.getAsJsonObject("element")),BlockPos.of(current.get("anchorPos").getAsLong()),current.get("moduleId").getAsString());
            long time=System.currentTimeMillis();if(time-lastPoll>=1000){lastPoll=time;JsonObject r=new JsonObject();r.addProperty("action","partyPoll");CanvasDeskClient.send(r);}
        }
    }
    public static void clear(){current=new JsonObject();leaving=false;lastPoll=0;message="";}
}
