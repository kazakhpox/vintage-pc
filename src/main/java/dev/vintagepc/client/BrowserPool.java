package dev.vintagepc.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Браузер на каждый включённый компьютер живёт, пока игрок на сервере: закрыл экран — игра
 * и музыка продолжают работать (и транслироваться зрителям), вернулся — всё на месте.
 */
final class BrowserPool {
    private static final int MAX_BROWSERS = 4;
    private static final Map<BlockPos, MCEFBrowser> browsers = new LinkedHashMap<>();
    private static final Map<BlockPos, int[]> sizes = new HashMap<>();

    static MCEFBrowser get(BlockPos pos, String url) {
        MCEFBrowser b = browsers.get(pos);
        if (b != null) return b;
        if (browsers.size() >= MAX_BROWSERS) { // выключаем самый старый компьютер
            BlockPos oldest = browsers.keySet().iterator().next();
            PcClient.shutdown(oldest);
            close(oldest);
        }
        b = MCEF.createBrowser(url, false);
        browsers.put(pos, b);
        PcClient.browserCreated(pos);
        dev.vintagepc.VintagePC.LOG.info("Vintage PC browser created for {} -> {}", pos.toShortString(), url.replaceAll("t=[0-9a-f]+", "t=***"));
        return b;
    }

    static MCEFBrowser peek(BlockPos pos) { return browsers.get(pos); }

    /**
     * Стандартный обработчик MCEF при любой смене курсора на странице вызывает glfwSetInputMode(NORMAL) —
     * браузер ПК живёт и после выхода из-за компьютера, и курсор «вылезал» прямо в игре.
     * Меняем курсор, только пока игрок сидит за этим ПК.
     */
    static void guardCursor(BlockPos pos, MCEFBrowser b) {
        b.setCursorChangeListener(type -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof PcScreen ps && ps.pos().equals(pos)) b.setCursor(org.cef.misc.CefCursorType.fromId(type));
        }));
    }

    /** Обычный курсор и захват мыши игрой после выхода из-за ПК. */
    static void resetCursor() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            org.lwjgl.glfw.GLFW.glfwSetCursor(mc.getWindow().getWindow(), 0L);
            if (mc.screen == null) mc.mouseHandler.grabMouse();
        });
    }

    static void resize(BlockPos pos, MCEFBrowser b, int w, int h) {
        int[] old = sizes.get(pos);
        if (old != null && old[0] == w && old[1] == h) return;
        b.resize(w, h);
        sizes.put(pos, new int[]{w, h});
    }

    static int[] size(BlockPos pos) { return sizes.get(pos); }

    static void close(BlockPos pos) {
        MCEFBrowser b = browsers.remove(pos);
        sizes.remove(pos);
        if (b != null) b.close();
    }

    static void closeAll() {
        browsers.values().forEach(MCEFBrowser::close);
        browsers.clear();
        sizes.clear();
    }

    static boolean ready() { return MCEF.isInitialized(); }
}
