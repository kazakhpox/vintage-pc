package dev.vintagepc.client;

import dev.vintagepc.VintagePC;
import dev.vintagepc.block.RetroPcBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = VintagePC.MOD_ID, dist = Dist.CLIENT)
public final class VintagePCClient {
    public VintagePCClient(IEventBus modBus) {
        RetroPcBlock.clientOpenHook = VintagePCClient::open;
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> LanBridge.onLogin());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            LanBridge.onLogout();
            if (ModList.get().isLoaded("mcef")) BrowserPool.closeAll();
        });
    }

    private static void open(net.minecraft.core.BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (!ModList.get().isLoaded("mcef")) {
            if (mc.player != null) mc.player.displayClientMessage(
                    Component.translatable("vintagepc.no_mcef"), false);
            return;
        }
        String url = WebAssets.extract().toUri() + "?" + LanBridge.urlParams()
                + "&pc=" + Integer.toHexString(pos.hashCode());
        PcScreenOpener.open(pos.immutable(), url);
    }
}
