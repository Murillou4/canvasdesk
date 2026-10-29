package br.com.murillo.canvasdesk.client;

import br.com.murillo.canvasdesk.block.DeskPlacement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/** A placement hint owns its click; the server revalidates and commits the actual placement. */
public final class AssistedPlacementInput {
    private static Player interceptedPlayer;
    private static int interceptedTick = Integer.MIN_VALUE;
    private AssistedPlacementInput() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                level.isClientSide() ? intercept(player) : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) ->
                level.isClientSide() ? intercept(player) : InteractionResult.PASS);
    }

    private static InteractionResult intercept(Player player) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != player || client.gui.screen() != null || client.getConnection() == null)
            return InteractionResult.PASS;
        // Air-use can continue to the second hand even after FAIL; a captured click sends only once.
        if (interceptedPlayer == player && interceptedTick == player.tickCount) return InteractionResult.FAIL;
        InteractionHand hand = DeskPlacement.chosenHand(player);
        if (hand == null) return InteractionResult.PASS;
        // The first callback can belong to an empty main hand while the preview uses the offhand.
        // Resolve the chosen hand again at click time instead of reusing a stale rendered preview.
        DeskPlacement.Result placement = DeskPlacement.resolve(player, hand);
        if (placement == null) return InteractionResult.PASS;

        JsonObject request = new JsonObject();
        request.addProperty("action", "assistPlace");
        request.addProperty("operationId", UUID.randomUUID().toString());
        request.addProperty("hand", hand.name());
        request.addProperty("anchor", placement.anchor().asLong());
        request.addProperty("moduleId", placement.moduleId().toString());
        request.addProperty("target", placement.pos().asLong());
        request.addProperty("direction", placement.expansion().name());
        request.addProperty("yaw", player.getYRot());
        request.addProperty("pitch", player.getXRot());
        interceptedPlayer = player;
        interceptedTick = player.tickCount;
        // Our custom payload bypasses vanilla's carried-slot synchronization; preserve packet order.
        client.getConnection().send(new ServerboundSetCarriedItemPacket(player.getInventory().getSelectedSlot()));
        CanvasDeskClient.send(request);
        // Even an invalid candidate owns the click, so vanilla cannot place a different block instead.
        return InteractionResult.FAIL;
    }

    public static void clear() {
        interceptedPlayer = null;
        interceptedTick = Integer.MIN_VALUE;
    }

    public static void acceptReply(Minecraft client, JsonObject reply) {
        if (reply.has("ok") && !reply.get("ok").getAsBoolean() && client.player != null) {
            String message = reply.has("message") ? reply.get("message").getAsString() : "";
            if (!message.isBlank()) client.player.sendOverlayMessage(Component.literal(message));
        }
    }
}
