package dev.vintagepc.client;

import dev.vintagepc.block.RetroPcBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * «Подсесть к компьютеру»: камера плавно подлетает к монитору и смотрит прямо в экран,
 * а сам интерфейс ПК рисуется на блоке монитора в мире — вокруг видно всё, рамка — корпус ЭЛТ.
 *
 * Камера ставится на рассчитанное расстояние с фиксированным FOV, поэтому прямоугольник экрана
 * в окне известен точно ({@link #screenRect}) — по нему мышь переводится в координаты браузера.
 */
final class PcCamera {
    static final float FOV = 60f;
    /** Экран монитора на блоке: 12×9 пикселей текстуры = ровно 4:3 (см. MonitorRenderer). */
    static final double SCREEN_W = 12 / 16.0, SCREEN_H = 9 / 16.0, CENTER_Y = 1 - (2 + 11) / 2.0 / 16.0; // строки 2..11 текстуры считаются сверху
    private static final int ENTER_TICKS = 14, EXIT_TICKS = 10;

    private static Entity cam;
    private static BlockPos pos;
    private static Vec3 fromP, toP;
    private static float fromYaw, fromPitch, toYaw, toPitch;
    private static int t, dur, dir;          // dir: +1 подлетаем, -1 улетаем, 0 на месте
    private static Runnable onExit;
    private static boolean prevHideGui;
    private static double fovMix;            // 0 — FOV игрока, 1 — наш фиксированный

    static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> tick());
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeFov e) -> {
            if (cam != null && e.usedConfiguredFov()) e.setFOV(Mth.lerp(fovMix, e.getFOV(), FOV));
        });
    }

    static boolean active() { return cam != null; }
    static boolean settled() { return cam != null && dir == 0; }

    private static double lastDistance = 0.6;

    /**
     * Дистанция от экрана, при которой он занимает ~80% высоты окна и влезает по ширине.
     * Свёрнутое окно имеет размер 0×0 — тогда берём последнее нормальное значение
     * (иначе деление на ноль уводило камеру в бесконечность и экран переставал откликаться).
     */
    private static double distance() {
        var w = Minecraft.getInstance().getWindow();
        if (w.getWidth() <= 0 || w.getHeight() <= 0) return lastDistance;
        double tan = Math.tan(Math.toRadians(FOV / 2)), aspect = (double) w.getWidth() / w.getHeight();
        double d = Math.max(SCREEN_H / (2 * 0.80 * tan), SCREEN_W / (2 * 0.92 * tan * aspect));
        if (Double.isFinite(d) && d > 0) lastDistance = d;
        return lastDistance;
    }

    /** Окно изменило размер: сразу переставить камеру на новую дистанцию, без повторного «подлёта». */
    static void retarget() {
        if (cam == null || pos == null || dir != 0) return;
        Minecraft mc = Minecraft.getInstance();
        var state = mc.level == null ? null : mc.level.getBlockState(pos);
        Direction facing = state != null && state.getBlock() instanceof RetroPcBlock ? state.getValue(RetroPcBlock.FACING) : Direction.NORTH;
        Vec3 screen = Vec3.atBottomCenterOf(pos).add(0, CENTER_Y, 0).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.5 + 0.002));
        toP = screen.add(Vec3.atLowerCornerOf(facing.getNormal()).scale(distance()));
        place(toP, toYaw, toPitch, true);
    }

    /** Прямоугольник экрана в GUI-координатах: {x, y, w, h}. */
    static double[] screenRect(int guiW, int guiH) {
        double tan = Math.tan(Math.toRadians(FOV / 2));
        double h = guiH * SCREEN_H / (2 * distance() * tan), w = h * SCREEN_W / SCREEN_H;
        return new double[]{(guiW - w) / 2, (guiH - h) / 2, w, h};
    }

    static void enter(BlockPos at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var state = mc.level.getBlockState(at);
        Direction facing = state.getBlock() instanceof RetroPcBlock ? state.getValue(RetroPcBlock.FACING) : Direction.NORTH;
        Vec3 screen = Vec3.atBottomCenterOf(at).add(0, CENTER_Y, 0).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.5 + 0.002));
        toP = screen.add(Vec3.atLowerCornerOf(facing.getNormal()).scale(distance()));
        toYaw = facing.getOpposite().toYRot();
        toPitch = 0;
        if (cam == null) {
            fromP = mc.player.getEyePosition(1);
            fromYaw = mc.player.getYRot();
            fromPitch = mc.player.getXRot();
            cam = EntityType.MARKER.create(mc.level);
            if (cam == null) return;
            place(fromP, fromYaw, fromPitch, true);
            prevHideGui = mc.options.hideGui;
            mc.options.hideGui = true;
            mc.setCameraEntity(cam);
            fovMix = 0;
        }
        else { fromP = cam.position(); fromYaw = cam.getYRot(); fromPitch = cam.getXRot(); } // перенацеливание (resize)
        pos = at;
        t = 0; dur = ENTER_TICKS; dir = 1;
    }

    /** Плавно вернуть камеру игроку; done вызывается по окончании. */
    static void exit(Runnable done) {
        if (cam == null) { if (done != null) done.run(); return; }
        Minecraft mc = Minecraft.getInstance();
        fromP = cam.position(); fromYaw = cam.getYRot(); fromPitch = cam.getXRot();
        toP = mc.player != null ? mc.player.getEyePosition(1) : fromP;
        toYaw = mc.player != null ? mc.player.getYRot() : fromYaw;
        toPitch = mc.player != null ? mc.player.getXRot() : fromPitch;
        onExit = done; t = 0; dur = EXIT_TICKS; dir = -1;
    }

    /** Мгновенно вернуть камеру (урон, смерть, выход с сервера). */
    static void cancel() {
        Minecraft mc = Minecraft.getInstance();
        if (cam == null) return;
        cam = null; dir = 0; fovMix = 0;
        mc.options.hideGui = prevHideGui;
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    private static void tick() {
        if (cam == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || cam.level() != mc.level) { cancel(); return; }
        if (dir == 0) { place(toP, toYaw, toPitch, false); return; }
        t++;
        double k = Math.min(1, t / (double) dur);
        double e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;   // ease-in-out
        Vec3 p = fromP.lerp(toP, e);
        float yaw = fromYaw + Mth.wrapDegrees(toYaw - fromYaw) * (float) e;
        float pitch = Mth.lerp((float) e, fromPitch, toPitch);
        fovMix = dir > 0 ? e : 1 - e;
        place(p, yaw, pitch, false);
        if (k >= 1) {
            if (dir > 0) dir = 0;
            else { Runnable r = onExit; onExit = null; cancel(); if (r != null) r.run(); }
        }
    }

    private static void place(Vec3 p, float yaw, float pitch, boolean snap) {
        if (snap) { cam.xo = p.x; cam.yo = p.y; cam.zo = p.z; cam.yRotO = yaw; cam.xRotO = pitch; }
        else { cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ(); cam.yRotO = cam.getYRot(); cam.xRotO = cam.getXRot(); }
        cam.setPos(p.x, p.y, p.z);
        cam.setYRot(yaw);
        cam.setXRot(pitch);
    }

    static BlockPos pos() { return pos; }

    private PcCamera() {}
}
