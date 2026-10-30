package br.com.murillo.canvasdesk.block;

import net.minecraft.core.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Shared finite front-paper ray check; neither client font metrics nor editor sessions belong here. */
public final class BoardInteraction {
    private BoardInteraction() {}
    public static BlockHitResult pick(Player player,BlockPos pos,Vec3 direction) {
        var level=player.level();
        if(!level.hasChunkAt(pos)||!(level.getBlockState(pos).getBlock() instanceof DeskBlock))return null;
        if(!Double.isFinite(direction.lengthSqr())||direction.lengthSqr()<1e-9)return null;
        var state=level.getBlockState(pos);Direction front=state.getValue(DeskBlock.FACING);
        Direction normal=BoardPlane.horizontal(state)?Direction.UP:front;
        Vec3 eye=player.getEyePosition(),ray=direction.normalize();double range=player.blockInteractionRange();
        if(!Double.isFinite(range)||range<=0||range>128)return null;
        Vec3 center=BoardPlane.surfacePoint(state,.5,.5).add(Vec3.atLowerCornerOf(pos));
        Vec3 n=new Vec3(normal.getStepX(),normal.getStepY(),normal.getStepZ());double dot=ray.dot(n);
        if(dot>=-1e-7)return null;
        double distance=center.subtract(eye).dot(n)/dot;
        if(distance<0||distance>range)return null;
        Vec3 point=eye.add(ray.scale(distance)),uv=coordinates(state,pos,point);
        if(uv.x<DeskGeometry.minU(state)||uv.x>=DeskGeometry.maxU(state)||uv.y<DeskGeometry.minV(state)||uv.y>=DeskGeometry.maxV(state))return null;
        for(var cell:PlacementRay.cells(new PlacementRay.Vector(eye.x,eye.y,eye.z),new PlacementRay.Vector(ray.x,ray.y,ray.z),distance,1024))
            if(!level.hasChunkAt(new BlockPos(cell.x(),cell.y(),cell.z())))return null;
        BlockHitResult obstruction=level.clip(new ClipContext(eye,point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        if(obstruction.getType()==HitResult.Type.BLOCK&&!obstruction.getBlockPos().equals(pos))return null;
        EntityHitResult entity=ProjectileUtil.getEntityHitResult(player,eye,point,new AABB(eye,point).inflate(1),e->!e.isSpectator()&&e.isPickable(),distance*distance);
        if(entity!=null&&entity.getLocation().distanceToSqr(eye)<distance*distance-1e-7)return null;
        return new BlockHitResult(point,normal,pos,false);
    }
    /** x/y are local U/V; z is signed distance from the visible paper plane. */
    public static Vec3 coordinates(net.minecraft.world.level.block.state.BlockState state,BlockPos pos,Vec3 point) {
        Direction front=state.getValue(DeskBlock.FACING),right=BoardPlane.right(front),down=BoardPlane.down(front,BoardPlane.horizontal(state));
        Vec3 delta=point.subtract(BoardPlane.surfacePoint(state,.5,.5).add(Vec3.atLowerCornerOf(pos)));
        return new Vec3(.5+delta.x*right.getStepX()+delta.z*right.getStepZ(),
                .5+delta.x*down.getStepX()+delta.y*down.getStepY()+delta.z*down.getStepZ(),0);
    }
}
