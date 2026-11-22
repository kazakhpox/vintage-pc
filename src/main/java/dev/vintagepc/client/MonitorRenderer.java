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

/**
 * Изображение на экране монитора в мире. Светится (полная яркость), как настоящий ЭЛТ.
 * Область экрана совпадает с «стеклом» на текстуре retro_pc_front (пиксели 2..14 × 2..11).
 */
public final class MonitorRenderer implements BlockEntityRenderer<RetroPcBlockEntity> {
    private static final float X0 = 2 / 16f, X1 = 14 / 16f, Y_TOP = 1 - 2 / 16f, Y_BOTTOM = 1 - 11 / 16f, Z = -0.002f;

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(RetroPcBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        ResourceLocation tex = ScreenTextures.get(be.getBlockPos());
        if (tex == null) return;
        float rot = switch (be.getBlockState().getValue(RetroPcBlock.FACING)) {
            case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0;
        };
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-rot));
        pose.translate(-0.5, 0, -0.5);
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        int full = LightTexture.FULL_BRIGHT;
        // Лицевая грань — север (z = 0); смотрящему снаружи «лево» экрана — это +x.
        vc.addVertex(m, 1 - X0, Y_TOP, Z).setColor(255, 255, 255, 255).setUv(0, 0).setLight(full);
        vc.addVertex(m, 1 - X1, Y_TOP, Z).setColor(255, 255, 255, 255).setUv(1, 0).setLight(full);
        vc.addVertex(m, 1 - X1, Y_BOTTOM, Z).setColor(255, 255, 255, 255).setUv(1, 1).setLight(full);
        vc.addVertex(m, 1 - X0, Y_BOTTOM, Z).setColor(255, 255, 255, 255).setUv(0, 1).setLight(full);
        pose.popPose();
    }
}
