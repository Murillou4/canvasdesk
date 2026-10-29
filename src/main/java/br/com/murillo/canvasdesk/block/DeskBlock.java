package br.com.murillo.canvasdesk.block;

import br.com.murillo.canvasdesk.server.DeskServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class DeskBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LEFT=BooleanProperty.create("left"), RIGHT=BooleanProperty.create("right"),
            UP=BooleanProperty.create("up"), DOWN=BooleanProperty.create("down"), BASE=BooleanProperty.create("base");
    public static final BooleanProperty WALL=BooleanProperty.create("wall_mounted");
    public static final EnumProperty<DyeColor> COLOR=EnumProperty.create("color",DyeColor.class);
    private final boolean horizontal;
    public DeskBlock(Properties properties) { this(properties,false); }
    public DeskBlock(Properties properties,boolean horizontal) {
        super(properties);
        this.horizontal=horizontal;
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(LEFT,false).setValue(RIGHT,false)
                .setValue(UP,false).setValue(DOWN,false).setValue(BASE,true).setValue(WALL,false).setValue(COLOR,DyeColor.WHITE));
    }
    public boolean horizontal(){return horizontal;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING,LEFT,RIGHT,UP,DOWN,BASE,WALL,COLOR); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DeskBlockEntity(pos,state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DeskGeometry.shape(state);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return DeskGeometry.shape(state);}
    @Override protected ItemStack getCloneItemStack(LevelReader level,BlockPos pos,BlockState state,boolean includeData){
        ItemStack stack=super.getCloneItemStack(level,pos,state,includeData);
        stack.set(DataComponents.BLOCK_STATE,new BlockItemStateProperties(java.util.Map.of("color",state.getValue(COLOR).getSerializedName())));return stack;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        boolean wall=!horizontal&&context.getClickedFace().getAxis().isHorizontal();
        if(wall)facing=context.getClickedFace();
        BlockPos clicked = context.getClickedPos().relative(context.getClickedFace().getOpposite());
        BlockState neighbor = context.getLevel().getBlockState(clicked);
        if (neighbor.is(this)) {
            Direction candidate = neighbor.getValue(FACING);
            for (Direction side : BoardPlane.sides(candidate,horizontal)) if (clicked.relative(side).equals(context.getClickedPos())) {facing = candidate;wall=neighbor.getValue(WALL);}
        }
        return defaultBlockState().setValue(FACING,facing).setValue(WALL,wall);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level,pos,state,placer,stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof DeskBlockEntity desk) DeskServer.get(serverLevel.getServer()).placed(serverLevel,desk,player,stack);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DeskBlockEntity desk)
            DeskServer.get(serverPlayer.level().getServer()).open(serverPlayer, desk);
        return InteractionResult.SUCCESS;
    }
    @Override protected InteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit) {
        if(stack.getItem() instanceof DeskBlockItem item && item.getBlock() instanceof DeskBlock held && held.horizontal()==horizontal) {
            return item.useOn(new net.minecraft.world.item.context.UseOnContext(player,hand,hit));
        }
        DyeColor dye=stack.get(DataComponents.DYE);
        if(dye!=null) {
            if(player instanceof ServerPlayer serverPlayer)DeskServer.get(serverPlayer.level().getServer()).recolor(serverPlayer,pos,dye,stack);
            return InteractionResult.SUCCESS;
        }
        // Vanilla invokes useWithoutItem only through this result, even for an empty hand.
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
    @Override protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        return state;
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state,level,pos,moved);
        DeskServer.get(level.getServer()).removed(level,pos);
    }
}
