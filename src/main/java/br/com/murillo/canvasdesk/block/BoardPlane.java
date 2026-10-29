package br.com.murillo.canvasdesk.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** One coordinate basis for topology, document projection and front-face rendering. */
public final class BoardPlane {
    private BoardPlane() {}
    public static Direction right(Direction front) { return front.getCounterClockWise(); }
    public static Direction[] sides(Direction front) {
        return new Direction[]{right(front), right(front).getOpposite(), Direction.UP, Direction.DOWN};
    }
    public static double horizontal(Direction front, BlockPos from, BlockPos to) {
        Direction right = right(front);
        return (to.getX() - from.getX()) * right.getStepX() + (to.getZ() - from.getZ()) * right.getStepZ();
    }
    public static double vertical(BlockPos from, BlockPos to) { return from.getY() - to.getY(); }
    public static boolean horizontal(BlockState state) { return state.getBlock() instanceof DeskBlock desk && desk.horizontal(); }
    public static Direction down(Direction front, boolean horizontal) { return horizontal ? front : Direction.DOWN; }
    public static Direction[] sides(Direction front, boolean horizontal) {
        return new Direction[]{right(front), right(front).getOpposite(), down(front,horizontal).getOpposite(), down(front,horizontal)};
    }
    public static double vertical(Direction front, boolean horizontal, BlockPos from, BlockPos to) {
        Direction down=down(front,horizontal);
        return (to.getX()-from.getX())*down.getStepX()+(to.getY()-from.getY())*down.getStepY()+(to.getZ()-from.getZ())*down.getStepZ();
    }
    public static boolean compatible(BlockState a, BlockState b) {
        return a.getBlock() instanceof DeskBlock && b.getBlock() instanceof DeskBlock && horizontal(a)==horizontal(b)
            && a.getValue(DeskBlock.FACING)==b.getValue(DeskBlock.FACING) && a.getValue(DeskBlock.WALL)==b.getValue(DeskBlock.WALL);
    }
    /** Canonical model coordinates: x goes left, y goes up, z goes behind the paper. */
    public static Vec3 modelPoint(BlockState state,double x,double y,double z) {
        Direction front=state.getValue(DeskBlock.FACING),right=right(front);
        double u=x/16-.5;
        if(horizontal(state)) return new Vec3(.5-right.getStepX()*u+front.getStepX()*(.5-y/16),(9.5-z)/16,.5-right.getStepZ()*u+front.getStepZ()*(.5-y/16));
        double depth=.5-(z+(state.getValue(DeskBlock.WALL)?6.5:0))/16;
        return new Vec3(.5-right.getStepX()*u+front.getStepX()*depth,y/16,.5-right.getStepZ()*u+front.getStepZ()*depth);
    }
    public static Vec3 surfacePoint(BlockState state,double u,double v) {
        Vec3 p=modelPoint(state,16*(1-u),16*(1-v),7);
        Direction normal=horizontal(state)?Direction.UP:state.getValue(DeskBlock.FACING);
        return p.add(normal.getStepX()*.002,normal.getStepY()*.002,normal.getStepZ()*.002);
    }
}
