package fr.chestesp;

import net.minecraft.client.gui.DrawContext;

/** La rose du RoseClient, dessinee en pixel art (aucune texture necessaire). */
public final class RoseLogo {

    public static final int WIDTH = 13;
    public static final int HEIGHT = 19;

    private static final String[] GRID = {
            "....DDDDD....",
            "..DDrrrrrDD..",
            ".DrrRRRRRrrD.",
            ".DrRRDDDRRrD.",
            "DrRRDrrrDRRrD",
            "DrRDrRRRrDRrD",
            "DrRDrRDRrDRrD",
            ".DrRDrrrDRrD.",
            ".DrRRDDDRRrD.",
            "..DrRRRRRrD..",
            "...DDRRRDD...",
            ".....DRD.....",
            "......G......",
            "..g...G......",
            ".GGg..G...g..",
            ".GGGg.G..gGG.",
            "..gGGgGgGGG..",
            "....gGGGg....",
            ".......G.....",
    };

    private RoseLogo() {}

    private static int color(char ch) {
        return switch (ch) {
            case 'D' -> 0xFF9F1239;
            case 'R' -> 0xFFE11D48;
            case 'r' -> 0xFFFB7185;
            case 'G' -> 0xFF22C55E;
            case 'g' -> 0xFF15803D;
            default -> 0;
        };
    }

    public static void draw(DrawContext c, int x, int y, int scale) {
        for (int row = 0; row < GRID.length; row++) {
            String line = GRID[row];
            for (int col = 0; col < line.length(); col++) {
                int color = color(line.charAt(col));
                if (color == 0) continue;
                int px = x + col * scale, py = y + row * scale;
                c.fill(px, py, px + scale, py + scale, color);
            }
        }
    }
}
