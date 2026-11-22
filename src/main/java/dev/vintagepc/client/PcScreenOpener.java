package dev.vintagepc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Отдельный класс, чтобы классы MCEF грузились только если мод MCEF установлен. */
final class PcScreenOpener {
    static void open(BlockPos pos, String url) {
        Minecraft.getInstance().setScreen(new PcScreen(pos, url));
    }
}
