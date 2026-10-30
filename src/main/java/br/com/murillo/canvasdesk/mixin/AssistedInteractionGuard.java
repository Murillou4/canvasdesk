package br.com.murillo.canvasdesk.mixin;

import br.com.murillo.canvasdesk.block.DeskBlockItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Protection callbacks run at HEAD. A changed anchor must not open/interact with its replacement. */
@Mixin(value=ServerPlayerGameMode.class,remap=false)
public abstract class AssistedInteractionGuard {
    @Inject(method="useItemOn",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",ordinal=0),cancellable=true)
    private void canvasdesk$validatePinnedPlacement(ServerPlayer player,Level level,ItemStack stack,
                                                   InteractionHand hand,BlockHitResult hit,
                                                   CallbackInfoReturnable<InteractionResult> result){
        if(!DeskBlockItem.dispatchStillValid(player,hand)||!br.com.murillo.canvasdesk.server.ChecklistInteraction.valid(player,hand))result.setReturnValue(InteractionResult.FAIL);
    }
}
