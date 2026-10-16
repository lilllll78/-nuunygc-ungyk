package fr.chestesp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Contour des autres joueurs a travers les blocs (+ ligne vers eux). */
public final class PlayerEsp {

    private static final double MAX_DIST = 128.0;

    private PlayerEsp() {}

    public static void render(WorldRenderContext ctx) {
        if (!Config.playerEsp) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || ctx.matrixStack() == null) return;

        float td = ctx.tickCounter().getTickDelta(false);
        Vec3d cam = ctx.camera().getPos();

        List<float[]> boxes = new ArrayList<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            Vec3d lerped = p.getLerpedPos(td);
            if (lerped.distanceTo(cam) > Config.espRange) continue;
            Box b = p.getBoundingBox().offset(lerped.subtract(p.getPos()));
            int col = Config.playerColor;
            float r = ((col >> 16) & 255) / 255f, g = ((col >> 8) & 255) / 255f, bl = (col & 255) / 255f;
            if (p.isSneaking()) { // accroupi : version plus claire
                r = r * 0.5f + 0.5f;
                g = g * 0.5f + 0.5f;
                bl = bl * 0.5f + 0.5f;
            }
            boxes.add(new float[]{
                    (float) (b.minX - cam.x), (float) (b.minY - cam.y), (float) (b.minZ - cam.z),
                    (float) (b.maxX - cam.x), (float) (b.maxY - cam.y), (float) (b.maxZ - cam.z), r, g, bl});
        }
        if (boxes.isEmpty()) return;

        float sx, sy, sz;
        if (mc.options.getPerspective().isFirstPerson()) {
            Vector3f look = new Vector3f(0, 0, -1).rotate(ctx.camera().getRotation());
            sx = look.x * 0.4f; sy = look.y * 0.4f; sz = look.z * 0.4f;
        } else {
            Vec3d body = mc.player.getPos().add(0, mc.player.getHeight() * 0.6, 0).subtract(cam);
            sx = (float) body.x; sy = (float) body.y; sz = (float) body.z;
        }

        Matrix4f mat = ctx.matrixStack().peek().getPositionMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (float[] b : boxes) {
            float x1 = b[0], y1 = b[1], z1 = b[2], x2 = b[3], y2 = b[4], z2 = b[5];
            float r = b[6], g = b[7], bl = b[8];

            if (Config.tracers) {
                v(buf, mat, sx, sy, sz, r, g, bl);
                v(buf, mat, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2, r, g, bl);
            }
            e(buf, mat, x1, y1, z1, x2, y1, z1, r, g, bl);
            e(buf, mat, x2, y1, z1, x2, y1, z2, r, g, bl);
            e(buf, mat, x2, y1, z2, x1, y1, z2, r, g, bl);
            e(buf, mat, x1, y1, z2, x1, y1, z1, r, g, bl);
            e(buf, mat, x1, y2, z1, x2, y2, z1, r, g, bl);
            e(buf, mat, x2, y2, z1, x2, y2, z2, r, g, bl);
            e(buf, mat, x2, y2, z2, x1, y2, z2, r, g, bl);
            e(buf, mat, x1, y2, z2, x1, y2, z1, r, g, bl);
            e(buf, mat, x1, y1, z1, x1, y2, z1, r, g, bl);
            e(buf, mat, x2, y1, z1, x2, y2, z1, r, g, bl);
            e(buf, mat, x2, y1, z2, x2, y2, z2, r, g, bl);
            e(buf, mat, x1, y1, z2, x1, y2, z2, r, g, bl);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void v(BufferBuilder buf, Matrix4f mat, float x, float y, float z, float r, float g, float b) {
        buf.vertex(mat, x, y, z).color(r, g, b, 0.9f);
    }

    private static void e(BufferBuilder buf, Matrix4f mat, float x1, float y1, float z1,
                          float x2, float y2, float z2, float r, float g, float b) {
        buf.vertex(mat, x1, y1, z1).color(r, g, b, 1f);
        buf.vertex(mat, x2, y2, z2).color(r, g, b, 1f);
    }
}
