package br.com.murillo.canvasdesk.client;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import br.com.murillo.canvasdesk.client.render.DeskRenderer;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.network.CanvasPayload;
import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class CanvasDeskClient implements ClientModInitializer {
    private final CanvasPayload.Assembler assembler = new CanvasPayload.Assembler();
    @Override public void onInitializeClient() {
        BlockEntityRendererRegistry.register(CanvasDesk.DESK_ENTITY, (BlockEntityRendererProvider.Context ctx) -> new DeskRenderer(ctx));
        ClientPlayNetworking.registerGlobalReceiver(CanvasPayload.TYPE, (packet, context) -> context.client().execute(() -> {
            try {
                String json = assembler.accept(packet); if (json == null) return;
                JsonObject state = JsonParser.parseString(json).getAsJsonObject();
                if (state.has("kind") && state.get("kind").getAsString().equals("tile")) { SurfaceCache.accept(state); return; }
                Minecraft client = context.client();
                if (state.has("open") && state.get("open").getAsBoolean()) client.setScreenAndShow(new CanvasScreen(state, CanvasDeskClient::send));
                else if (client.gui.screen() instanceof CanvasScreen screen) screen.acceptState(state);
            } catch (RuntimeException e) { CanvasDesk.LOGGER.error("Canvas Desk response rejected", e); }
        }));
        ClientTickEvents.END_CLIENT_TICK.register(SurfaceCache::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SurfaceCache.clear());
    }
    public static void send(JsonObject request){for(CanvasPayload part:CanvasPayload.split(request.toString()))ClientPlayNetworking.send(part);}
}
