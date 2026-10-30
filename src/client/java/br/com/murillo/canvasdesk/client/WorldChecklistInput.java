package br.com.murillo.canvasdesk.client;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.Shapes;

/** A physical button press owns one checkbox action, independent from any editor session. */
public final class WorldChecklistInput {
    private record Target(BlockPos pos,SurfaceCache.InteractionTile tile,SurfaceCache.Checkbox box) {}
    private static Target current;
    private static boolean latched;
    private static String pending="";
    private static long sentAt;
    private WorldChecklistInput() {}
    public static void register(){
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if(!level.isClientSide()||Minecraft.getInstance().player!=player)return InteractionResult.PASS;
            if(latched)return InteractionResult.FAIL;
            if(hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
            Target target=target(Minecraft.getInstance(),hit.getBlockPos());
            if(target==null)return InteractionResult.PASS;
            latched=true;if(!pending.isEmpty())return InteractionResult.FAIL;
            JsonObject request=new JsonObject();request.addProperty("action","worldChecklist");
            pending=UUID.randomUUID().toString();sentAt=System.currentTimeMillis();
            request.addProperty("operationId",pending);request.addProperty("pos",target.pos.asLong());
            request.addProperty("moduleId",target.tile.moduleId());request.addProperty("workspace",target.tile.workspace());
            request.addProperty("tileX",target.tile.x());request.addProperty("tileY",target.tile.y());
            request.addProperty("elementId",target.box.element().id);request.addProperty("row",target.box.row());
            request.addProperty("expectedRevision",target.box.element().revision);
            request.addProperty("checked",!checked(target));request.addProperty("yaw",player.getYRot());request.addProperty("pitch",player.getXRot());
            Minecraft.getInstance().getConnection().send(new ServerboundSetCarriedItemPacket(player.getInventory().getSelectedSlot()));
            CanvasDeskClient.send(request);return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player,level,hand)->level.isClientSide()&&latched?InteractionResult.FAIL:InteractionResult.PASS);
    }
    public static void tick(Minecraft client){
        if(!client.options.keyUse.isDown())latched=false;
        if(client.player==null||client.level==null){clear();return;}
        if(!pending.isEmpty()&&System.currentTimeMillis()-sentAt>5000){pending="";client.player.sendOverlayMessage(Component.literal("Canvas Desk: confira a tarefa antes de tentar novamente."));}
        current=client.gui.screen()==null&&client.hitResult instanceof BlockHitResult hit?target(client,hit.getBlockPos()):null;
        if(current!=null&&client.player.tickCount%10==0){
            boolean pt=client.getLanguageManager().getSelected().startsWith("pt");
            client.player.sendOverlayMessage(Component.literal(!pending.isEmpty()?(pt?"Gravando…":"Saving…"):(checked(current)?(pt?"Desmarcar · botão direito":"Uncheck · right click"):(pt?"Marcar · botão direito":"Check · right click"))));
        }
    }
    private static boolean checked(Target t){return t.box.row()<t.box.element().checks.size()&&t.box.element().checks.get(t.box.row());}
    private static Target target(Minecraft client,BlockPos pos){
        var p=client.player;if(p==null||client.level==null||client.gui.screen()!=null||p.isSpectator()||p.isShiftKeyDown()||!p.getMainHandItem().isEmpty()||DeskPlacement.chosenHand(p)!=null)return null;
        var hit=BoardInteraction.pick(p,pos,p.getLookAngle());if(hit==null)return null;
        SurfaceCache.InteractionTile tile=SurfaceCache.interaction(pos);
        if(tile==null){SurfaceCache.request(pos);return null;}
        if(!tile.editable()||!(client.level.getBlockEntity(pos) instanceof DeskBlockEntity desk)||!tile.moduleId().equals(desk.moduleId.toString()))return null;
        Vec3 uv=BoardInteraction.coordinates(desk.getBlockState(),pos,hit.getLocation());
        var box=SurfaceCache.checkbox(tile,tile.x()+uv.x*256,tile.y()+uv.y*256);if(box==null)return null;
        double u1=Math.max(DeskGeometry.minU(desk.getBlockState()),(box.x()-tile.x())/256),v1=Math.max(DeskGeometry.minV(desk.getBlockState()),(box.y()-tile.y())/256);
        double u2=Math.min(DeskGeometry.maxU(desk.getBlockState()),(box.x()+box.size()-tile.x())/256),v2=Math.min(DeskGeometry.maxV(desk.getBlockState()),(box.y()+box.size()-tile.y())/256);
        if(u2<=u1||v2<=v1)return null;
        return new Target(pos.immutable(),tile,box);
    }
    public static void acceptReply(Minecraft client,JsonObject reply){
        if(reply.has("operationId")&&reply.get("operationId").getAsString().equals(pending))pending="";
        AssistedPlacementInput.acceptReply(client,reply);
    }
    public static void clear(){current=null;latched=false;pending="";}
}
