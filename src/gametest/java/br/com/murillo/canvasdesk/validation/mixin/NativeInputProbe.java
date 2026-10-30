package br.com.murillo.canvasdesk.validation.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** Empty test-only mixin gives the plugin a post-apply hook after Fabric's input guard. */
@Mixin(targets="com.mojang.blaze3d.platform.SDLEventHandler",priority=500)
abstract class NativeInputProbe {}
