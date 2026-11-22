package dev.vintagepc.block;

import dev.vintagepc.VintagePC;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Хранит имя сети, заданное игроком (через переименованный на наковальне роутер). */
public class RouterBlockEntity extends BlockEntity implements Nameable {
    private String name = "";

    public RouterBlockEntity(BlockPos pos, BlockState state) {
        super(VintagePC.ROUTER_BE.get(), pos, state);
    }

    public String name() { return name; }

    public void setName(String n) {
        name = n == null ? "" : n.substring(0, Math.min(32, n.length()));
        setChanged();
    }

    @Override public Component getName() { return name.isEmpty() ? Component.translatable("block.vintagepc.router") : Component.literal(name); }
    @Override public Component getCustomName() { return name.isEmpty() ? null : Component.literal(name); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg);
        if (!name.isEmpty()) tag.putString("NetName", name);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);
        name = tag.getString("NetName");
    }
}
