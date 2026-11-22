package dev.vintagepc.client;

import dev.vintagepc.net.PcPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;


/**
 * Зритель чужого ПК (hotseat): видит трансляцию экрана хоста; F8 — взять/отдать управление,
 * тогда мышь и клавиатура уходят на ПК хоста. MCEF зрителю не нужен.
 */
final class ViewerScreen extends Screen implements PcScreenMarker {

    private final BlockPos pos;
    private final String hostName;
    private String controller = "";
    private int viewers;
    private long lastMove;

    ViewerScreen(BlockPos pos, String hostName) {
        super(Component.translatable("block.vintagepc.retro_pc"));
        this.pos = pos;
        this.hostName = hostName;
    }

    @Override public BlockPos pos() { return pos; }

    void setState(String controller, int viewers) { this.controller = controller; this.viewers = viewers; }

    private boolean inControl() {
        return minecraft != null && minecraft.player != null && controller.equals(minecraft.player.getGameProfile().getName());
    }

    @Override
    protected void init() { if (!closing) { if (!PcCamera.active() || !pos.equals(PcCamera.pos())) PcCamera.enter(pos); else PcCamera.retarget(); } }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (!PcCamera.settled()) return;
        if (inControl() && !closing) CursorConfine.capture();
        Component status = ScreenTextures.get(pos) == null ? Component.translatable("vintagepc.viewer_wait", hostName)
                : inControl() ? Component.translatable("vintagepc.viewer_control", hostName)
                : Component.translatable("vintagepc.viewer_watch", hostName, controller.isEmpty() ? hostName : controller);
        int w = font.width(status) + 10;
        g.fill((width - w) / 2, height - 14, (width + w) / 2, height - 2, 0x90000000);
        g.drawCenteredString(font, status, width / 2, height - 12, inControl() ? 0x80FF80 : 0xFFFFFF);
    }

    private boolean closing;

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        PcCamera.exit(() -> minecraft.setScreen(null));
    }

    private float nx(double x) { double[] r = PcCamera.screenRect(width, height); return (float) Math.max(0, Math.min(1, (x - r[0]) / r[2])); }
    private float ny(double y) { double[] r = PcCamera.screenRect(width, height); return (float) Math.max(0, Math.min(1, (y - r[1]) / r[3])); }

    private void input(int kind, int a, int b, int c, double x, double y) {
        if (inControl() && PcCamera.settled() && !closing) PcClient.send(new PcPayloads.Input(pos, kind, a, b, c, nx(x), ny(y)));
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override public boolean mouseClicked(double x, double y, int btn) { input(3, btn, 0, 0, x, y); return true; }
    @Override public boolean mouseReleased(double x, double y, int btn) { input(4, btn, 0, 0, x, y); return true; }
    @Override public boolean mouseDragged(double x, double y, int btn, double dx, double dy) { mouseMoved(x, y); return true; }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) { input(6, (int) (sy * 100), 0, 0, x, y); return true; }

    @Override
    public void mouseMoved(double x, double y) {
        if (inControl() && PcCamera.settled()) { double[] c = CursorConfine.clamp(x, y, PcCamera.screenRect(width, height)); x = c[0]; y = c[1]; }
        long now = System.currentTimeMillis();
        if (now - lastMove < 33) return; // не чаще 30 раз в секунду
        lastMove = now;
        input(5, 0, 0, 0, x, y);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && (mods & GLFW.GLFW_MOD_SHIFT) != 0) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_F8) { PcClient.send(new PcPayloads.Control(pos, !inControl())); return true; }
        input(0, key, scan, mods, 0, 0);
        return true;
    }

    @Override public boolean keyReleased(int key, int scan, int mods) { input(1, key, scan, mods, 0, 0); return true; }
    @Override public boolean charTyped(char ch, int mods) { input(2, ch, 0, mods, 0, 0); return true; }

    @Override
    public void removed() {
        PcCamera.cancel();
        BrowserPool.resetCursor();
        PcClient.send(new PcPayloads.Leave(pos));
    }
}
