package dev.vintagepc.net;

import dev.vintagepc.block.NetworkCableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Физическая топология сети — обход {@link NetGraph} (работает и через непрогруженные чанки).
 *
 *  - Кабель лежит на полу, как редстоун: соединяется с соседним кабелем на той же высоте
 *    или на блок выше/ниже (подъём и спуск), а с устройствами — с любой стороны.
 *  - Компьютер — оконечное устройство: через него сеть не проходит.
 *  - Роутер и вышка Wi-Fi объединяют всё подключённое и работают как повторители.
 *  - Отрезок кабеля между устройствами — не длиннее {@link NetworkCableBlock#MAX_LENGTH} блоков.
 *  - Без роутера сеть работает только для двух ПК, соединённых напрямую (кроссовер).
 */
public final class NetTopology {
    private static final int MAX_WORK = 50_000;

    /** id — 8 hex-символов ("" — сети нет), label — имя для игрока. */
    public record Network(String id, String label, List<BlockPos> pcs, List<BlockPos> hubs) {
        public boolean up() { return !id.isEmpty(); }
        public static final Network NONE = new Network("", "", List.of(), List.of());
    }

    public static Network explore(Level level, BlockPos start) {
        NetGraph g = NetGraph.get(level);
        Set<BlockPos> pcs = new LinkedHashSet<>(), hubs = new LinkedHashSet<>();
        Deque<BlockPos> toExpand = new ArrayDeque<>();
        byte t0 = g.type(start);
        if (t0 == NetGraph.ROUTER || t0 == NetGraph.TOWER) hubs.add(start);
        else if (t0 == NetGraph.PC) pcs.add(start);
        else return Network.NONE;
        toExpand.add(start);
        int work = 0;

        while (!toExpand.isEmpty() && work < MAX_WORK) {
            BlockPos dev = toExpand.poll();
            Map<BlockPos, Integer> dist = new HashMap<>();
            Deque<BlockPos> q = new ArrayDeque<>();
            for (Direction d : Direction.values()) {
                BlockPos n = dev.relative(d);
                byte t = g.type(n);
                if (t == NetGraph.CABLE) { dist.put(n, 1); q.add(n); }
                else device(n, t, pcs, hubs, toExpand);
            }
            while (!q.isEmpty() && work++ < MAX_WORK) {
                BlockPos c = q.poll();
                int dc = dist.get(c);
                for (Direction d : Direction.values()) {          // устройства — с любой стороны кабеля
                    BlockPos n = c.relative(d);
                    byte t = g.type(n);
                    if (t != NetGraph.CABLE) { if (!n.equals(dev)) device(n, t, pcs, hubs, toExpand); }
                }
                if (dc >= NetworkCableBlock.MAX_LENGTH) continue;   // сигнал затух
                for (Direction d : Direction.Plane.HORIZONTAL) {    // кабель-кабель: как редстоун
                    BlockPos side = c.relative(d);
                    for (BlockPos n : new BlockPos[]{side, side.above(), side.below()}) {
                        if (g.type(n) != NetGraph.CABLE) continue;
                        Integer old = dist.get(n);
                        if (old != null && old <= dc + 1) continue;
                        dist.put(n, dc + 1);
                        q.add(n);
                    }
                }
            }
        }

        List<BlockPos> pcList = new ArrayList<>(pcs), hubList = new ArrayList<>(hubs);
        int dim = level.dimension().location().hashCode();
        if (!hubList.isEmpty()) {
            BlockPos main = Collections.min(hubList, Comparator.comparingLong(BlockPos::asLong));
            String id = hex(main.asLong() * 31 + dim);
            String name = "";
            for (BlockPos h : hubList) if (!g.name(h).isEmpty()) { name = g.name(h); break; }
            return new Network(id, name.isEmpty() ? "LAN-" + id.substring(0, 4).toUpperCase(Locale.ROOT) : name, pcList, hubList);
        }
        if (pcList.size() == 2) {
            BlockPos main = Collections.min(pcList, Comparator.comparingLong(BlockPos::asLong));
            return new Network(hex(main.asLong() * 17 + dim), "crossover", pcList, hubList);
        }
        return new Network("", "", pcList, hubList);
    }

    private static void device(BlockPos n, byte t, Set<BlockPos> pcs, Set<BlockPos> hubs, Deque<BlockPos> toExpand) {
        if (t == NetGraph.ROUTER || t == NetGraph.TOWER) { if (hubs.add(n.immutable())) toExpand.add(n.immutable()); }
        else if (t == NetGraph.PC) pcs.add(n.immutable());
    }

    static String hex(long v) { return String.format("%08x", (int) (v ^ (v >>> 32))); }

    private NetTopology() {}
}
