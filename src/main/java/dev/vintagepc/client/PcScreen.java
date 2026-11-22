package dev.vintagepc.client;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Экран монитора. Весь ввод уходит в Chromium; выход — Shift+Esc
 * (обычный Esc нужен играм и самой оболочке).
 */
final class PcScreen extends Screen {
    private static final int MARGIN = 8; // рамка «монитора»
    private final BlockPos pos;
    private final String url;
    private MCEFBrowser browser;

    PcScreen(BlockPos pos, String url) {
        super(Component.translatable("block.vintagepc.retro_pc"));
        this.pos = pos;
        this.url = url;
    }

    @Override
    protected void init() {
        if (!BrowserPool.ready()) return;
        if (browser == null) browser = BrowserPool.get(pos, url);
        resizeBrowser();
        browser.setFocus(true);
    }

    @Override
    public void resize(net.minecraft.client.Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        resizeBrowser();
    }

    private double scale() { return minecraft.getWindow().getGuiScale(); }

    private void resizeBrowser() {
        if (browser == null) return;
        int w = (int) ((width - 2 * MARGIN) * scale()), h = (int) ((height - 2 * MARGIN) * scale());
        if (w > 100 && h > 100) browser.resize(w, h);
    }

    private int bx(double x) { return (int) ((x - MARGIN) * scale()); }
    private int by(double y) { return (int) ((y - MARGIN) * scale()); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0xFF2B2A26);                       // корпус
        g.fill(MARGIN - 2, MARGIN - 2, width - MARGIN + 2, height - MARGIN + 2, 0xFF000000);
        if (browser == null) {
            g.drawCenteredString(font, Component.translatable("vintagepc.booting"), width / 2, height / 2, 0xFFFFFF);
            if (BrowserPool.ready()) init();
            return;
        }
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, browser.getRenderer().getTextureID());
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int x0 = MARGIN, y0 = MARGIN, x1 = width - MARGIN, y1 = height - MARGIN;
        buf.addVertex(x0, y1, 0).setUv(0, 1).setColor(255, 255, 255, 255);
        buf.addVertex(x1, y1, 0).setUv(1, 1).setColor(255, 255, 255, 255);
        buf.addVertex(x1, y0, 0).setUv(1, 0).setColor(255, 255, 255, 255);
        buf.addVertex(x0, y0, 0).setUv(0, 0).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {}
    @Override public boolean isPauseScreen() { return false; } // в мультиплеере мир всё равно не на паузе
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (browser != null) { browser.sendMousePress(bx(x), by(y), button); browser.setFocus(true); }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (browser != null) browser.sendMouseRelease(bx(x), by(y), button);
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (browser != null) browser.sendMouseMove(bx(x), by(y));
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (browser != null) browser.sendMouseMove(bx(x), by(y));
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (browser != null) browser.sendMouseWheel(bx(x), by(y), sy, 0);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && (mods & GLFW.GLFW_MOD_SHIFT) != 0) { onClose(); return true; }
        if (browser != null) browser.sendKeyPress(key, scan, mods);
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (browser != null) browser.sendKeyRelease(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (c != 0 && browser != null) browser.sendKeyTyped(c, mods);
        return true;
    }

    @Override
    public void removed() {
        if (browser != null) browser.setFocus(false); // браузер не закрываем — ПК остаётся включённым
    }
}
