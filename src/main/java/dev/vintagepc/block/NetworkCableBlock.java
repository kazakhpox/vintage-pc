package dev.vintagepc.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Сетевой кабель (витая пара). Сам соединяется с соседними кабелями, компьютерами и роутерами.
 * Без роутера сигнал проходит не больше {@link #MAX_LENGTH} блоков — как Ethernet на 100 м.
 */
public class NetworkCableBlock extends Block {
    public static final int MAX_LENGTH = 100;
    public static final Map<Direction, BooleanProperty> PROPS = new EnumMap<>(Map.of(
            Direction.NORTH, BlockStateProperties.NORTH, Direction.SOUTH, BlockStateProperties.SOUTH,
            Direction.EAST, BlockStateProperties.EAST, Direction.WEST, BlockStateProperties.WEST,
            Direction.UP, BlockStateProperties.UP, Direction.DOWN, BlockStateProperties.DOWN));

    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(6, 6, 0, 10, 10, 6), Direction.SOUTH, Block.box(6, 6, 10, 10, 10, 16),
            Direction.WEST, Block.box(0, 6, 6, 6, 10, 10), Direction.EAST, Block.box(10, 6, 6, 16, 10, 10),
            Direction.DOWN, Block.box(6, 0, 6, 10, 6, 10), Direction.UP, Block.box(6, 10, 6, 10, 16, 10)));
    private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

    public NetworkCableBlock(Properties props) {
        super(props);
        BlockState s = stateDefinition.any();
        for (BooleanProperty p : PROPS.values()) s = s.setValue(p, false);
        registerDefaultState(s);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        PROPS.values().forEach(b::add);
    }

    /** С чем соединяется кабель. */
    public static boolean isNetworkBlock(BlockState s) {
        return s.getBlock() instanceof NetworkCableBlock || s.getBlock() instanceof RetroPcBlock || s.getBlock() instanceof RouterBlock;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s = defaultBlockState();
        for (Direction d : Direction.values())
            s = s.setValue(PROPS.get(d), isNetworkBlock(ctx.getLevel().getBlockState(ctx.getClickedPos().relative(d))));
        return s;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(PROPS.get(dir), isNetworkBlock(neighbor));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapes.computeIfAbsent(state, s -> {
            VoxelShape v = CORE;
            for (Direction d : Direction.values()) if (s.getValue(PROPS.get(d))) v = Shapes.or(v, ARMS.get(d));
            return v;
        });
    }
}
