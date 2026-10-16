package fr.chestesp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

/** HUD minimaliste facon client PvP : FPS, CPS, coordonnees, touches, armure. */
public final class RoseHud {

    private static final Deque<Long> LMB = new ArrayDeque<>();
    private static final Deque<Long> RMB = new ArrayDeque<>();
    private static boolean lWas = false, rWas = false;

    private RoseHud() {}

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        pollMouse(mc);
        if (mc.currentScreen != null) return;

        TextRenderer tr = mc.textRenderer;
        ClientPlayerEntity p = mc.player;
        int x = 6, y = 6;

        if (Config.hudFps) y = chip(c, tr, x, y, "FPS " + mc.getCurrentFps());
        if (Config.hudCps) y = chip(c, tr, x, y, "CPS " + cps(LMB) + " | " + cps(RMB));
        if (Config.hudCoords) {
            y = chip(c, tr, x, y, "XYZ " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ());
            y = chip(c, tr, x, y, p.getHorizontalFacing().asString());
        }
        if (Config.hudKeystrokes) keystrokes(c, tr, mc, x, y + 3);
        if (Config.hudArmor) armor(c, tr, mc, p);
    }

    // ------------------------------------------------------------------ pieces

    private static int chip(DrawContext c, TextRenderer tr, int x, int y, String text) {
        int w = tr.getWidth(text) + 12;
        c.fill(x, y, x + w, y + 14, 0xB00D0A14);
        c.fill(x, y, x + 2, y + 14, Ui.ACCENT);
        c.drawText(tr, text, x + 7, y + 3, Ui.TEXT, true);
        return y + 17;
    }

    private static void key(DrawContext c, TextRenderer tr, int x, int y, int w, int h, String label, boolean down) {
        c.fill(x, y, x + w, y + h, down ? ((Ui.ACCENT & 0x00FFFFFF) | 0xE0000000) : 0xB00D0A14);
        c.drawBorder(x, y, w, h, down ? Ui.ACCENT2 : Ui.BORDER);
        c.drawText(tr, label, x + (w - tr.getWidth(label)) / 2, y + (h - 8) / 2, down ? 0xFFFFFFFF : Ui.MUTED, true);
    }

    private static void keystrokes(DrawContext c, TextRenderer tr, MinecraftClient mc, int x, int y) {
        int s = 22;
        key(c, tr, x + s + 2, y, s, s, "W", mc.options.forwardKey.isPressed());
        key(c, tr, x, y + s + 2, s, s, "A", mc.options.leftKey.isPressed());
        key(c, tr, x + s + 2, y + s + 2, s, s, "S", mc.options.backKey.isPressed());
        key(c, tr, x + 2 * (s + 2), y + s + 2, s, s, "D", mc.options.rightKey.isPressed());
        int w = (3 * s + 4 - 2) / 2;
        key(c, tr, x, y + 2 * (s + 2), w, 18, "LMB " + cps(LMB), lWas);
        key(c, tr, x + w + 2, y + 2 * (s + 2), w, 18, "RMB " + cps(RMB), rWas);
        key(c, tr, x, y + 2 * (s + 2) + 20, 3 * s + 4, 12, "SPACE", mc.options.jumpKey.isPressed());
    }

    private static void armor(DrawContext c, TextRenderer tr, MinecraftClient mc, ClientPlayerEntity p) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int x = mc.getWindow().getScaledWidth() - 26;
        int y = mc.getWindow().getScaledHeight() / 2 - 40;
        for (EquipmentSlot slot : slots) {
            ItemStack st = p.getEquippedStack(slot);
            c.fill(x - 3, y - 2, x + 19, y + 18, 0xB00D0A14);
            c.fill(x - 3, y - 2, x - 1, y + 18, Ui.ACCENT);
            if (!st.isEmpty()) {
                c.drawItem(st, x, y);
                c.drawStackOverlay(tr, st, x, y);
            }
            y += 22;
        }
    }

    // --------------------------------------------------------------------- CPS

    private static void pollMouse(MinecraftClient mc) {
        if (mc.currentScreen != null) {
            lWas = false;
            rWas = false;
            return;
        }
        long h = mc.getWindow().getHandle();
        boolean l = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean r = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        long now = System.currentTimeMillis();
        if (l && !lWas) LMB.addLast(now);
        if (r && !rWas) RMB.addLast(now);
        lWas = l;
        rWas = r;
    }

    private static int cps(Deque<Long> q) {
        long now = System.currentTimeMillis();
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
        return q.size();
    }
}
