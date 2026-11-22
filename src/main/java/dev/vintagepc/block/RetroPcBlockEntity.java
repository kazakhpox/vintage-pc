package dev.vintagepc.block;

import dev.vintagepc.VintagePC;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Нужен только для рендера изображения на экране монитора; данных не хранит. */
public class RetroPcBlockEntity extends BlockEntity {
    public RetroPcBlockEntity(BlockPos pos, BlockState state) {
        super(VintagePC.RETRO_PC_BE.get(), pos, state);
    }
}
