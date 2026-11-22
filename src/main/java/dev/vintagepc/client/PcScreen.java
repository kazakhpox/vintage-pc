package dev.vintagepc.client;

import com.cinemamod.mcef.MCEFBrowser;
import dev.vintagepc.net.PcPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Хост за компьютером. Интерфейс ПК рисуется на мониторе в мире ({@link MonitorRenderer}),
 * камера подлетает к экрану ({@link PcCamera}); этот экран лишь ловит ввод и показывает подсказки.
 * Shift+Esc — встать из-за компьютера, F8 — забрать управление у зрителя.
 */
final class PcScreen extends Screen implements PcScreenMarker {
    private final BlockPos pos;
    private final String url;
    private MCEFBrowser browser;
    private boolean closing;

    PcScreen(BlockPos pos, String url) {
        super(Component.translatable("block.vintagepc.retro_pc"));
        this.pos = pos;
        this.url = url;
    }

    @Override public BlockPos pos() { return pos; }

    @Override
    protected void init() {
        if (!closing) PcCamera.enter(pos);
        if (!BrowserPool.ready()) return;
        if (browser == null) browser = BrowserPool.get(pos, url);
        resizeBrowser();
        browser.setFocus(true);
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        resizeBrowser();
    }

    private double scale() { return minecraft.getWindow().getGuiScale(); }
    private double[] rect() { return PcCamera.screenRect(width, height); }

    /** Браузер — ровно 4:3, в разрешении, которое экран монитора занимает в окне (резко, без растяжения). */
    private void resizeBrowser() {
        if (browser == null) return;
        double[] r = rect();
        int w = (int) Math.round(r[2] * scale()), h = (int) Math.round(r[3] * scale());
        if (w > 100 && h > 75) BrowserPool.resize(pos, browser, w, h);
    }

    private int bx(double x) { double[] r = rect(); int[] s = BrowserPool.size(pos); return s == null ? 0 : (int) ((x - r[0]) / r[2] * s[0]); }
    private int by(double y) { double[] r = rect(); int[] s = BrowserPool.size(pos); return s == null ? 0 : (int) ((y - r[1]) / r[3] * s[1]); }
    private boolean locked() { return browser == null || closing || !PcCamera.settled() || !PcClient.controllerOf(pos).isEmpty(); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (browser == null) {
            g.drawCenteredString(font, Component.translatable("vintagepc.booting"), width / 2, height - 20, 0xFFFFFF);
            if (BrowserPool.ready()) init();
            return;
        }
        if (!PcCamera.settled()) return;
        int viewers = PcClient.viewersOf(pos);
        String ctrl = PcClient.controllerOf(pos);
        Component c = !ctrl.isEmpty() ? Component.translatable("vintagepc.host_locked", ctrl)
                : viewers > 0 ? Component.translatable("vintagepc.host_status", viewers)
                : Component.translatable("vintagepc.host_hint");
        int w = font.width(c) + 10;
        g.fill((width - w) / 2, height - 14, (width + w) / 2, height - 2, 0x90000000);
        g.drawCenteredString(font, c, width / 2, height - 12, ctrl.isEmpty() ? 0xC0FFC0 : 0xFFD040);
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        if (browser != null) browser.setFocus(false);
        PcCamera.exit(() -> minecraft.setScreen(null));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (!locked()) { browser.sendMousePress(bx(x), by(y), button); browser.setFocus(true); }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (!locked()) browser.sendMouseRelease(bx(x), by(y), button);
        return true;
    }

    @Override public void mouseMoved(double x, double y) { if (!locked()) browser.sendMouseMove(bx(x), by(y)); }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (!locked()) browser.sendMouseMove(bx(x), by(y));
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (!locked()) browser.sendMouseWheel(bx(x), by(y), sy, 0);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && (mods & GLFW.GLFW_MOD_SHIFT) != 0) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_F8) { PcClient.send(new PcPayloads.Control(pos, true)); return true; }
        if (!locked()) browser.sendKeyPress(key, scan, mods);
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (!locked()) browser.sendKeyRelease(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (c != 0 && !locked()) browser.sendKeyTyped(c, mods);
        return true;
    }

    @Override
    public void removed() {
        if (browser != null) browser.setFocus(false); // браузер не закрываем — ПК остаётся включённым
        PcCamera.cancel();                              // закрыли не через Shift+Esc (урон и т.п.) — сразу вернуть камеру
    }
}
