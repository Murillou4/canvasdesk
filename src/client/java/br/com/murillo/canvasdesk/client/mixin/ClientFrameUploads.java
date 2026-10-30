package br.com.murillo.canvasdesk.client.mixin;

import br.com.murillo.canvasdesk.client.media.BrowserTexture;
import br.com.murillo.canvasdesk.client.render.ItemIconAtlas;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RenderPearl forbids uploads and nested atlas passes while a world render pass is open. */
@Mixin(GameRenderer.class)
abstract class ClientFrameUploads {
    @Inject(method="render",at=@At("HEAD"))
    private void canvasdesk$uploadBeforePasses(CallbackInfo ci){BrowserTexture.uploadFrames();ItemIconAtlas.renderPending();}
}
