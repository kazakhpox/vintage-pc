package dev.vintagepc;

import dev.vintagepc.block.RetroPcBlock;
import dev.vintagepc.block.RetroPcBlockEntity;
import dev.vintagepc.block.NetworkCableBlock;
import dev.vintagepc.block.RouterBlock;
import dev.vintagepc.block.RouterBlockEntity;
import dev.vintagepc.block.WifiTowerBaseBlock;
import dev.vintagepc.block.WifiTowerBlockEntity;
import dev.vintagepc.block.WifiTowerPartBlock;
import dev.vintagepc.item.PcPart;
import dev.vintagepc.item.PcPartItem;
import net.minecraft.world.item.Item;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
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

    public static final DeferredBlock<WifiTowerBaseBlock> WIFI_TOWER_BASE = BLOCKS.registerBlock("wifi_tower_base", WifiTowerBaseBlock::new,
            BlockBehaviour.Properties.of().strength(3f).sound(SoundType.METAL)
                    .lightLevel(s -> s.getValue(WifiTowerBaseBlock.FORMED) ? 6 : 0));
    public static final DeferredItem<BlockItem> WIFI_TOWER_BASE_ITEM = ITEMS.registerSimpleBlockItem(WIFI_TOWER_BASE);
    public static final DeferredBlock<WifiTowerPartBlock> WIFI_MAST = BLOCKS.registerBlock("wifi_mast", p -> new WifiTowerPartBlock(p, false),
            BlockBehaviour.Properties.of().strength(2f).sound(SoundType.METAL).noOcclusion());
    public static final DeferredItem<BlockItem> WIFI_MAST_ITEM = ITEMS.registerSimpleBlockItem(WIFI_MAST);
    public static final DeferredBlock<WifiTowerPartBlock> WIFI_ANTENNA = BLOCKS.registerBlock("wifi_antenna", p -> new WifiTowerPartBlock(p, true),
            BlockBehaviour.Properties.of().strength(2f).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 7));
    public static final DeferredItem<BlockItem> WIFI_ANTENNA_ITEM = ITEMS.registerSimpleBlockItem(WIFI_ANTENNA);

    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);
    /** Включение ПК: писк POST, вентилятор, щелчки диска и стартовый аккорд (синтезирован для мода). */
    public static final DeferredHolder<SoundEvent, SoundEvent> PC_BOOT = SOUNDS.register("pc_boot", () -> SoundEvent.createVariableRangeEvent(id("pc_boot")));

    /** Комплектующие ПК. */
    public static final java.util.Map<PcPart, DeferredItem<PcPartItem>> PART_ITEMS = new java.util.EnumMap<>(PcPart.class);
    static {
        for (PcPart part : PcPart.values())
            PART_ITEMS.put(part, ITEMS.registerItem(part.id, p -> new PcPartItem(part, p), new Item.Properties().stacksTo(16)));
    }

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    @SuppressWarnings("DataFlowIssue")
    public static final Supplier<BlockEntityType<RetroPcBlockEntity>> RETRO_PC_BE = BLOCK_ENTITIES.register("retro_pc",
            () -> BlockEntityType.Builder.of(RetroPcBlockEntity::new, RETRO_PC.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final Supplier<BlockEntityType<WifiTowerBlockEntity>> WIFI_TOWER_BE = BLOCK_ENTITIES.register("wifi_tower",
            () -> BlockEntityType.Builder.of(WifiTowerBlockEntity::new, WIFI_TOWER_BASE.get()).build(null));
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
        SOUNDS.register(modBus);
        modBus.addListener(LanPayloads::register);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                e.accept(RETRO_PC_ITEM);
                e.accept(ROUTER_ITEM);
                e.accept(NETWORK_CABLE_ITEM);
                e.accept(WIFI_TOWER_BASE_ITEM);
                e.accept(WIFI_MAST_ITEM);
                e.accept(WIFI_ANTENNA_ITEM);
            }
            if (e.getTabKey() == CreativeModeTabs.INGREDIENTS) PART_ITEMS.values().forEach(e::accept);
        });
        LanHub.init(NeoForge.EVENT_BUS);
        PcSessions.init(NeoForge.EVENT_BUS);
    }
}
