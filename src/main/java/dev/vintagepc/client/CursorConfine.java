package dev.vintagepc.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Курсор за компьютером не покидает экран монитора:
 *  - режим GLFW_CURSOR_CAPTURED не даёт системному курсору уйти за окно игры
 *    (раньше при запущенной игре мышь «уезжала» на рабочий стол Windows);
 *  - внутри окна курсор возвращается к краю экрана монитора, если вышел за него.
 */
final class CursorConfine {
    /** Держать курсор в окне (вызывается каждый кадр, пока игрок за ПК и камера на месте). */
    static void capture() {
        Minecraft mc = Minecraft.getInstance();
        long win = mc.getWindow().getWindow();
        if (!mc.isWindowActive()) return;
        if (GLFW.glfwGetInputMode(win, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_CAPTURED)
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_CAPTURED);
    }

    /**
     * Если курсор (в GUI-координатах) вышел за прямоугольник экрана — вернуть его на край.
     * @return {x, y} — координаты внутри экрана, которые и нужно передать браузеру
     */
    static double[] clamp(double x, double y, double[] r) {
        double cx = Math.max(r[0], Math.min(r[0] + r[2] - 0.5, x));
        double cy = Math.max(r[1], Math.min(r[1] + r[3] - 0.5, y));
        if (cx != x || cy != y) {
            Minecraft mc = Minecraft.getInstance();
            var w = mc.getWindow();
            double kx = (double) w.getScreenWidth() / w.getGuiScaledWidth(), ky = (double) w.getScreenHeight() / w.getGuiScaledHeight();
            GLFW.glfwSetCursorPos(w.getWindow(), cx * kx, cy * ky);
        }
        return new double[]{cx, cy};
    }

    private CursorConfine() {}
}
