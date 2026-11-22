package dev.vintagepc.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vintagepc.VintagePC;
import dev.vintagepc.net.PcPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;

/**
 * Текстуры экранов чужих ПК: собирает JPEG-кадры из кусков, декодирует в фоне и держит
 * по одной DynamicTexture на компьютер. Их рисуют и монитор в мире, и экран зрителя.
 * Для своих ПК (хост) используется сама текстура Chromium — без копирования и сжатия.
 */
public final class ScreenTextures {
    private static final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VintagePC-decoder");
        t.setDaemon(true);
        return t;
    });

    private static final class Entry {
        final ResourceLocation loc;
        DynamicTexture tex;
        int w, h, frameId = -1, got, total;
        ByteArrayOutputStream parts = new ByteArrayOutputStream();
        long lastFrame = System.currentTimeMillis();
        boolean decoding;
        Entry(BlockPos pos) { loc = VintagePC.id("dynamic/pc_" + PcClient.key(pos)); }
    }

    private static final Map<BlockPos, Entry> remote = new HashMap<>();
    private static final Map<BlockPos, ResourceLocation> hostTex = new HashMap<>();

    /** Текстура для рисования экрана ПК в позиции pos или null, если картинки нет. */
    public static ResourceLocation get(BlockPos pos) {
        if (PcClient.hosting.containsKey(pos) && PcClient.hasMcef()) return hostTexture(pos);
        Entry e = remote.get(pos);
        return e != null && e.tex != null ? e.loc : null;
    }

    static int[] size(BlockPos pos) {
        Entry e = remote.get(pos);
        return e == null || e.tex == null ? null : new int[]{e.w, e.h};
    }

    private static ResourceLocation hostTexture(BlockPos pos) {
        IntSupplier id = HostSide.textureId(pos);
        if (id == null) return null;
        return hostTex.computeIfAbsent(pos, p -> {
            ResourceLocation loc = VintagePC.id("dynamic/host_" + PcClient.key(p));
            Minecraft.getInstance().getTextureManager().register(loc, new ExternalTexture(id));
            return loc;
        });
    }

    static void acceptChunk(PcPayloads.Frame f) {
        Entry e = remote.computeIfAbsent(f.pos(), Entry::new);
        if (f.frame() != e.frameId) { e.frameId = f.frame(); e.got = 0; e.total = f.total(); e.parts = new ByteArrayOutputStream(); }
        if (f.index() != e.got) { e.frameId = -1; return; } // потеря/перестановка — ждём следующий кадр
        e.parts.writeBytes(f.data());
        if (++e.got < e.total) return;
        byte[] jpg = e.parts.toByteArray();
        e.frameId = -1;
        e.lastFrame = System.currentTimeMillis();
        if (e.decoding) return; // не успеваем — пропускаем кадр, а не копим очередь
        e.decoding = true;
        decoder.execute(() -> decode(f.pos(), e, jpg));
    }

    private static void decode(BlockPos pos, Entry e, byte[] jpg) {
        ByteBuffer src = MemoryUtil.memAlloc(jpg.length).put(jpg).flip();
        NativeImage img = null;
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer w = st.mallocInt(1), h = st.mallocInt(1), c = st.mallocInt(1);
            ByteBuffer px = STBImage.stbi_load_from_memory(src, w, h, c, 4);
            if (px != null) {
                int W = w.get(0), H = h.get(0);
                img = new NativeImage(NativeImage.Format.RGBA, W, H, false);
                px.order(ByteOrder.LITTLE_ENDIAN);
                for (int y = 0; y < H; y++)
                    for (int x = 0; x < W; x++) img.setPixelRGBA(x, y, px.getInt((y * W + x) * 4)); // RGBA-байты = ABGR int
                STBImage.stbi_image_free(px);
            }
        } finally {
            MemoryUtil.memFree(src);
        }
        NativeImage done = img;
        Minecraft.getInstance().execute(() -> {
            e.decoding = false;
            if (done == null) return;
            if (remote.get(pos) != e) { done.close(); return; }
            if (e.tex == null || e.w != done.getWidth() || e.h != done.getHeight()) {
                if (e.tex != null) Minecraft.getInstance().getTextureManager().release(e.loc);
                e.tex = new DynamicTexture(done);
                e.w = done.getWidth(); e.h = done.getHeight();
                Minecraft.getInstance().getTextureManager().register(e.loc, e.tex);
            } else {
                e.tex.setPixels(done);
                e.tex.upload();
            }
        });
    }

    static void release(BlockPos pos) {
        Entry e = remote.remove(pos);
        if (e != null && e.tex != null) Minecraft.getInstance().getTextureManager().release(e.loc);
        ResourceLocation h = hostTex.remove(pos);
        if (h != null) Minecraft.getInstance().getTextureManager().release(h);
    }

    static void releaseAll() {
        for (BlockPos p : new java.util.ArrayList<>(remote.keySet())) release(p);
        for (BlockPos p : new java.util.ArrayList<>(hostTex.keySet())) release(p);
    }

    /** Раз в секунду: убрать картинки, кадры для которых давно не приходят (хост ушёл, связь пропала). */
    static void expire() {
        long now = System.currentTimeMillis();
        for (var it = remote.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (now - en.getValue().lastFrame > 20_000 && !en.getValue().decoding) {
                if (en.getValue().tex != null) Minecraft.getInstance().getTextureManager().release(en.getValue().loc);
                it.remove();
            }
        }
    }

    /** Обёртка над чужой GL-текстурой (Chromium) — чтобы рисовать её обычными RenderType. */
    private static final class ExternalTexture extends AbstractTexture {
        private final IntSupplier id;
        ExternalTexture(IntSupplier id) { this.id = id; }
        @Override public int getId() { return id.getAsInt(); }
        @Override public void load(ResourceManager rm) {}
        @Override public void releaseId() {} // текстурой владеет MCEF
        @Override public void close() {}
    }

    private ScreenTextures() {}
}
