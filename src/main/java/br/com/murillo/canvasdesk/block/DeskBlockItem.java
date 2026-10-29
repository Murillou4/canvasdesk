package br.com.murillo.canvasdesk.block;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class DeskBlockItem extends BlockItem {
    private record Dispatch(ServerPlayer player,InteractionHand hand,DeskPlacement.Result expected) {}
    private static final ThreadLocal<Dispatch> DISPATCH=new ThreadLocal<>();
    public DeskBlockItem(Block block,Properties properties){super(block,properties);}
    /** The native interaction guard calls this after Fabric callbacks, before reading/using the clicked block. */
    public static boolean dispatchStillValid(ServerPlayer player,InteractionHand hand) {
        Dispatch dispatch=DISPATCH.get();
        if(dispatch==null)return true;
        if(player!=dispatch.player||hand!=dispatch.hand)return false;
        DeskPlacement.Result checked=DeskPlacement.revalidate(player,hand,dispatch.expected);
        return checked!=null&&checked.valid();
    }
    /** Keep interaction/protection callbacks, with one exact candidate pinned across them. */
    public static InteractionResult dispatch(ServerPlayer player,InteractionHand hand,DeskPlacement.Result result) {
        DeskPlacement.Result checked=DeskPlacement.revalidate(player,hand,result);
        if(checked==null||!checked.valid()||DISPATCH.get()!=null)return InteractionResult.FAIL;
        DISPATCH.set(new Dispatch(player,hand,checked));
        try {
            return player.gameMode.useItemOn(player,player.level(),player.getItemInHand(hand),hand,DeskPlacement.syntheticHit(checked));
        } finally {DISPATCH.remove();}
    }
    @Override public InteractionResult useOn(UseOnContext context){
        Dispatch dispatch=DISPATCH.get();
        if(dispatch!=null) {
            if(context.getPlayer()!=dispatch.player||context.getHand()!=dispatch.hand||context.getItemInHand().getItem()!=this)
                return InteractionResult.FAIL;
            DeskPlacement.Result checked=DeskPlacement.revalidate(dispatch.player,dispatch.hand,dispatch.expected);
            return checked!=null&&checked.valid()?place(checked.context()):InteractionResult.FAIL;
        }
        // Assistance is an explicit client request. A plain vanilla click must keep vanilla placement,
        // even if the server's last look packet happens to point near a different module.
        return super.useOn(context);
    }
    @Override protected BlockState getPlacementState(BlockPlaceContext context){
        if(context instanceof DeskPlacement.AssistedContext assisted)return canPlace(context,assisted.state)?assisted.state:null;
        return super.getPlacementState(context);
    }
}
