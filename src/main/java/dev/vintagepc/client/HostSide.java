package dev.vintagepc.client;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.vintagepc.VintagePC;
import dev.vintagepc.net.PcPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Хост ПК: браузер MCEF, трансляция экрана зрителям и приём их ввода. Грузится только при наличии MCEF.
 *
 * Трансляция: текстура Chromium → glGetTexImage → уменьшение до ≤ 854 px → JPEG (stb, нативно)
 * → куски по 15 КБ → сервер → зрители. Пока экран не меняется, кадры не шлются (сравнение CRC),
 * но раз в 2 секунды уходит опорный кадр для только что подошедших игроков.
 */
final class HostSide {
    /** 6 к/с, когда кто-то сидит за экраном; 2 к/с, когда монитор только видят рядом в мире. */
    private static final int FPS_VIEWERS = 6, FPS_NEARBY = 2, MAX_W = 854, QUALITY = 60;
    private static final ExecutorService encoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VintagePC-encoder");
        t.setDaemon(true);
        return t;
    });
    private static final Map<BlockPos, long[]> stats = new HashMap<>(); // [последний захват мс, последний опорный мс, crc, номер кадра]
    private static final AtomicBoolean busy = new AtomicBoolean();

    private static boolean diagInstalled;

    /** Ошибки загрузки и консоль страниц Chromium → logs/latest.log (только для страниц Vintage PC). */
    static void installDiagnostics() {
        if (diagInstalled) return;
        diagInstalled = true;
        try {
            var client = com.cinemamod.mcef.MCEF.getClient();
            client.addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                @Override public void onLoadEnd(org.cef.browser.CefBrowser b, org.cef.browser.CefFrame f, int status) {
                    if (ours(f.getURL()) && f.isMain()) VintagePC.LOG.info("Vintage PC page loaded: {} (HTTP {})", strip(f.getURL()), status);
                }
                @Override public void onLoadError(org.cef.browser.CefBrowser b, org.cef.browser.CefFrame f, ErrorCode code, String text, String url) {
                    if (!ours(url)) return;
                    VintagePC.LOG.error("Vintage PC page failed: {} -> {} {}", strip(url), code, text);
                    Minecraft.getInstance().execute(() -> {
                        var p = Minecraft.getInstance().player;
                        if (p != null) p.displayClientMessage(net.minecraft.network.chat.Component.translatable("vintagepc.load_error", String.valueOf(code)), false);
                    });
                }
            });
            client.addDisplayHandler(new org.cef.handler.CefDisplayHandlerAdapter() {
                @Override public boolean onConsoleMessage(org.cef.browser.CefBrowser b, org.cef.CefSettings.LogSeverity level, String msg, String src, int line) {
                    if (ours(src) || ours(b.getURL())) VintagePC.LOG.info("Vintage PC console [{}] {} ({}:{})", level, msg, strip(src), line);
                    return false;
                }
            });
        } catch (Throwable t) {
            VintagePC.LOG.warn("Vintage PC: could not attach Chromium diagnostics", t);
        }
    }

    private static boolean ours(String url) { return url != null && url.startsWith("http://127.0.0.1:"); }
    private static String strip(String url) { return url == null ? "" : url.replaceAll("t=[0-9a-f]+", "t=***"); }

    static void open(BlockPos pos) {
        installDiagnostics();
        Minecraft.getInstance().setScreen(new PcScreen(pos, LanBridge.shellUrl(PcClient.key(pos))));
    }

    static boolean ready() { return BrowserPool.ready(); }

    static void reload(BlockPos pos, String url) {
        MCEFBrowser b = BrowserPool.peek(pos);
        if (b != null) b.loadURL(url);
    }

    static void close(BlockPos pos) {
        BrowserPool.close(pos);
        stats.remove(pos);
    }

    static void closeAll() {
        BrowserPool.closeAll();
        stats.clear();
    }

    /** id GL-текстуры Chromium для монитора в мире (берётся заново каждый кадр — MCEF может её пересоздать). */
    static java.util.function.IntSupplier textureId(BlockPos pos) {
        if (BrowserPool.peek(pos) == null) return null;
        return () -> { MCEFBrowser b = BrowserPool.peek(pos); return b == null ? 0 : b.getRenderer().getTextureID(); };
    }

    static void inject(PcPayloads.Input in) {
        MCEFBrowser b = BrowserPool.peek(in.pos());
        int[] size = BrowserPool.size(in.pos());
        if (b == null || size == null) return;
        int x = Math.round(in.x() * size[0]), y = Math.round(in.y() * size[1]);
        switch (in.kind()) {
            case 0 -> b.sendKeyPress(in.a(), in.b(), in.c());
            case 1 -> b.sendKeyRelease(in.a(), in.b(), in.c());
            case 2 -> { if (Character.isValidCodePoint(in.a())) for (char ch : Character.toChars(in.a())) b.sendKeyTyped(ch, in.c()); }
            case 3 -> { b.setFocus(true); b.sendMousePress(x, y, in.a()); }
            case 4 -> b.sendMouseRelease(x, y, in.a());
            case 5 -> b.sendMouseMove(x, y);
            case 6 -> b.sendMouseWheel(x, y, in.a() / 100.0, 0);
            default -> {}
        }
    }

    /** Вызывается после каждого кадра Minecraft (поток рендера). */
    static void captureTick() {
        long now = System.currentTimeMillis();
        for (var e : PcClient.hosting.entrySet()) {
            var info = e.getValue();
            if (info.viewers <= 0 && info.nearby <= 0) continue;
            int fps = info.viewers > 0 ? FPS_VIEWERS : FPS_NEARBY;
            BlockPos pos = e.getKey();
            long[] st = stats.computeIfAbsent(pos, k -> new long[4]);
            if (now - st[0] < 1000 / fps || busy.get()) continue;
            MCEFBrowser b = BrowserPool.peek(pos);
            if (b == null) continue;
            st[0] = now;

            int tex = b.getRenderer().getTextureID();
            GlStateManager._bindTexture(tex);
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0) continue;
            ByteBuffer raw = MemoryUtil.memAlloc(w * h * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, raw);
            GlStateManager._bindTexture(0);
            busy.set(true);
            boolean key = now - st[1] > 2000;
            encoder.execute(() -> {
                try { encode(pos, raw, w, h, key, st); }
                catch (Throwable t) { VintagePC.LOG.warn("screen encode failed", t); }
                finally { MemoryUtil.memFree(raw); busy.set(false); }
            });
        }
    }

    private static void encode(BlockPos pos, ByteBuffer raw, int w, int h, boolean keyframe, long[] st) {
        int dw = Math.min(w, MAX_W), dh = Math.max(1, (int) ((long) h * dw / w));
        ByteBuffer small = MemoryUtil.memAlloc(dw * dh * 3);
        try {
            for (int y = 0; y < dh; y++) {
                int sy = (int) ((long) y * h / dh);
                for (int x = 0; x < dw; x++) {
                    int si = (sy * w + (int) ((long) x * w / dw)) * 4;
                    small.put(raw.get(si)).put(raw.get(si + 1)).put(raw.get(si + 2));
                }
            }
            small.flip();
            CRC32 crc = new CRC32();
            crc.update(small.duplicate());
            long c = crc.getValue();
            if (c == st[2] && !keyframe) return; // экран не изменился
            st[2] = c;
            if (keyframe) st[1] = System.currentTimeMillis();

            ByteArrayOutputStream jpg = new ByteArrayOutputStream(64 * 1024);
            try (STBIWriteCallback cb = STBIWriteCallback.create((ctx, data, size) -> {
                ByteBuffer part = STBIWriteCallback.getData(data, size);
                byte[] tmp = new byte[size];
                part.get(tmp);
                jpg.write(tmp, 0, size);
            })) {
                STBImageWrite.stbi_write_jpg_to_func(cb, 0L, dw, dh, 3, small, QUALITY);
            }
            byte[] bytes = jpg.toByteArray();
            int frame = (int) (++st[3] & 0x7FFFFFFF);
            int total = (bytes.length + PcPayloads.MAX_CHUNK - 1) / PcPayloads.MAX_CHUNK;
            if (total > 255) return;
            for (int i = 0; i < total; i++) {
                int from = i * PcPayloads.MAX_CHUNK, to = Math.min(bytes.length, from + PcPayloads.MAX_CHUNK);
                byte[] part = java.util.Arrays.copyOfRange(bytes, from, to);
                PcClient.send(new PcPayloads.Frame(pos, frame, i, total, part));
            }
        } finally {
            MemoryUtil.memFree(small);
        }
    }

    private HostSide() {}
}
