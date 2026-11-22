package dev.vintagepc.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Браузер на каждый компьютер живёт, пока игрок на сервере: закрыл экран — игра/музыка
 * в Winamp продолжает работать, вернулся к ПК — всё на месте (как не выключенный компьютер).
 */
final class BrowserPool {
    private static final int MAX_BROWSERS = 4;
    private static final Map<BlockPos, MCEFBrowser> browsers = new LinkedHashMap<>();

    static MCEFBrowser get(BlockPos pos, String url) {
        MCEFBrowser b = browsers.get(pos);
        if (b != null) return b;
        if (browsers.size() >= MAX_BROWSERS) { // выключаем самый старый «компьютер»
            var it = browsers.entrySet().iterator();
            it.next().getValue().close();
            it.remove();
        }
        b = MCEF.createBrowser(url, false);
        browsers.put(pos, b);
        return b;
    }

    static void closeAll() {
        browsers.values().forEach(MCEFBrowser::close);
        browsers.clear();
    }

    static boolean ready() { return MCEF.isInitialized(); }
}
