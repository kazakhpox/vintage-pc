package dev.vintagepc.block;

import dev.vintagepc.net.NetGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Основание вышки Wi-Fi (многоблочная структура):
 *
 *      [антенна]
 *      [мачта] ×{@value #MIN_MAST}..{@value #MAX_MAST}
 *      [основание]   ← можно подключить кабелем к роутеру: беспроводные ПК попадут в ту же LAN
 *
 * Собранная вышка создаёт сеть, к которой ПК с Wi-Fi-картой подключаются из любой точки измерения.
 */
public class WifiTowerBaseBlock extends Block implements EntityBlock {
    public static final int MIN_MAST = 4, MAX_MAST = 24;
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    public WifiTowerBaseBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FORMED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FORMED); }

    /** Проверяет структуру над основанием; обновляет блок-стейт, граф сети и мачты. */
    public static void revalidate(Level level, BlockPos base) {
        if (level.isClientSide) return;
        BlockState bs = level.getBlockState(base);
        if (!(bs.getBlock() instanceof WifiTowerBaseBlock)) return;
        int mast = 0;
        BlockPos p = base.above();
        while (mast < MAX_MAST && level.getBlockState(p).getBlock() instanceof WifiTowerPartBlock part && !part.antenna) { mast++; p = p.above(); }
        boolean formed = mast >= MIN_MAST && level.getBlockState(p).getBlock() instanceof WifiTowerPartBlock top && top.antenna;
        if (bs.getValue(FORMED) != formed) level.setBlock(base, bs.setValue(FORMED, formed), Block.UPDATE_CLIENTS);
        NetGraph.get(level).setFormed(base, formed);
    }

    /** Найти основание под частью вышки. */
    public static BlockPos findBase(Level level, BlockPos from) {
        BlockPos p = from.below();
        for (int i = 0; i <= MAX_MAST + 1; i++, p = p.below()) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof WifiTowerBaseBlock) return p;
            if (!(s.getBlock() instanceof WifiTowerPartBlock)) return null;
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null && !level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof WifiTowerBlockEntity be) be.setName(name.getString());
            NetGraph.get(level).setName(pos, name.getString());
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!level.isClientSide && !old.is(this)) { NetGraph.get(level).put(pos, NetGraph.TOWER); revalidate(level, pos); }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!level.isClientSide && !newState.is(this)) NetGraph.get(level).remove(pos);
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) {
            revalidate(level, pos);
            boolean formed = level.getBlockState(pos).getValue(FORMED);
            String name = NetGraph.get(level).name(pos);
            sp.displayClientMessage(formed
                    ? Component.translatable("vintagepc.tower_ok", name.isEmpty() ? Component.translatable("vintagepc.tower_unnamed") : Component.literal(name))
                    : Component.translatable("vintagepc.tower_incomplete", MIN_MAST), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new WifiTowerBlockEntity(pos, state); }
}
