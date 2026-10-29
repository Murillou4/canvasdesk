package br.com.murillo.canvasdesk.block;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A bounded eye-ray query shared by the preview and authoritative placement. */
public final class DeskPlacement {
    private static final int MAX_RAY_CELLS=1024;
    private static final double EPSILON=1.0e-7;
    public record Result(BlockPos anchor,UUID moduleId,BlockPos pos,BlockState state,Direction expansion,
                         boolean valid,BlockPlaceContext context,Vec3 rayPoint,Vec3 normal) {}
    private record Candidate(BlockPos anchor,UUID moduleId,BlockState state,PlacementRay.Hit hit,
                             Vec3 rayPoint,Vec3 normal,Direction expansion) {}
    private DeskPlacement() {}

    public static InteractionHand chosenHand(Player player) {
        if(player.getMainHandItem().getItem() instanceof DeskBlockItem)return InteractionHand.MAIN_HAND;
        return player.getMainHandItem().isEmpty()&&player.getOffhandItem().getItem() instanceof DeskBlockItem
            ?InteractionHand.OFF_HAND:null;
    }
    public static Result resolve(Player player,InteractionHand hand) {return resolve(player,hand,player.getLookAngle());}
    public static Result resolve(Player player,InteractionHand hand,Vec3 direction) {
        if(player.isSecondaryUseActive()||player.isSpectator()||hand!=chosenHand(player))return null;
        ItemStack stack=player.getItemInHand(hand);
        if(!(stack.getItem() instanceof DeskBlockItem item)||!finite(direction)||direction.lengthSqr()<EPSILON)return null;
        double range=player.blockInteractionRange();if(!Double.isFinite(range)||range<=0)return null;
        Vec3 eye=player.getEyePosition(),ray=direction.normalize();var level=player.level();
        Set<BlockPos> visited=new HashSet<>();List<Candidate> candidates=new ArrayList<>();
        int minX=PlacementRay.haloStart(eye.x,ray.x),minY=PlacementRay.haloStart(eye.y,ray.y),minZ=PlacementRay.haloStart(eye.z,ray.z);
        // The halo finds modules beside the ray, even when the target cell is completely empty.
        for(var cell:PlacementRay.cells(vector(eye),vector(ray),range,MAX_RAY_CELLS)) {
            for(int x=minX;x<=1;x++)for(int y=minY;y<=1;y++)for(int z=minZ;z<=1;z++) {
                BlockPos pos=new BlockPos(cell.x()+x,cell.y()+y,cell.z()+z);
                if(!visited.add(pos)||level.isOutsideBuildHeight(pos)||!level.hasChunkAt(pos))continue;
                BlockState anchor=level.getBlockState(pos);
                if(!(anchor.getBlock() instanceof DeskBlock)||BoardPlane.horizontal(anchor)!=((DeskBlock)item.getBlock()).horizontal()
                    ||!(level.getBlockEntity(pos) instanceof DeskBlockEntity desk))continue;
                Candidate candidate=candidate(pos,desk.moduleId,anchor,eye,ray,range);
                if(candidate!=null&&visible(player,candidate))candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.<Candidate,Boolean>comparing(c->!c.hit.face())
            .thenComparingDouble(c->c.hit.distance()).thenComparingDouble(c->c.hit.edgeDistance())
            .thenComparingInt(c->c.anchor.getX()).thenComparingInt(c->c.anchor.getY()).thenComparingInt(c->c.anchor.getZ())
            .thenComparingInt(c->c.hit.edge().ordinal()));
        return candidates.isEmpty()?null:result(player,hand,stack,item,candidates.getFirst());
    }
    /** Compatibility for fixtures; assistance no longer depends on the vanilla block hit. */
    public static Result resolve(Player player,InteractionHand hand,ItemStack stack,BlockHitResult hit) {return resolve(player,hand);}

    /** Recheck the pinned candidate after protection callbacks; never choose a different edge. */
    static Result revalidate(Player player,InteractionHand hand,Result expected) {
        if(expected==null||player.isSecondaryUseActive()||player.isSpectator()||hand!=chosenHand(player))return null;
        ItemStack stack=player.getItemInHand(hand);var level=player.level();
        if(!(stack.getItem() instanceof DeskBlockItem item)||!level.hasChunkAt(expected.anchor)
            ||!(level.getBlockEntity(expected.anchor) instanceof DeskBlockEntity desk)||!desk.moduleId.equals(expected.moduleId))return null;
        BlockState anchor=level.getBlockState(expected.anchor);
        if(!BoardPlane.compatible(anchor,expected.state)||BoardPlane.horizontal(anchor)!=((DeskBlock)item.getBlock()).horizontal()
            ||!expected.pos.equals(expected.anchor.relative(expected.expansion)))return null;
        Vec3 eye=player.getEyePosition(),delta=expected.rayPoint.subtract(eye);
        if(!finite(delta)||delta.lengthSqr()<EPSILON)return null;
        Candidate pinned=candidate(expected.anchor,desk.moduleId,anchor,eye,delta.normalize(),player.blockInteractionRange());
        if(pinned==null||pinned.expansion!=expected.expansion||!visible(player,pinned))return null;
        Result checked=result(player,hand,stack,item,pinned);
        return checked.pos.equals(expected.pos)&&checked.state.equals(expected.state)?checked:null;
    }
    private static Candidate candidate(BlockPos pos,UUID id,BlockState state,Vec3 eye,Vec3 ray,double range) {
        Direction front=state.getValue(DeskBlock.FACING),right=BoardPlane.right(front),down=BoardPlane.down(front,BoardPlane.horizontal(state));
        Direction normal=BoardPlane.horizontal(state)?Direction.UP:front;
        Vec3 center=BoardPlane.surfacePoint(state,.5,.5).add(Vec3.atLowerCornerOf(pos));
        PlacementRay.Hit hit=PlacementRay.intersect(vector(eye),vector(ray),vector(center),vector(normal),vector(right),vector(down),range);
        if(hit==null)return null;
        Direction expansion=switch(hit.edge()) {case UP->down.getOpposite();case RIGHT->right;case DOWN->down;case LEFT->right.getOpposite();};
        return new Candidate(pos.immutable(),id,state,hit,eye.add(ray.scale(hit.distance())),new Vec3(normal.getStepX(),normal.getStepY(),normal.getStepZ()),expansion);
    }
    private static Result result(Player player,InteractionHand hand,ItemStack stack,DeskBlockItem item,Candidate candidate) {
        BlockPos target=candidate.anchor.relative(candidate.expansion);
        BlockState state=item.getBlock().defaultBlockState().setValue(DeskBlock.FACING,candidate.state.getValue(DeskBlock.FACING))
            .setValue(DeskBlock.WALL,candidate.state.getValue(DeskBlock.WALL));
        var properties=stack.get(DataComponents.BLOCK_STATE);
        if(properties!=null&&properties.get(DeskBlock.COLOR)!=null)state=state.setValue(DeskBlock.COLOR,properties.get(DeskBlock.COLOR));
        BlockPlaceContext context=new AssistedContext(player,hand,stack,target,candidate.expansion,state);
        var level=player.level();double range=player.blockInteractionRange();
        boolean valid=Double.isFinite(range)&&range>0&&!player.isSpectator()&&!player.getCooldowns().isOnCooldown(stack)
            &&level.hasChunkAt(target)&&!level.isOutsideBuildHeight(target)&&level.getWorldBorder().isWithinBounds(target)
            &&new AABB(target).distanceToSqr(player.getEyePosition())<=range*range+EPSILON
            &&level.getBlockState(target).canBeReplaced(context)&&state.canSurvive(level,target)
            &&level.isUnobstructed(state,target,CollisionContext.of(player))&&level.mayInteract(player,target)
            &&!player.blockActionRestricted(level,target,player.gameMode())&&player.mayUseItemAt(target,candidate.expansion,stack)
            &&(properties==null||properties.properties().keySet().stream().allMatch("color"::equals));
        return new Result(candidate.anchor,candidate.moduleId,target,state,candidate.expansion,valid,context,candidate.rayPoint,candidate.normal);
    }
    private static boolean visible(Player player,Candidate candidate) {
        Vec3 eye=player.getEyePosition(),delta=candidate.rayPoint.subtract(eye);if(delta.lengthSqr()<EPSILON)return false;
        // Never let clip() request an unloaded chunk at the end of the eye ray.
        for(var cell:PlacementRay.cells(vector(eye),vector(delta.normalize()),delta.length(),MAX_RAY_CELLS))
            if(!player.level().hasChunkAt(new BlockPos(cell.x(),cell.y(),cell.z())))return false;
        EntityHitResult entity=ProjectileUtil.getEntityHitResult(player,eye,candidate.rayPoint,new AABB(eye,candidate.rayPoint).inflate(1),
            e->!e.isSpectator()&&e.isPickable(),delta.lengthSqr());
        if(entity!=null&&entity.getLocation().distanceToSqr(eye)+EPSILON<candidate.rayPoint.distanceToSqr(eye))return false;
        BlockHitResult obstruction=player.level().clip(new ClipContext(eye,candidate.rayPoint,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        if(obstruction.getType()==HitResult.Type.MISS)return true;
        // An occupied target remains a red preview; unrelated foreground blocks hide assistance.
        return obstruction.getBlockPos().equals(candidate.anchor)||obstruction.getBlockPos().equals(candidate.anchor.relative(candidate.expansion))
            ||obstruction.getLocation().distanceToSqr(eye)+EPSILON>=candidate.rayPoint.distanceToSqr(eye);
    }
    static BlockHitResult syntheticHit(Result result) {
        BlockState anchor=result.context.getLevel().getBlockState(result.anchor);
        Direction right=BoardPlane.right(anchor.getValue(DeskBlock.FACING)),down=BoardPlane.down(anchor.getValue(DeskBlock.FACING),BoardPlane.horizontal(anchor));
        Vec3 center=BoardPlane.surfacePoint(anchor,.5,.5).add(Vec3.atLowerCornerOf(result.anchor));Vec3 relative=result.rayPoint.subtract(center);
        double u=Math.clamp(.5+relative.dot(new Vec3(right.getStepX(),right.getStepY(),right.getStepZ())),0,1);
        double v=Math.clamp(.5+relative.dot(new Vec3(down.getStepX(),down.getStepY(),down.getStepZ())),0,1);
        if(result.expansion==right)u=1;else if(result.expansion==right.getOpposite())u=0;
        else if(result.expansion==down)v=1;else v=0;
        return new BlockHitResult(BoardPlane.surfacePoint(anchor,u,v).add(Vec3.atLowerCornerOf(result.anchor)),result.expansion,result.anchor,false);
    }
    private static boolean finite(Vec3 v){return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private static PlacementRay.Vector vector(Vec3 v){return new PlacementRay.Vector(v.x,v.y,v.z);}
    private static PlacementRay.Vector vector(Direction d){return new PlacementRay.Vector(d.getStepX(),d.getStepY(),d.getStepZ());}

    static final class AssistedContext extends BlockPlaceContext {
        final BlockPos target;final BlockState state;
        AssistedContext(Player player,InteractionHand hand,ItemStack stack,BlockPos target,Direction side,BlockState state) {
            super(player.level(),player,hand,stack,new BlockHitResult(Vec3.atCenterOf(target),side,target,false));this.target=target;this.state=state;
        }
        @Override public BlockPos getClickedPos(){return target==null?super.getClickedPos():target;}
        @Override public boolean canPlace(){return getLevel().hasChunkAt(getClickedPos())&&getLevel().getBlockState(getClickedPos()).canBeReplaced(this);}
    }
}
