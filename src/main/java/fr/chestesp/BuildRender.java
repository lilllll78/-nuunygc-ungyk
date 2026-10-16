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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Affiche : boite du schema, blocs manquants (vert), blocs differents (rouge), selection (jaune). */
public final class BuildRender {

    private BuildRender() {}

    private static void add(List<float[]> out, Vec3d cam, double x1, double y1, double z1,
                            double x2, double y2, double z2, float r, float g, float b) {
        out.add(new float[]{(float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z),
                (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z), r, g, b});
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || ctx.matrixStack() == null) return;

        Vec3d cam = ctx.camera().getPos();
        List<float[]> boxes = new ArrayList<>();

        int[] sb = BuildState.schemBounds();
        if (BuildState.showGhost && sb != null) {
            add(boxes, cam, sb[0], sb[1], sb[2], sb[3] + 1, sb[4] + 1, sb[5] + 1, 1f, 1f, 1f);
            for (BlockPos p : BuildState.missing) {
                add(boxes, cam, p.getX() + 0.2, p.getY() + 0.2, p.getZ() + 0.2,
                        p.getX() + 0.8, p.getY() + 0.8, p.getZ() + 0.8, 0.2f, 1f, 0.3f);
            }
            for (BlockPos p : BuildState.wrong) {
                add(boxes, cam, p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, 1f, 0.2f, 0.2f);
            }
        }

        int[] sel = BuildState.selBounds();
        if (sel != null) {
            add(boxes, cam, sel[0], sel[1], sel[2], sel[3] + 1, sel[4] + 1, sel[5] + 1, 1f, 0.9f, 0.1f);
        } else {
            BlockPos one = BuildState.pos1 != null ? BuildState.pos1 : BuildState.pos2;
            if (one != null) {
                add(boxes, cam, one.getX(), one.getY(), one.getZ(), one.getX() + 1, one.getY() + 1, one.getZ() + 1,
                        1f, 0.9f, 0.1f);
            }
        }

        if (boxes.isEmpty()) return;

        Matrix4f mat = ctx.matrixStack().peek().getPositionMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (float[] b : boxes) {
            float x1 = b[0], y1 = b[1], z1 = b[2], x2 = b[3], y2 = b[4], z2 = b[5];
            float r = b[6], g = b[7], bl = b[8];
            // bas
            e(buf, mat, x1, y1, z1, x2, y1, z1, r, g, bl);
            e(buf, mat, x2, y1, z1, x2, y1, z2, r, g, bl);
            e(buf, mat, x2, y1, z2, x1, y1, z2, r, g, bl);
            e(buf, mat, x1, y1, z2, x1, y1, z1, r, g, bl);
            // haut
            e(buf, mat, x1, y2, z1, x2, y2, z1, r, g, bl);
            e(buf, mat, x2, y2, z1, x2, y2, z2, r, g, bl);
            e(buf, mat, x2, y2, z2, x1, y2, z2, r, g, bl);
            e(buf, mat, x1, y2, z2, x1, y2, z1, r, g, bl);
            // montants
            e(buf, mat, x1, y1, z1, x1, y2, z1, r, g, bl);
            e(buf, mat, x2, y1, z1, x2, y2, z1, r, g, bl);
            e(buf, mat, x2, y1, z2, x2, y2, z2, r, g, bl);
            e(buf, mat, x1, y1, z2, x1, y2, z2, r, g, bl);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void e(BufferBuilder buf, Matrix4f mat, float x1, float y1, float z1,
                          float x2, float y2, float z2, float r, float g, float b) {
        buf.vertex(mat, x1, y1, z1).color(r, g, b, 1f);
        buf.vertex(mat, x2, y2, z2).color(r, g, b, 1f);
    }
}
