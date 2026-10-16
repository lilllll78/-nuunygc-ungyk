package fr.chestesp;

import net.minecraft.client.gui.DrawContext;

/** Decor sakura en pixel art : lune, cerisiers en fleurs avec lianes, petales qui tombent. */
public final class SakuraDecor {

    // d = tronc / ombre, m = rose fonce, P = rose, p = rose clair, w = reflet
    private static final String[] TREE = {
            ".......dddddd...........",
            "....ddddPPPPPPdd........",
            "...dPPPPPpppPPPPdd......",
            "..dPPpppppwpppPPPPd.....",
            ".dPPpppwwppppppPPPPd....",
            ".dPPppppppppppPPPmmd....",
            "dPPPPpppppPPPPPPmmmd....",
            "dPPPPPPPPPPPPPPmmmdd....",
            ".dmPPPPPPPPPPmmmmdd.....",
            ".ddmmPPPPPmmmmmddd......",
            "..dddmmmmmmmdddd........",
            "....ddddddddd...........",
            ".......dd.dd............",
            "......dd...d............",
    };
    private static final int[] PETAL_COLORS = {0x99FF7FB0, 0x99E5508A, 0x99FFB3D1};

    private SakuraDecor() {}

    private static int color(char ch) {
        return switch (ch) {
            case 'd' -> 0xFF3A2540;
            case 'm' -> 0xFFB83A78;
            case 'P' -> 0xFFE5508A;
            case 'p' -> 0xFFFF7FB0;
            case 'w' -> 0xFFFFB3D1;
            default -> 0;
        };
    }

    public static void tree(DrawContext c, int x, int y, int scale) {
        for (int row = 0; row < TREE.length; row++) {
            String line = TREE[row];
            for (int col = 0; col < line.length(); col++) {
                int color = color(line.charAt(col));
                if (color == 0) continue;
                c.fill(x + col * scale, y + row * scale, x + (col + 1) * scale, y + (row + 1) * scale, color);
            }
        }
        // lianes avec petit losange dore au bout
        int[] cols = {5, 10, 15};
        int[] lens = {16, 26, 20};
        for (int i = 0; i < cols.length; i++) {
            int vx = x + cols[i] * scale + scale / 2;
            int vy = y + 10 * scale;
            int end = vy + lens[i] * scale / 2;
            c.fill(vx, vy, vx + Math.max(1, scale / 3), end, 0xFF3A2540);
            int g = Math.max(2, scale);
            c.fill(vx - g, end, vx + g, end + g, 0xFFF2B84B);
            c.fill(vx - g / 2, end - g / 2, vx + g / 2, end + g + g / 2, 0xFFF2B84B);
        }
    }

    private static void disc(DrawContext c, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy += 3) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            c.fill(cx - half, cy + dy, cx + half, cy + dy + 3, color);
        }
    }

    public static void moon(DrawContext c, int w, int h) {
        int r = (int) Math.min(h * 0.36, 150);
        int cx = w / 2, cy = h / 2 - 8;
        disc(c, cx, cy, r, 0x55C979B8);
        disc(c, cx - r / 5, cy - r / 6, (int) (r * 0.78), 0x44E7A6D8);
        disc(c, cx + r / 3, cy + r / 3, (int) (r * 0.45), 0x33A05A9C);
    }

    public static void petals(DrawContext c, int w, int h) {
        long t = System.currentTimeMillis();
        for (int i = 0; i < 30; i++) {
            double sx = ((i * 7919) % 1000) / 1000.0 * w;
            double speed = 0.018 + ((i * 31) % 7) * 0.006;
            double y = ((t * speed + i * 61) % (h + 20)) - 10;
            double x = sx + Math.sin(t / 900.0 + i) * 12;
            int size = 2 + (i % 3);
            c.fill((int) x, (int) y, (int) x + size, (int) y + size, PETAL_COLORS[i % 3]);
        }
    }

    /** Arbres aux coins + petales. */
    public static void corners(DrawContext c, int w, int h) {
        int s = Math.max(2, Math.min(4, w / 220));
        tree(c, w - 24 * s - 6, 4, s);
        tree(c, 4, h - 14 * s - 44, s);
        petals(c, w, h);
    }
}
