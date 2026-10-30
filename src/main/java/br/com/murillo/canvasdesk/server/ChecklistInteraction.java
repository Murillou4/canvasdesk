package br.com.murillo.canvasdesk.server;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/** Pin one physical canvas action through vanilla/Fabric callbacks (checkbox or media button). */
public final class ChecklistInteraction {
    private record Dispatch(ServerPlayer player,BlockPos pos,BooleanSupplier valid,Runnable commit,java.util.concurrent.atomic.AtomicBoolean consumed) {}
    private static final ThreadLocal<Dispatch> CURRENT=new ThreadLocal<>();
    private ChecklistInteraction() {}
    public static void dispatch(ServerPlayer player,BlockHitResult hit,BooleanSupplier valid,Runnable commit) {
        if(CURRENT.get()!=null||!valid.getAsBoolean())return;
        CURRENT.set(new Dispatch(player,hit.getBlockPos(),valid,commit,new java.util.concurrent.atomic.AtomicBoolean()));
        try{player.gameMode.useItemOn(player,player.level(),player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);}
        finally{CURRENT.remove();}
    }
    public static boolean valid(ServerPlayer player,InteractionHand hand) {
        Dispatch d=CURRENT.get();return d==null||d.player==player&&hand==InteractionHand.MAIN_HAND&&d.valid.getAsBoolean();
    }
    public static boolean consume(ServerPlayer player,BlockPos pos) {
        Dispatch d=CURRENT.get();if(d==null)return false;
        if(d.player==player&&d.pos.equals(pos)&&d.valid.getAsBoolean()&&d.consumed.compareAndSet(false,true))d.commit.run();
        return true;
    }
}
