package dev.vintagepc.client;

import dev.vintagepc.net.PcPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * Клиентская логика сессий ПК. Не трогает классы MCEF напрямую — всё, что требует Chromium,
 * живёт в {@link HostSide}, поэтому зрителем можно быть и без установленного MCEF.
 */
public final class PcClient {
    /** Сессии, где этот клиент — хост: pos → [контролёр ("" = я), число зрителей]. */
    static final Map<BlockPos, HostInfo> hosting = new HashMap<>();
    static final class HostInfo { String controller = ""; int viewers, nearby; }

    /** Физическая сеть каждого своего ПК (из NetState). */
    record NetInfo(String id, String label, int pcs, String via, boolean wifiCard, long wifiTarget, java.util.List<PcPayloads.Tower> towers) {}
    private static final Map<BlockPos, NetInfo> nets = new java.util.concurrent.ConcurrentHashMap<>();

    static NetInfo netInfo(BlockPos pos) { return nets.get(pos); }

    public static void onNetState(PcPayloads.NetState p) {
        NetInfo info = new NetInfo(p.net(), p.label(), p.pcs(), p.via(), p.wifiCard(), p.wifiTarget(), p.towers());
        nets.put(p.pos(), info);
        LanBridge.netChanged(p.pos(), info);
    }

    static boolean hasMcef() { return ModList.get().isLoaded("mcef"); }

    private static float lastHealth = -1;
    private static int lastHurtTime;

    static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> {
            if (hasMcef() && !hosting.isEmpty()) HostSide.captureTick();
        });
    }

    /** Закрываем экран ПК, если игрока ударили — иначе за монитором можно молча умереть. */
    private static int ticks;

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (++ticks % 20 == 0) ScreenTextures.expire();
        var p = mc.player;
        if (p == null) { lastHealth = -1; return; }
        boolean atPc = mc.screen instanceof PcScreenMarker;
        boolean hurt = lastHealth >= 0 && (p.getHealth() < lastHealth - 0.01f || p.hurtTime > lastHurtTime);
        lastHealth = p.getHealth();
        lastHurtTime = p.hurtTime;
        if (atPc && hurt) {
            mc.setScreen(null);
            p.displayClientMessage(Component.translatable("vintagepc.hurt_close"), true);
        }
    }

    // ---------- пакеты от сервера ----------

    public static void onOpen(PcPayloads.Open p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.host()) {
            if (!hasMcef()) {
                if (mc.player != null) mc.player.displayClientMessage(Component.translatable("vintagepc.no_mcef"), false);
                send(new PcPayloads.Leave(p.pos()));
                return;
            }
            hosting.computeIfAbsent(p.pos(), k -> new HostInfo());
            HostSide.open(p.pos());
        } else {
            mc.setScreen(new ViewerScreen(p.pos(), p.hostName()));
        }
    }

    public static void onClosed(PcPayloads.Closed p) {
        Minecraft mc = Minecraft.getInstance();
        ScreenTextures.release(p.pos());
        if (nets.remove(p.pos()) != null) LanBridge.netChanged(p.pos(), null);
        if (hosting.remove(p.pos()) != null && hasMcef()) HostSide.close(p.pos());
        if (mc.screen instanceof PcScreenMarker m && m.pos().equals(p.pos())) mc.setScreen(null);
    }

    public static void onState(PcPayloads.State p) {
        HostInfo h = hosting.get(p.pos());
        if (h != null) { h.controller = p.controller(); h.viewers = p.viewers(); h.nearby = p.nearby(); }
        if (Minecraft.getInstance().screen instanceof ViewerScreen v && v.pos().equals(p.pos()))
            v.setState(p.controller(), p.viewers());
    }

    public static void onFrame(PcPayloads.Frame p) {
        if (!hosting.containsKey(p.pos())) ScreenTextures.acceptChunk(p);
    }

    public static void onInput(PcPayloads.Input p) {
        if (hasMcef() && hosting.containsKey(p.pos())) HostSide.inject(p);
    }

    /** ПК «выключили» из меню Пуск или браузер вытеснен — освобождаем место за компьютером. */
    static void shutdown(BlockPos pos) {
        ScreenTextures.release(pos);
        if (nets.remove(pos) != null) LanBridge.netChanged(pos, null);
        if (hosting.remove(pos) == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PcScreenMarker m && m.pos().equals(pos)) mc.setScreen(null);
        if (hasMcef()) HostSide.close(pos);
        send(new PcPayloads.Leave(pos));
    }

    static void onLogout() {
        nets.clear();
        ScreenTextures.releaseAll();
        hosting.clear();
        if (hasMcef()) HostSide.closeAll();
    }

    static String controllerOf(BlockPos pos) {
        HostInfo h = hosting.get(pos);
        return h == null ? "" : h.controller;
    }

    static int viewersOf(BlockPos pos) {
        HostInfo h = hosting.get(pos);
        return h == null ? 0 : h.viewers;
    }

    static void send(CustomPacketPayload p) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.getConnection() != null) PacketDistributor.sendToServer(p); });
    }

    static String key(BlockPos pos) { return Long.toHexString(pos.asLong()); }
    static BlockPos fromKey(String k) {
        try { return BlockPos.of(Long.parseUnsignedLong(k, 16)); } catch (NumberFormatException e) { return null; }
    }

    private PcClient() {}
}
