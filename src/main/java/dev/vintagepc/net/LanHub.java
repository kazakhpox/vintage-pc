package dev.vintagepc.net;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

/**
 * Серверная часть виртуальной LAN: сервер Minecraft работает как сетевой коммутатор.
 *
 * eth:*  — обучаемый L2-свитч: запоминает MAC отправителя, unicast-кадры шлёт только
 *          владельцу MAC, broadcast/multicast/неизвестные — всем в сети. Этого хватает
 *          для IPX (большинство LAN-игр 9x/DOS) и TCP/IP внутри эмулятора v86.
 * room:* — широковещательная «комната» для веб-игр, чата и «Сетевого окружения».
 *
 * Обработчики NeoForge 1.21.1 по умолчанию выполняются в главном потоке сервера,
 * поэтому синхронизация не нужна.
 */
public final class LanHub {
    private static final int MAX_PACKETS_PER_TICK = 200;   // ~4000 пакетов/с на игрока
    private static final int MAX_BYTES_PER_TICK = 256 * 1024;
    private static final int MAX_CHANNELS_PER_PLAYER = 16;

    private static final Map<String, Set<UUID>> members = new HashMap<>();
    private static final Map<String, Map<Long, UUID>> macTables = new HashMap<>();
    private static final Map<UUID, int[]> budget = new HashMap<>(); // [пакеты, байты]

    public static void init(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post e) -> budget.clear());
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> leaveAll(e.getEntity().getUUID()));
        bus.addListener((ServerStoppedEvent e) -> { members.clear(); macTables.clear(); budget.clear(); });
    }

    static void onMembership(LanPayloads.Membership p, IPayloadContext ctx) {
        if (!LanPayloads.validChannel(p.channel()) || !(ctx.player() instanceof ServerPlayer sp)) return;
        UUID id = sp.getUUID();
        if (p.join()) {
            long count = members.values().stream().filter(s -> s.contains(id)).count();
            if (count >= MAX_CHANNELS_PER_PLAYER) return;
            members.computeIfAbsent(p.channel(), k -> new HashSet<>()).add(id);
        } else {
            leave(p.channel(), id);
        }
    }

    static void onSend(LanPayloads.SendC2S p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer from)) return;
        int[] b = budget.computeIfAbsent(from.getUUID(), k -> new int[2]);
        if (b[0] >= MAX_PACKETS_PER_TICK || b[1] + p.data().length > MAX_BYTES_PER_TICK) return; // перегруженный хаб
        b[0]++; b[1] += p.data().length;
        route(from, p.channel(), p.data());
    }

    private static void leaveAll(UUID id) {
        for (String ch : new ArrayList<>(members.keySet())) leave(ch, id);
        budget.remove(id);
    }

    private static void leave(String channel, UUID id) {
        Set<UUID> set = members.get(channel);
        if (set != null) {
            set.remove(id);
            if (set.isEmpty()) { members.remove(channel); macTables.remove(channel); return; }
        }
        Map<Long, UUID> macs = macTables.get(channel);
        if (macs != null) macs.values().removeIf(id::equals);
    }

    private static void route(ServerPlayer from, String channel, byte[] data) {
        Set<UUID> set = members.get(channel);
        if (set == null || !set.contains(from.getUUID())) return;
        var players = from.server.getPlayerList();
        var out = new LanPayloads.DeliverS2C(channel, from.getGameProfile().getName(), data);

        if (channel.startsWith("eth:")) {
            if (data.length < 14 || data.length > 1600) return;
            long dst = mac(data, 0), src = mac(data, 6);
            Map<Long, UUID> table = macTables.computeIfAbsent(channel, k -> new HashMap<>());
            if ((data[6] & 1) == 0 && table.size() < 4096) table.put(src, from.getUUID());
            boolean groupcast = (data[0] & 1) != 0;
            UUID target = groupcast ? null : table.get(dst);
            if (target != null) {
                if (!target.equals(from.getUUID()) && set.contains(target)) {
                    ServerPlayer tp = players.getPlayer(target);
                    if (tp != null) PacketDistributor.sendToPlayer(tp, out);
                }
                return;
            }
        }
        for (UUID id : set) {
            if (id.equals(from.getUUID())) continue;
            ServerPlayer tp = players.getPlayer(id);
            if (tp != null) PacketDistributor.sendToPlayer(tp, out);
        }
    }

    private static long mac(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 6; i++) v = (v << 8) | (d[off + i] & 0xFF);
        return v;
    }

    private LanHub() {}
}
