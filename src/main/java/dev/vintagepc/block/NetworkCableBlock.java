package dev.vintagepc.block;

import dev.vintagepc.net.NetGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Сетевой кабель (витая пара) — прокладывается по полу, как редстоун: тянется к соседним кабелям,
 * компьютерам, роутерам и вышкам, поднимается на блок и спускается с него.
 * Без роутера сигнал проходит не больше {@link #MAX_LENGTH} блоков — как Ethernet на 100 м.
 */
public class NetworkCableBlock extends Block {
    public static final int MAX_LENGTH = 100;
    public static final Map<Direction, EnumProperty<RedstoneSide>> PROPS = new EnumMap<>(Map.of(
            Direction.NORTH, BlockStateProperties.NORTH_REDSTONE, Direction.SOUTH, BlockStateProperties.SOUTH_REDSTONE,
            Direction.EAST, BlockStateProperties.EAST_REDSTONE, Direction.WEST, BlockStateProperties.WEST_REDSTONE));
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1, 16);

    public NetworkCableBlock(Properties props) {
        super(props);
        BlockState s = stateDefinition.any();
        for (var p : PROPS.values()) s = s.setValue(p, RedstoneSide.NONE);
        registerDefaultState(s);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        PROPS.values().forEach(b::add);
    }

    /** Устройства, к которым кабель подходит сбоку. */
    public static boolean isDevice(BlockState s) {
        return s.getBlock() instanceof RetroPcBlock || s.getBlock() instanceof RouterBlock || s.getBlock() instanceof WifiTowerBaseBlock;
    }

    private static boolean isCable(BlockState s) { return s.getBlock() instanceof NetworkCableBlock; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return connections(ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir == Direction.DOWN && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return connections(level, pos);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        // кабели на соседних высотах не являются прямыми соседями — пересчитываем при любом изменении рядом
        BlockState now = connections(level, pos);
        if (now != state) level.setBlock(pos, now, Block.UPDATE_CLIENTS);
    }

    /** Состояние связей — по правилам редстоун-пыли. */
    BlockState connections(BlockGetter level, BlockPos pos) {
        BlockState s = defaultBlockState();
        BlockState above = level.getBlockState(pos.above());
        boolean coveredAbove = above.isRedstoneConductor(level, pos.above());
        int count = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            BlockState ns = level.getBlockState(n);
            RedstoneSide side = RedstoneSide.NONE;
            if (isCable(ns) || isDevice(ns)) side = RedstoneSide.SIDE;
            else if (!coveredAbove && ns.isFaceSturdy(level, n, d.getOpposite()) && isCable(level.getBlockState(n.above()))) side = RedstoneSide.UP;
            else if (!ns.isRedstoneConductor(level, n) && isCable(level.getBlockState(n.below()))) side = RedstoneSide.SIDE;
            if (side != RedstoneSide.NONE) count++;
            s = s.setValue(PROPS.get(d), side);
        }
        if (count == 1) { // одна связь — тянем прямую линию насквозь, как у редстоуна
            for (Direction d : Direction.Plane.HORIZONTAL)
                if (s.getValue(PROPS.get(d)) != RedstoneSide.NONE && s.getValue(PROPS.get(d.getOpposite())) == RedstoneSide.NONE)
                    s = s.setValue(PROPS.get(d.getOpposite()), RedstoneSide.SIDE);
        }
        return s;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!level.isClientSide && !old.is(this)) NetGraph.get(level).put(pos, NetGraph.CABLE);
        if (!old.is(this)) updateDiagonals(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!level.isClientSide && !newState.is(this)) NetGraph.get(level).remove(pos);
        super.onRemove(state, level, pos, newState, moving);
        if (!newState.is(this)) updateDiagonals(level, pos);
    }

    /** Кабели на блок выше/ниже по диагонали — обновить их связи (подъём/спуск). */
    private void updateDiagonals(Level level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL)
            for (BlockPos n : new BlockPos[]{pos.relative(d).above(), pos.relative(d).below()}) {
                BlockState ns = level.getBlockState(n);
                if (ns.getBlock() instanceof NetworkCableBlock c) {
                    BlockState now = c.connections(level, n);
                    if (now != ns) level.setBlock(n, now, Block.UPDATE_CLIENTS);
                }
            }
    }
}
