package dev.vintagepc.block;

import dev.vintagepc.VintagePC;
import dev.vintagepc.item.PcPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Корпус ПК: установленные комплектующие (в порядке установки) и выбранная Wi-Fi-сеть. */
public class RetroPcBlockEntity extends BlockEntity {
    private final List<PcPart> parts = new ArrayList<>();
    private long wifiTower; // asLong основания вышки; 0 — не подключён

    public RetroPcBlockEntity(BlockPos pos, BlockState state) {
        super(VintagePC.RETRO_PC_BE.get(), pos, state);
    }

    public List<PcPart> parts() { return parts; }
    public boolean has(PcPart p) { return parts.contains(p); }

    public boolean complete() {
        for (PcPart p : PcPart.values()) if (p.required && !parts.contains(p)) return false;
        return true;
    }

    public List<PcPart> missing() {
        List<PcPart> m = new ArrayList<>();
        for (PcPart p : PcPart.values()) if (p.required && !parts.contains(p)) m.add(p);
        return m;
    }

    public void add(PcPart p) { parts.add(p); changed(); }

    /** Время (мс) установки каждой детали на клиенте — для анимации «въезжает в корпус». */
    public final java.util.Map<PcPart, Long> insertedAt = new java.util.EnumMap<>(PcPart.class);

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider reg) { return saveWithoutMetadata(reg); }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    public PcPart removeLast() {
        if (parts.isEmpty()) return null;
        // материнская плата вынимается последней
        for (int i = parts.size() - 1; i >= 0; i--)
            if (parts.get(i) != PcPart.MOTHERBOARD || parts.size() == 1) { PcPart p = parts.remove(i); changed(); return p; }
        return null;
    }

    public long wifiTower() { return wifiTower; }
    public void setWifiTower(long t) { wifiTower = t; setChanged(); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg);
        ListTag l = new ListTag();
        for (PcPart p : parts) l.add(StringTag.valueOf(p.id));
        tag.put("Parts", l);
        if (wifiTower != 0) tag.putLong("Wifi", wifiTower);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);
        List<PcPart> before = new ArrayList<>(parts);
        parts.clear();
        for (Tag t : tag.getList("Parts", Tag.TAG_STRING))
            for (PcPart p : PcPart.values()) if (p.id.equals(t.getAsString()) && !parts.contains(p)) parts.add(p);
        wifiTower = tag.getLong("Wifi");
        if (level != null && level.isClientSide) {                    // новая деталь — запускаем анимацию
            long now = System.currentTimeMillis();
            for (PcPart p : parts) if (!before.contains(p)) insertedAt.put(p, now);
            insertedAt.keySet().retainAll(parts);
        }
    }
}
