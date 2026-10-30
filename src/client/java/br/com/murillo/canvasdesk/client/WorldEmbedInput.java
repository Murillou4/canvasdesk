package br.com.murillo.canvasdesk.client;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.editor.*;
import br.com.murillo.canvasdesk.client.media.BrowserTexture;
import br.com.murillo.canvasdesk.client.media.MediaPartyClient;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.core.*;
import com.google.gson.JsonObject;
import java.util.*;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.*;
import net.minecraft.world.phys.BlockHitResult;

/** Videos own one physical press; ordinary sites retain the focused browser opening flow. */
public final class WorldEmbedInput {
    private record Target(BlockPos pos,SurfaceCache.InteractionTile tile,CanvasElementView element,boolean video) {}
    private static final LinkedHashMap<String,JsonObject> states=new LinkedHashMap<>();
    private static Target current;
    private static String element="",module="",pending="";
    private static long at,sentAt;
    private static boolean latched,bypass;
    private WorldEmbedInput() {}

    public static void register(){
        UseBlockCallback.EVENT.register((p,level,hand,hit)->{
            Minecraft client=Minecraft.getInstance();
            if(!level.isClientSide()||client.player!=p)return InteractionResult.PASS;
            if(bypass)return InteractionResult.PASS;
            if(latched)return InteractionResult.FAIL;
            if(hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
            Target target=target(client,hit.getBlockPos());element="";module="";
            if(target==null)return InteractionResult.PASS;
            if(target.video&&(!target.tile.editable()||!MediaPartyClient.participates(target.tile.workspace(),target.element.sourceAuthor))){latched=true;client.setScreenAndShow(new PartyScreen(new MediaPartyClient.Target(target.tile.workspace(),target.element.id,target.pos,target.tile.moduleId())));return InteractionResult.FAIL;}
            if(!target.video){
                element=target.element.id;module=target.tile.moduleId();at=System.currentTimeMillis();return InteractionResult.PASS;
            }
            latched=true;if(!pending.isEmpty())return InteractionResult.FAIL;
            JsonObject request=new JsonObject();request.addProperty("action","worldMedia");
            pending=UUID.randomUUID().toString();sentAt=System.currentTimeMillis();
            request.addProperty("operationId",pending);request.addProperty("pos",target.pos.asLong());
            request.addProperty("moduleId",target.tile.moduleId());request.addProperty("workspace",target.tile.workspace());
            request.addProperty("tileX",target.tile.x());request.addProperty("tileY",target.tile.y());
            request.addProperty("elementId",target.element.id);request.addProperty("source",target.element.url);
            request.addProperty("expectedRevision",target.element.revision);
            request.addProperty("yaw",p.getYRot());request.addProperty("pitch",p.getXRot());
            client.getConnection().send(new ServerboundSetCarriedItemPacket(p.getInventory().getSelectedSlot()));
            CanvasDeskClient.send(request);return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((p,level,hand)->level.isClientSide()&&latched?InteractionResult.FAIL:InteractionResult.PASS);
    }

    public static void tick(Minecraft client){
        if(!client.options.keyUse.isDown())latched=false;
        if(client.player==null||client.level==null){clear();return;}
        if(!pending.isEmpty()&&System.currentTimeMillis()-sentAt>5000){pending="";client.player.sendOverlayMessage(Component.literal("Canvas Desk: confira a reprodução antes de tentar novamente."));}
        current=client.gui.screen()==null&&client.hitResult instanceof BlockHitResult hit?target(client,hit.getBlockPos()):null;
        if(current!=null&&current.video&&client.player.tickCount%10==0){
            var state=states.get(current.tile.workspace()+"/"+current.element.id);
            boolean playing=state!=null&&state.get("source").getAsString().equals(current.element.url)&&state.get("playing").getAsBoolean();
            boolean pt=client.getLanguageManager().getSelected().startsWith("pt");
            String text=!current.tile.editable()||!MediaPartyClient.participates(current.tile.workspace(),current.element.sourceAuthor)?(pt?"Entrar na party · botão direito":"Join party · right click"):!pending.isEmpty()?(pt?"Atualizando vídeo…":"Updating video…"):(playing?(pt?"Pausar · botão direito":"Pause · right click"):(pt?"Reproduzir · botão direito":"Play · right click"));
            client.player.sendOverlayMessage(Component.literal(text));
        }
    }

    private static Target target(Minecraft client,BlockPos pos){
        var p=client.player;
        if(p==null||client.level==null||client.gui.screen()!=null||p.isSpectator()||p.isShiftKeyDown()||!p.getMainHandItem().isEmpty()||DeskPlacement.chosenHand(p)!=null)return null;
        var paper=BoardInteraction.pick(p,pos,p.getLookAngle());if(paper==null)return null;
        var tile=SurfaceCache.interaction(pos);if(tile==null){SurfaceCache.request(pos);return null;}
        if(!(client.level.getBlockEntity(pos) instanceof DeskBlockEntity desk)||!tile.moduleId().equals(desk.moduleId.toString()))return null;
        var uv=BoardInteraction.coordinates(desk.getBlockState(),pos,paper.getLocation());double x=tile.x()+uv.x*256,y=tile.y()+uv.y*256;
        var e=SurfaceCache.topElement(tile,x,y);if(e==null||!e.type.equals("EMBED"))return null;
        var entry=BrowserTexture.get(tile.workspace(),e.id);
        boolean video=!EmbedSource.youtubeId(e.url).isEmpty()||EmbedSource.directVideo(e.url)||entry!=null&&!entry.closed&&entry.ready&&!entry.mediaKind.equals("web");
        if(video&&!EmbedGeometry.fit(e.x,e.y,e.w,e.h).contains(x,y))return null;
        return new Target(pos.immutable(),tile,e,video);
    }

    public static void acceptState(JsonObject state){
        if(!state.has("workspace")||!state.has("elementId")||!state.has("mediaRevision"))return;
        String key=state.get("workspace").getAsString()+"/"+state.get("elementId").getAsString();
        JsonObject old=states.get(key);
        if(old!=null&&old.get("mediaSession").equals(state.get("mediaSession"))&&old.get("mediaRevision").getAsLong()>state.get("mediaRevision").getAsLong())return;
        states.put(key,state.deepCopy());while(states.size()>64)states.remove(states.keySet().iterator().next());
    }
    public static void ended(JsonObject state){states.remove(state.get("workspace").getAsString()+"/"+state.get("elementId").getAsString());}
    public static void acceptReply(Minecraft client,JsonObject reply){
        BrowserTexture.accept(reply);
        if(reply.has("operationId")&&reply.get("operationId").getAsString().equals(pending))pending="";
        AssistedPlacementInput.acceptReply(client,reply);
    }
    public static void opened(CanvasScreen screen,JsonObject state){if(!element.isEmpty()&&System.currentTimeMillis()-at<2000&&state.has("moduleId")&&module.equals(state.get("moduleId").getAsString())){String id=element;element="";screen.focusEmbed(id);}}
    public static void openEditor(MediaPartyClient.Target target){
        var c=Minecraft.getInstance();if(c.player==null||c.level==null||!c.player.getMainHandItem().isEmpty()||!(c.level.getBlockEntity(target.pos()) instanceof DeskBlockEntity desk)||!desk.moduleId.toString().equals(target.module()))return;
        var hit=BoardInteraction.pick(c.player,target.pos(),c.player.getLookAngle());if(hit==null)return;
        element=target.element();module=target.module();at=System.currentTimeMillis();latched=false;c.setScreenAndShow(null);bypass=true;
        try{c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,hit);}finally{bypass=false;}
    }
    public static void clear(){bypass=false;current=null;element="";module="";pending="";latched=false;states.clear();}
}
