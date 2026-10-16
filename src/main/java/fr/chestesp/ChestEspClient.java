package fr.chestesp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ChestEspClient implements ClientModInitializer {

    public static KeyBinding menuKey;
    public static KeyBinding pos1Key;
    public static KeyBinding pos2Key;
    public static KeyBinding zoomKey;
    private static int savedFov = -1;
    private static int ghostTimer = 0;

    /** Ancres détectées (rafraîchi toutes les 10 ticks). */
    public static volatile List<BlockPos> anchors = new ArrayList<>();

    private static final int CHUNK_RADIUS = 8;      // portée en chunks
    private static final double MAX_DIST = 128.0;   // portée en blocs
    private static int tickCounter = 0;
    private static int customTimer = 0;

    private record Target(BlockPos pos, float r, float g, float b) {}

    private static Target rgbTarget(BlockPos p, int rgb) {
        return new Target(p, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
    }

    @Override
    public void onInitializeClient() {
        Config.load();
        Ui.applyTheme();
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestesp.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.chestesp"));
        pos1Key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestesp.pos1", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.chestesp"));
        pos2Key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestesp.pos2", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.chestesp"));
        zoomKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chestesp.zoom", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, "category.chestesp"));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) return;
            if (Config.anchorFarm && tickCounter++ % 10 == 0) {
                scanAnchors(client);
            }
            if (Config.customEsp && customTimer++ % 20 == 0) {
                CustomEsp.scan(client);
            }
            AnchorFarm.tick(client);
            BuildBot.tick(client);
            AutoPickup.tick(client);
            if (Config.toggleSprint && client.currentScreen == null) {
                var pl = client.player;
                if (client.options.forwardKey.isPressed() && !pl.isSprinting() && !pl.isSneaking()
                        && !pl.horizontalCollision && !pl.isUsingItem()
                        && pl.getHungerManager().getFoodLevel() > 6) {
                    pl.setSprinting(true);
                }
            }
            if (ghostTimer++ % 10 == 0) BuildState.scanGhost(client.world, client.player);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Ecran titre RoseClient a la place de celui de Minecraft
            if (client.currentScreen instanceof TitleScreen && !(client.currentScreen instanceof RoseTitleScreen)) {
                client.setScreen(new RoseTitleScreen());
            }
            if (client.currentScreen instanceof GameMenuScreen && !(client.currentScreen instanceof RoseGameMenuScreen)) {
                client.setScreen(new RoseGameMenuScreen(true));
            }
            // Zoom (maintenir la touche)
            if (client.player != null) {
                if (Config.zoom && client.currentScreen == null && zoomKey.isPressed()) {
                    if (savedFov < 0) savedFov = client.options.getFov().getValue();
                    client.options.getFov().setValue(30);
                } else if (savedFov >= 0) {
                    client.options.getFov().setValue(savedFov);
                    savedFov = -1;
                }
            }
            while (pos1Key.wasPressed()) {
                if (client.currentScreen == null) BuildState.setCorner(client, 1);
            }
            while (pos2Key.wasPressed()) {
                if (client.currentScreen == null) BuildState.setCorner(client, 2);
            }
            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new EspMenuScreen());
                }
            }
        });

        WorldRenderEvents.LAST.register(ChestEspClient::render);
        WorldRenderEvents.LAST.register(BuildRender::render);
        WorldRenderEvents.LAST.register(PlayerEsp::render);
        HudRenderCallback.EVENT.register(RoseHud::render);
    }

    private static void scanAnchors(MinecraftClient mc) {
        ClientWorld world = mc.world;
        List<BlockPos> found = new ArrayList<>();
        Vec3d pp = mc.player.getPos();
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        int r = Math.min(24, mc.options.getViewDistance().getValue());
        double maxD = r * 16.0 + 16.0;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                if (chunk == null) continue;
                ChunkSection[] sections = chunk.getSectionArray();
                int startX = chunk.getPos().getStartX();
                int startZ = chunk.getPos().getStartZ();
                for (int i = 0; i < sections.length; i++) {
                    ChunkSection s = sections[i];
                    if (s == null || s.isEmpty()) continue;
                    if (!s.hasAny(st -> st.isOf(Blocks.RESPAWN_ANCHOR))) continue;
                    int baseY = world.getBottomY() + i * 16;
                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                if (s.getBlockState(lx, ly, lz).isOf(Blocks.RESPAWN_ANCHOR)) {
                                    BlockPos p = new BlockPos(startX + lx, baseY + ly, startZ + lz);
                                    if (p.toCenterPos().distanceTo(pp) <= maxD) found.add(p);
                                }
                            }
                        }
                    }
                }
            }
        }
        anchors = found;
    }

    private static void render(WorldRenderContext ctx) {
        if (!Config.chestEsp && !Config.customEsp) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;

        Camera cam = ctx.camera();
        Vec3d camPos = cam.getPos();

        List<Target> targets = new ArrayList<>();

        // 1) Coffres
        if (Config.chestEsp) {
            int pcx = mc.player.getChunkPos().x;
            int pcz = mc.player.getChunkPos().z;
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                    if (chunk == null) continue;
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos p = be.getPos();
                        if (p.toCenterPos().distanceTo(camPos) > Config.espRange) continue;
                        if (be instanceof TrappedChestBlockEntity) {
                            targets.add(new Target(p, 1f, 0.15f, 0.15f));      // rouge
                        } else if (be instanceof ChestBlockEntity) {
                            targets.add(rgbTarget(p, Config.chestColor));
                        } else if (be instanceof EnderChestBlockEntity) {
                            targets.add(new Target(p, 0.7f, 0.2f, 1f));        // violet
                        } else if (be instanceof BarrelBlockEntity) {
                            targets.add(new Target(p, 0.6f, 0.4f, 0.2f));      // marron
                        }
                    }
                }
            }
        }

        // 2) Custom ESP : blocs choisis dans le menu
        if (Config.customEsp) {
            int shown = 0;
            for (BlockPos p : CustomEsp.positions) {
                if (shown >= 3000) break;
                if (p.toCenterPos().distanceTo(camPos) > Config.espRange) continue;
                if (!CustomEsp.selected(world.getBlockState(p).getBlock())) continue;
                targets.add(rgbTarget(p, Config.customColor));
                shown++;
            }
        }
        if (targets.isEmpty()) return;

        // 3) Point de départ des lignes (relatif à la caméra)
        float sx, sy, sz;
        if (mc.options.getPerspective().isFirstPerson()) {
            Vector3f look = new Vector3f(0, 0, -1).rotate(cam.getRotation());
            sx = look.x * 0.4f; sy = look.y * 0.4f; sz = look.z * 0.4f;
        } else {
            Vec3d body = mc.player.getPos().add(0, mc.player.getHeight() * 0.6, 0).subtract(camPos);
            sx = (float) body.x; sy = (float) body.y; sz = (float) body.z;
        }

        if (ctx.matrixStack() == null) return;
        Matrix4f mat = ctx.matrixStack().peek().getPositionMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (Target t : targets) {
            float x1 = (float) (t.pos.getX() - camPos.x);
            float y1 = (float) (t.pos.getY() - camPos.y);
            float z1 = (float) (t.pos.getZ() - camPos.z);
            float x2 = x1 + 1, y2 = y1 + 1, z2 = z1 + 1;

            // Ligne joueur -> cible
            if (Config.tracers) {
                buf.vertex(mat, sx, sy, sz).color(t.r, t.g, t.b, 0.9f);
                buf.vertex(mat, x1 + 0.5f, y1 + 0.5f, z1 + 0.5f).color(t.r, t.g, t.b, 0.9f);
            }

            // Contour (12 arêtes)
            edge(buf, mat, x1, y1, z1, x2, y1, z1, t);
            edge(buf, mat, x2, y1, z1, x2, y1, z2, t);
            edge(buf, mat, x2, y1, z2, x1, y1, z2, t);
            edge(buf, mat, x1, y1, z2, x1, y1, z1, t);

            edge(buf, mat, x1, y2, z1, x2, y2, z1, t);
            edge(buf, mat, x2, y2, z1, x2, y2, z2, t);
            edge(buf, mat, x2, y2, z2, x1, y2, z2, t);
            edge(buf, mat, x1, y2, z2, x1, y2, z1, t);

            edge(buf, mat, x1, y1, z1, x1, y2, z1, t);
            edge(buf, mat, x2, y1, z1, x2, y2, z1, t);
            edge(buf, mat, x2, y1, z2, x2, y2, z2, t);
            edge(buf, mat, x1, y1, z2, x1, y2, z2, t);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void edge(BufferBuilder buf, Matrix4f mat,
                             float x1, float y1, float z1, float x2, float y2, float z2, Target t) {
        buf.vertex(mat, x1, y1, z1).color(t.r, t.g, t.b, 1f);
        buf.vertex(mat, x2, y2, z2).color(t.r, t.g, t.b, 1f);
    }
}
