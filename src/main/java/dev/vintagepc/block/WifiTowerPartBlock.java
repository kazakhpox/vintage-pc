package dev.vintagepc.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Секция мачты или антенна вышки Wi-Fi. При изменении — перепроверка структуры. */
public class WifiTowerPartBlock extends Block {
    final boolean antenna;
    private static final VoxelShape MAST = Block.box(3, 0, 3, 13, 16, 13), ANTENNA = Block.box(5, 0, 5, 11, 16, 11);

    public WifiTowerPartBlock(Properties props, boolean antenna) {
        super(props);
        this.antenna = antenna;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return antenna ? ANTENNA : MAST;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!level.isClientSide && !old.is(this)) touch(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        super.onRemove(state, level, pos, newState, moving);
        if (!level.isClientSide && !newState.is(this)) touch(level, pos);
    }

    private static void touch(Level level, BlockPos pos) {
        BlockPos base = WifiTowerBaseBlock.findBase(level, pos);
        if (base != null) WifiTowerBaseBlock.revalidate(level, base);
    }
}
