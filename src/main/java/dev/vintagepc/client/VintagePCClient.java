package dev.vintagepc.client;

import dev.vintagepc.VintagePC;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = VintagePC.MOD_ID, dist = Dist.CLIENT)
public final class VintagePCClient {
    public VintagePCClient(IEventBus modBus) {
        PcClient.init();
        BuiltinCartridges.installAsync(LanBridge.dataRoot().resolve("cartridges"));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(VintagePC.RETRO_PC_BE.get(), MonitorRenderer::new));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> LanBridge.onLogin());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            LanBridge.onLogout();
            PcClient.onLogout();
        });
    }
}
