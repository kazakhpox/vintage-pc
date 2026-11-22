package dev.vintagepc;

import dev.vintagepc.block.RetroPcBlock;
import dev.vintagepc.block.RetroPcBlockEntity;
import dev.vintagepc.block.NetworkCableBlock;
import dev.vintagepc.block.RouterBlock;
import dev.vintagepc.block.RouterBlockEntity;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.function.Supplier;
import dev.vintagepc.net.LanHub;
import dev.vintagepc.net.LanPayloads;
import dev.vintagepc.net.PcSessions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(VintagePC.MOD_ID)
public final class VintagePC {
    public static final String MOD_ID = "vintagepc";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    public static final DeferredBlock<RetroPcBlock> RETRO_PC = BLOCKS.registerBlock("retro_pc", RetroPcBlock::new,
            BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion());
    public static final DeferredItem<BlockItem> RETRO_PC_ITEM = ITEMS.registerSimpleBlockItem(RETRO_PC);

    public static final DeferredBlock<NetworkCableBlock> NETWORK_CABLE = BLOCKS.registerBlock("network_cable", NetworkCableBlock::new,
            BlockBehaviour.Properties.of().strength(0.2f).sound(SoundType.WOOL).noOcclusion().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> NETWORK_CABLE_ITEM = ITEMS.registerSimpleBlockItem(NETWORK_CABLE);
    public static final DeferredBlock<RouterBlock> ROUTER = BLOCKS.registerBlock("router", RouterBlock::new,
            BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> s.getValue(RouterBlock.ACTIVE) ? 4 : 1));
    public static final DeferredItem<BlockItem> ROUTER_ITEM = ITEMS.registerSimpleBlockItem(ROUTER);

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    @SuppressWarnings("DataFlowIssue")
    public static final Supplier<BlockEntityType<RetroPcBlockEntity>> RETRO_PC_BE = BLOCK_ENTITIES.register("retro_pc",
            () -> BlockEntityType.Builder.of(RetroPcBlockEntity::new, RETRO_PC.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final Supplier<BlockEntityType<RouterBlockEntity>> ROUTER_BE = BLOCK_ENTITIES.register("router",
            () -> BlockEntityType.Builder.of(RouterBlockEntity::new, ROUTER.get()).build(null));

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public VintagePC(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(LanPayloads::register);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                e.accept(RETRO_PC_ITEM);
                e.accept(ROUTER_ITEM);
                e.accept(NETWORK_CABLE_ITEM);
            }
        });
        LanHub.init(NeoForge.EVENT_BUS);
        PcSessions.init(NeoForge.EVENT_BUS);
    }
}
