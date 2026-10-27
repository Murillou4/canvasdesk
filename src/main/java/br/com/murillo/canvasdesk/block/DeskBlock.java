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
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class DeskBlock extends BaseEntityBlock {
    public static final BooleanProperty NORTH=BooleanProperty.create("north"), SOUTH=BooleanProperty.create("south"),
            EAST=BooleanProperty.create("east"), WEST=BooleanProperty.create("west");
    private static final VoxelShape SHAPE = Shapes.or(Block.box(0,14,0,16,16,16),
            Block.box(1,0,1,3,14,3), Block.box(13,0,1,15,14,3), Block.box(1,0,13,3,14,15), Block.box(13,0,13,15,14,15));
    public DeskBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NORTH,false).setValue(SOUTH,false).setValue(EAST,false).setValue(WEST,false));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(NORTH,SOUTH,EAST,WEST); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DeskBlockEntity(pos,state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState(); }
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
    @Override protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        return state;
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state,level,pos,moved);
        DeskServer.get(level.getServer()).removed(level,pos);
    }
}
