package br.com.murillo.canvasdesk.client;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import br.com.murillo.canvasdesk.client.render.DeskRenderer;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.client.render.PlacementPreview;
import br.com.murillo.canvasdesk.network.CanvasPayload;
import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import br.com.murillo.canvasdesk.client.editor.CanvasTextLayout;

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
                if (state.has("kind") && state.get("kind").getAsString().equals("assist")) {
                    AssistedPlacementInput.acceptReply(client, state); return;
                }
                if(state.has("closed")){if(client.gui.screen() instanceof CanvasScreen screen)screen.serverClosed(state.get("message").getAsString());return;}
                if (state.has("open") && state.get("open").getAsBoolean()) client.setScreenAndShow(new CanvasScreen(state, CanvasDeskClient::send));
                else if (client.gui.screen() instanceof CanvasScreen screen) screen.acceptState(state);
            } catch (RuntimeException e) { CanvasDesk.LOGGER.error("Canvas Desk response rejected", e); }
        }));
        AssistedPlacementInput.register();
        PlacementPreview.register();
        Minecraft resourceClient=Minecraft.getInstance();
        if(resourceClient.getResourceManager() instanceof ReloadableResourceManager resources)resources.registerReloadListener((ResourceManagerReloadListener)manager->{
            resourceClient.execute(()->{SurfaceCache.clear();CanvasTextLayout.clear();});
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->{SurfaceCache.tick(client);PlacementPreview.tick(client);});
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            SurfaceCache.clear(); PlacementPreview.clear(); AssistedPlacementInput.clear();
        }));
    }
    public static void send(JsonObject request){for(CanvasPayload part:CanvasPayload.split(request.toString()))ClientPlayNetworking.send(part);}
}
