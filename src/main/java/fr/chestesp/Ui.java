package fr.chestesp;

import net.minecraft.client.gui.DrawContext;

/** Couleurs (derivees du theme choisi) et petits outils de dessin. */
public final class Ui {
    public static int DIM = 0x99000000;
    public static int BORDER = 0xFF3B2A5E;
    public static int BG = 0xF2100C18;
    public static int SIDE = 0xFF0D0A14;
    public static int ROW = 0xFF1B1626;
    public static int ROW_HOVER = 0xFF261F36;
    public static int ACCENT = 0xFFA855F7;
    public static int ACCENT2 = 0xFFC084FC;
    public static int OFF = 0xFF3B3550;

    public static final int CYAN = 0xFF22D3EE;
    public static final int TEXT = 0xFFE9E4F5;
    public static final int MUTED = 0xFF8E88A6;
    public static final int GREEN = 0xFF4ADE80;
    public static final int RED = 0xFFF87171;

    private Ui() {}

    /** Recalcule toutes les couleurs a partir de Config.accent et Config.uiOpacity. */
    public static void applyTheme() {
        int rgb = Config.accent & 0xFFFFFF;
        ACCENT = 0xFF000000 | rgb;
        ACCENT2 = lerp(ACCENT, 0xFFFFFFFF, 0.35f);
        BORDER = lerp(ACCENT, 0xFF000000, 0.55f);
        int a = (int) (Math.max(40.0, Math.min(100.0, Config.uiOpacity)) / 100.0 * 255.0);
        BG = (a << 24) | (lerp(0xFF100C18, ACCENT, 0.04f) & 0xFFFFFF);
        SIDE = lerp(0xFF0B0910, ACCENT, 0.04f);
        ROW = lerp(0xFF17131E, ACCENT, 0.07f);
        ROW_HOVER = lerp(0xFF17131E, ACCENT, 0.18f);
        OFF = lerp(0xFF2E2B38, ACCENT, 0.12f);
    }

    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** 0..1 qui oscille doucement (halo). Fixe si le halo est desactive. */
    public static float pulse() {
        if (!Config.glow) return 0.3f;
        return (float) (Math.sin(System.currentTimeMillis() / 450.0) * 0.5 + 0.5);
    }

    // ------------------------------------------------------------ HSV

    /** h, s, v dans [0,1] -> 0xFFRRGGBB */
    public static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float c = v * s;
        float x = c * (1 - Math.abs((h * 6) % 2 - 1));
        float m = v - c;
        float r, g, b;
        int sector = (int) (h * 6);
        switch (sector) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /** 0xRRGGBB -> {h, s, v} */
    public static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = (((g - b) / d) % 6) / 6f;
        else if (max == g) h = (((b - r) / d) + 2) / 6f;
        else h = (((r - g) / d) + 4) / 6f;
        if (h < 0) h += 1f;
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    // ------------------------------------------------------------ dessin

    public static void window(DrawContext c, int x, int y, int w, int h) {
        c.fill(x - 1, y - 1, x + w + 1, y + h + 1, lerp(BORDER, ACCENT, pulse() * 0.5f));
        c.fill(x, y, x + w, y + h, BG);
    }

    public static void rowBg(DrawContext c, int x, int y, int w, int h, boolean hover, boolean selected) {
        c.fill(x, y, x + w, y + h, hover ? ROW_HOVER : ROW);
        if (selected) c.drawBorder(x, y, w, h, lerp(ACCENT, ACCENT2, pulse()));
    }
}
