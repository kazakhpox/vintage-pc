package dev.vintagepc.client;

import net.minecraft.core.BlockPos;

/** Экран, относящийся к конкретному ПК (хост или зритель). */
interface PcScreenMarker {
    BlockPos pos();
}
