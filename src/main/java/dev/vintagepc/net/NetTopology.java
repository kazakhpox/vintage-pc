package dev.vintagepc.net;

import dev.vintagepc.block.NetworkCableBlock;
import dev.vintagepc.block.RetroPcBlock;
import dev.vintagepc.block.RouterBlock;
import dev.vintagepc.block.RouterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Физическая топология сети: обход кабелей от устройства.
 *
 *  - Компьютер — оконечное устройство: через него сеть не проходит (две сетевые карты не мост).
 *  - Роутер объединяет всё подключённое и работает как повторитель.
 *  - Один отрезок кабеля между устройствами — не длиннее {@link NetworkCableBlock#MAX_LENGTH} блоков.
 *  - Без роутера сеть работает только для двух ПК, соединённых напрямую (кроссовер).
 */
public final class NetTopology {
    private static final int MAX_WORK = 20_000;

    /** id — 8 hex-символов ("" — сети нет), label — имя для игрока. */
    public record Network(String id, String label, List<BlockPos> pcs, List<BlockPos> routers) {
        public boolean up() { return !id.isEmpty(); }
    }

    public static Network explore(Level level, BlockPos start) {
        Set<BlockPos> pcs = new LinkedHashSet<>(), routers = new LinkedHashSet<>();
        Deque<BlockPos> toExpand = new ArrayDeque<>();
        int[] work = {0};
        BlockState s0 = level.getBlockState(start);
        if (s0.getBlock() instanceof RouterBlock) routers.add(start);
        else if (s0.getBlock() instanceof RetroPcBlock) pcs.add(start);
        else return new Network("", "", List.of(), List.of());
        toExpand.add(start);

        while (!toExpand.isEmpty() && work[0] < MAX_WORK) {
            BlockPos dev = toExpand.poll();
            // обход кабелей от устройства, с ограничением длины
            Map<BlockPos, Integer> dist = new HashMap<>();
            Deque<BlockPos> q = new ArrayDeque<>();
            for (Direction d : Direction.values()) visit(level, dev.relative(d), 0, dist, q, pcs, routers, toExpand, dev);
            while (!q.isEmpty() && work[0]++ < MAX_WORK) {
                BlockPos c = q.poll();
                int dc = dist.get(c);
                for (Direction d : Direction.values()) visit(level, c.relative(d), dc, dist, q, pcs, routers, toExpand, dev);
            }
        }

        List<BlockPos> pcList = new ArrayList<>(pcs), rList = new ArrayList<>(routers);
        int dim = level.dimension().location().hashCode();
        if (!rList.isEmpty()) {
            BlockPos main = Collections.min(rList, Comparator.comparingLong(BlockPos::asLong));
            String id = hex(main.asLong() * 31 + dim);
            String name = level.getBlockEntity(main) instanceof RouterBlockEntity be ? be.name() : "";
            return new Network(id, name.isEmpty() ? "LAN-" + id.substring(0, 4).toUpperCase(Locale.ROOT) : name, pcList, rList);
        }
        if (pcList.size() == 2) {
            BlockPos main = Collections.min(pcList, Comparator.comparingLong(BlockPos::asLong));
            return new Network(hex(main.asLong() * 17 + dim), "crossover", pcList, rList);
        }
        return new Network("", "", pcList, rList);
    }

    private static void visit(Level level, BlockPos p, int fromDist, Map<BlockPos, Integer> dist, Deque<BlockPos> q,
                              Set<BlockPos> pcs, Set<BlockPos> routers, Deque<BlockPos> toExpand, BlockPos source) {
        if (p.equals(source) || !level.isLoaded(p)) return;
        BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof NetworkCableBlock) {
            int d = fromDist + 1;
            if (d > NetworkCableBlock.MAX_LENGTH) return;             // сигнал затух
            Integer old = dist.get(p);
            if (old != null && old <= d) return;
            dist.put(p, d);
            q.add(p);
        } else if (s.getBlock() instanceof RouterBlock) {
            if (routers.add(p.immutable())) toExpand.add(p.immutable());
        } else if (s.getBlock() instanceof RetroPcBlock) {
            pcs.add(p.immutable());                                    // ПК — оконечное устройство
        }
    }

    private static String hex(long v) {
        return String.format("%08x", (int) (v ^ (v >>> 32)));
    }

    private NetTopology() {}
}
