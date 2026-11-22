package dev.vintagepc.net;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Граф сетевых устройств измерения, сохраняемый вместе с миром.
 *
 * Обход сети идёт по этому графу, а не по блокам мира, поэтому кабель через непрогруженные
 * чанки продолжает работать. Граф обновляют сами блоки в onPlace/onRemove (это срабатывает
 * и при /fill, /setblock, взрывах и поршнях).
 */
public final class NetGraph extends SavedData {
    public static final byte CABLE = 1, PC = 2, ROUTER = 3, TOWER = 4;
    private static final String ID = "vintagepc_netgraph";

    private final Long2ByteOpenHashMap nodes = new Long2ByteOpenHashMap();
    private final Long2ObjectOpenHashMap<String> names = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet formedTowers = new LongOpenHashSet();

    public static NetGraph get(Level level) {
        return ((ServerLevel) level).getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NetGraph::new, NetGraph::load, null), ID);
    }

    public byte type(BlockPos p) { return nodes.get(p.asLong()); }

    public void put(BlockPos p, byte type) {
        if (nodes.put(p.asLong(), type) != type) setDirty();
    }

    public void remove(BlockPos p) {
        long k = p.asLong();
        if (nodes.remove(k) != 0 | names.remove(k) != null | formedTowers.remove(k)) setDirty();
    }

    public String name(BlockPos p) { String n = names.get(p.asLong()); return n == null ? "" : n; }

    public void setName(BlockPos p, String name) {
        if (name == null || name.isEmpty()) { if (names.remove(p.asLong()) != null) setDirty(); }
        else if (!name.equals(names.put(p.asLong(), name))) setDirty();
    }

    public boolean formed(BlockPos p) { return formedTowers.contains(p.asLong()); }

    public void setFormed(BlockPos p, boolean f) {
        if (f ? formedTowers.add(p.asLong()) : formedTowers.remove(p.asLong())) setDirty();
    }

    /** Собранные вышки измерения (для списка беспроводных сетей). */
    public long[] towers() { return formedTowers.toLongArray(); }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        for (byte t = CABLE; t <= TOWER; t++) {
            final byte tt = t;
            tag.putLongArray("t" + t, nodes.long2ByteEntrySet().stream().filter(e -> e.getByteValue() == tt).mapToLong(e -> e.getLongKey()).toArray());
        }
        ListTag ns = new ListTag();
        names.forEach((k, v) -> { CompoundTag c = new CompoundTag(); c.putLong("p", k); c.putString("n", v); ns.add(c); });
        tag.put("names", ns);
        tag.putLongArray("formed", formedTowers.toLongArray());
        return tag;
    }

    private static NetGraph load(CompoundTag tag, HolderLookup.Provider reg) {
        NetGraph g = new NetGraph();
        for (byte t = CABLE; t <= TOWER; t++) for (long k : tag.getLongArray("t" + t)) g.nodes.put(k, t);
        for (Tag e : tag.getList("names", Tag.TAG_COMPOUND)) g.names.put(((CompoundTag) e).getLong("p"), ((CompoundTag) e).getString("n"));
        for (long k : tag.getLongArray("formed")) g.formedTowers.add(k);
        return g;
    }
}
