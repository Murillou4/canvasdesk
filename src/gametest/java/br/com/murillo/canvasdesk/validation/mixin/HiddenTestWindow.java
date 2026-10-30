package br.com.murillo.canvasdesk.validation.mixin;

import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Test-only: render to a genuine GPU window without showing it or taking desktop focus. */
@Mixin(com.mojang.blaze3d.platform.Window.class)
abstract class HiddenTestWindow {
    @ModifyArg(method="createWindow",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"),index=3)
    private long canvasdesk$hidden(long flags){return flags|SDLVideo.SDL_WINDOW_HIDDEN|SDLVideo.SDL_WINDOW_NOT_FOCUSABLE;}
}
