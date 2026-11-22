package dev.vintagepc.client;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.vintagepc.net.PcPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Монитор хоста. Весь ввод уходит в Chromium; Shift+Esc — выйти (обычный Esc нужен играм),
 * F8 — забрать управление у зрителя. Пока управляет другой игрок, локальный ввод игнорируется.
 */
final class PcScreen extends Screen implements PcScreenMarker {
    static final int MARGIN = 8;
    private final BlockPos pos;
    private final String url;
    private MCEFBrowser browser;

    PcScreen(BlockPos pos, String url) {
        super(Component.translatable("block.vintagepc.retro_pc"));
        this.pos = pos;
        this.url = url;
    }

    @Override public BlockPos pos() { return pos; }

    @Override
    protected void init() {
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

    private void resizeBrowser() {
        if (browser == null) return;
        int w = (int) ((width - 2 * MARGIN) * scale()), h = (int) ((height - 2 * MARGIN) * scale());
        if (w > 100 && h > 100) BrowserPool.resize(pos, browser, w, h);
    }

    private int bx(double x) { return (int) ((x - MARGIN) * scale()); }
    private int by(double y) { return (int) ((y - MARGIN) * scale()); }
    private boolean locked() { return browser == null || !PcClient.controllerOf(pos).isEmpty(); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0xFF2B2A26);
        g.fill(MARGIN - 2, MARGIN - 2, width - MARGIN + 2, height - MARGIN + 2, 0xFF000000);
        if (browser == null) {
            g.drawCenteredString(font, Component.translatable("vintagepc.booting"), width / 2, height / 2, 0xFFFFFF);
            if (BrowserPool.ready()) init();
            return;
        }
        drawTexture(browser.getRenderer().getTextureID(), MARGIN, MARGIN, width - MARGIN, height - MARGIN);

        int viewers = PcClient.viewersOf(pos);
        String ctrl = PcClient.controllerOf(pos);
        if (viewers > 0 || !ctrl.isEmpty()) {
            Component c = ctrl.isEmpty()
                    ? Component.translatable("vintagepc.host_status", viewers)
                    : Component.translatable("vintagepc.host_locked", ctrl);
            int w = font.width(c) + 8;
            g.fill(width - MARGIN - w, 0, width - MARGIN, MARGIN + 2, 0xC0000000);
            g.drawString(font, c, width - MARGIN - w + 4, 0, ctrl.isEmpty() ? 0x80FF80 : 0xFFD040);
        }
    }

    static void drawTexture(int tex, int x0, int y0, int x1, int y1) {
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buf.addVertex(x0, y1, 0).setUv(0, 1).setColor(255, 255, 255, 255);
        buf.addVertex(x1, y1, 0).setUv(1, 1).setColor(255, 255, 255, 255);
        buf.addVertex(x1, y0, 0).setUv(1, 0).setColor(255, 255, 255, 255);
        buf.addVertex(x0, y0, 0).setUv(0, 0).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

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

    @Override
    public void mouseMoved(double x, double y) {
        if (!locked()) browser.sendMouseMove(bx(x), by(y));
    }

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
    }
}
