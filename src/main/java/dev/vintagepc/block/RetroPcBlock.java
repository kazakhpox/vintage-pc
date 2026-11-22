package dev.vintagepc.block;

import dev.vintagepc.VintagePC;
import dev.vintagepc.item.PcPart;
import dev.vintagepc.item.PcPartItem;
import dev.vintagepc.net.NetGraph;
import dev.vintagepc.net.PcSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Корпус ретро-ПК со встроенным монитором. Сначала вставьте материнскую плату, затем процессор,
 * ОЗУ, видеокарту, жёсткий диск и блок питания (Wi-Fi-карта — по желанию). Когда всё на месте,
 * компьютер собран и включается правым кликом. Shift + ПКМ пустой рукой — вынуть последний компонент.
 */
public class RetroPcBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");

    public RetroPcBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ASSEMBLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, ASSEMBLED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof PcPartItem item)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof RetroPcBlockEntity be)) return ItemInteractionResult.FAIL;
        PcPart part = item.part;
        if (be.has(part)) { msg(player, Component.translatable("vintagepc.part_already", Component.translatable(part.langKey()))); return ItemInteractionResult.FAIL; }
        if (part != PcPart.MOTHERBOARD && !be.has(PcPart.MOTHERBOARD)) { msg(player, Component.translatable("vintagepc.part_need_mb")); return ItemInteractionResult.FAIL; }
        be.add(part);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.6f, 1.6f);
        afterChange(level, pos, state, be, player);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof RetroPcBlockEntity be)) return InteractionResult.FAIL;
        if (player.isShiftKeyDown()) {                                     // вынуть последний компонент
            PcPart p = be.removeLast();
            if (p == null) { msg(player, Component.translatable("vintagepc.case_empty")); return InteractionResult.SUCCESS; }
            ItemStack drop = new ItemStack(VintagePC.PART_ITEMS.get(p).get());
            if (!player.getInventory().add(drop)) player.drop(drop, false);
            level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 0.6f, 1.4f);
            afterChange(level, pos, state, be, player);
            return InteractionResult.SUCCESS;
        }
        if (!be.complete()) { showMissing(player, be); return InteractionResult.SUCCESS; }
        if (screenBlocked(level, pos, state)) { msg(player, Component.translatable("vintagepc.screen_blocked")); return InteractionResult.SUCCESS; }
        PcSessions.use(sp, pos);                                          // хост или зритель — решает сервер
        return InteractionResult.SUCCESS;
    }

    /**
     * Камера подлетает к экрану на ~0,6 блока — если перед монитором стоит что-то твёрдое,
     * она оказалась бы внутри блока. Плоский кабель, цветы, факелы и т.п. (без коллизии) не мешают.
     */
    public static boolean screenBlocked(Level level, BlockPos pos, BlockState state) {
        BlockPos front = pos.relative(state.getValue(FACING));
        BlockState fs = level.getBlockState(front);
        if (fs.getBlock() instanceof NetworkCableBlock) return false;
        return !fs.getCollisionShape(level, front).isEmpty();
    }

    private static void afterChange(Level level, BlockPos pos, BlockState state, RetroPcBlockEntity be, Player player) {
        boolean done = be.complete();
        if (state.getValue(ASSEMBLED) != done) {
            level.setBlock(pos, state.setValue(ASSEMBLED, done), Block.UPDATE_ALL);
            if (done) {
                level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.5f, 1.8f);
                msg(player, Component.translatable("vintagepc.pc_assembled"));
            } else {
                PcSessions.removed(level, pos);                            // разобрали работающий ПК — он выключается
            }
        }
        if (!done) showMissing(player, be);
        PcSessions.hardwareChanged(level, pos);
    }

    private static void showMissing(Player player, RetroPcBlockEntity be) {
        MutableComponent list = Component.empty();
        boolean first = true;
        for (PcPart p : be.missing()) {
            if (!first) list.append(", ");
            list.append(Component.translatable(p.langKey()));
            first = false;
        }
        msg(player, Component.translatable("vintagepc.pc_missing", list));
    }

    private static void msg(Player p, Component c) { p.displayClientMessage(c, true); }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        if (!level.isClientSide && !old.is(this)) NetGraph.get(level).put(pos, NetGraph.PC);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            PcSessions.removed(level, pos);
            NetGraph.get(level).remove(pos);
            if (level.getBlockEntity(pos) instanceof RetroPcBlockEntity be)
                for (PcPart p : be.parts())
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), new ItemStack(VintagePC.PART_ITEMS.get(p).get()));
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RetroPcBlockEntity(pos, state);
    }
}
