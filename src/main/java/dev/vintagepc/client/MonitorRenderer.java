package dev.vintagepc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.vintagepc.block.RetroPcBlock;
import dev.vintagepc.block.RetroPcBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import dev.vintagepc.VintagePC;
import dev.vintagepc.item.PcPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Изображение на экране монитора в мире. Светится (полная яркость), как настоящий ЭЛТ.
 * Область экрана совпадает с «стеклом» на текстуре retro_pc_front (пиксели 2..14 × 2..11).
 */
public final class MonitorRenderer implements BlockEntityRenderer<RetroPcBlockEntity> {
    private static final float X0 = 2 / 16f, X1 = 14 / 16f, Y_TOP = 1 - 2 / 16f, Y_BOTTOM = 1 - 11 / 16f, Z = -0.002f;

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {}

    /**
     * Где внутри корпуса стоит каждая деталь (для корпуса, смотрящего на север; открытый бок — запад, x=0).
     * pos — центр в пикселях блока, rx/ry/rz — поворот, scale — масштаб модели, cy — центр модели по высоте.
     */
    private record Slot(float x, float y, float z, float rx, float ry, float rz, float scale, float cy) {}
    private static final java.util.Map<PcPart, Slot> SLOTS = new java.util.EnumMap<>(java.util.Map.of(
            PcPart.MOTHERBOARD, new Slot(13.3f, 8f, 8f, 0, 0, 90, 0.75f, 1f),
            PcPart.CPU, new Slot(12.4f, 6.5f, 6.5f, 0, 0, 90, 0.4f, 1.2f),
            PcPart.RAM, new Slot(10.9f, 10f, 8f, 0, 90, 90, 0.62f, 3f),
            PcPart.GPU, new Slot(10.2f, 6.2f, 8.6f, 0, 90, 0, 0.52f, 2f),
            PcPart.HDD, new Slot(5.5f, 2.9f, 4.2f, 0, 90, 0, 0.36f, 1.5f),
            PcPart.PSU, new Slot(5.5f, 11.8f, 11.2f, 0, 0, 0, 0.36f, 4f),
            PcPart.WIFI, new Slot(10.5f, 3.2f, 11.5f, 0, 90, 0, 0.36f, 0.5f)));
    private static final float SLIDE_MS = 450;

    @Override
    public void render(RetroPcBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        float rot = switch (be.getBlockState().getValue(RetroPcBlock.FACING)) {
            case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0;
        };
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-rot));
        pose.translate(-0.5, 0, -0.5);
        renderParts(be, pose, buffers, light, overlay);
        ResourceLocation tex = ScreenTextures.get(be.getBlockPos());
        if (tex == null) { pose.popPose(); return; }
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        int full = LightTexture.FULL_BRIGHT;
        // Лицевая грань — север (z = 0); смотрящему снаружи «лево» экрана — это +x.
        // Обход против часовой стрелки, если смотреть снаружи на лицевую грань (иначе грань отсекается).
        vc.addVertex(m, 1 - X0, Y_TOP, Z).setColor(255, 255, 255, 255).setUv(0, 0).setLight(full);
        vc.addVertex(m, 1 - X0, Y_BOTTOM, Z).setColor(255, 255, 255, 255).setUv(0, 1).setLight(full);
        vc.addVertex(m, 1 - X1, Y_BOTTOM, Z).setColor(255, 255, 255, 255).setUv(1, 1).setLight(full);
        vc.addVertex(m, 1 - X1, Y_TOP, Z).setColor(255, 255, 255, 255).setUv(1, 0).setLight(full);
        pose.popPose();
    }

    /** Комплектующие внутри корпуса; только что вставленная деталь въезжает через открытый бок. */
    private static void renderParts(RetroPcBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        long now = System.currentTimeMillis();
        for (PcPart part : be.parts()) {
            Slot s = SLOTS.get(part);
            if (s == null) continue;
            Long at = be.insertedAt.get(part);
            float k = at == null ? 1 : Math.min(1, (now - at) / SLIDE_MS);
            float e = 1 - (1 - k) * (1 - k) * (1 - k);                       // ease-out
            ItemStack stack = new ItemStack(VintagePC.PART_ITEMS.get(part).get());
            pose.pushPose();
            pose.translate((s.x() - (1 - e) * 12) / 16f, s.y() / 16f, s.z() / 16f);
            if (s.rz() != 0) pose.mulPose(Axis.ZP.rotationDegrees(s.rz()));
            if (s.ry() != 0) pose.mulPose(Axis.YP.rotationDegrees(s.ry()));
            if (s.rx() != 0) pose.mulPose(Axis.XP.rotationDegrees(s.rx()));
            pose.scale(s.scale(), s.scale(), s.scale());
            pose.translate(0, (8 - s.cy()) / 16f, 0);                        // центр модели — в точку слота
            var model = mc.getItemRenderer().getModel(stack, be.getLevel(), null, 0);
            mc.getItemRenderer().render(stack, ItemDisplayContext.NONE, false, pose, buffers, light, overlay, model);
            pose.popPose();
        }
    }

    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(RetroPcBlockEntity be) {
        return new net.minecraft.world.phys.AABB(be.getBlockPos());
    }
}
