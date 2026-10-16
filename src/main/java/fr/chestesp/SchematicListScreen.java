package fr.chestesp;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Liste les schemas du dossier .minecraft/schematics (meme style que le menu). */
public class SchematicListScreen extends Screen {

    private final Screen parent;
    private List<Path> files = new ArrayList<>();
    private int scroll = 0;
    private int wx, wy, ww, wh, ly, lh;

    public SchematicListScreen(Screen parent) {
        super(Text.literal("Choisir un schéma"));
        this.parent = parent;
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    @Override
    protected void init() {
        files = new ArrayList<>();
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(dir())) {
                s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".nbt");
                }).sorted().forEach(files::add);
            }
        } catch (IOException ignored) {
        }
        ww = Math.min(380, this.width - 16);
        wh = Math.min(300, this.height - 16);
        wx = (this.width - ww) / 2;
        wy = (this.height - wh) / 2;
        ly = wy + 40;
        lh = wh - 40 - 40;
    }

    private void clampScroll() {
        int max = Math.max(0, files.size() * 26 - lh);
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, this.width, this.height, Ui.DIM);
        Ui.window(c, wx, wy, ww, wh);
        TextRenderer tr = this.textRenderer;

        c.fillGradient(wx, wy, wx + ww, wy + 28, 0xFF1A1230, Ui.BG);
        c.fill(wx, wy, wx + ww, wy + 2, Ui.lerp(Ui.ACCENT, Ui.CYAN, Ui.pulse()));
        RoseLogo.draw(c, wx + 10, wy + 6, 1);
        c.drawText(tr, "CHOISIR UN SCHÉMA", wx + 30, wy + 10, Ui.ACCENT2, true);
        String cnt = files.size() + " fichier(s)";
        c.drawText(tr, cnt, wx + ww - 12 - tr.getWidth(cnt), wy + 10, Ui.MUTED, true);

        clampScroll();
        c.enableScissor(wx + 10, ly, wx + ww - 10, ly + lh);
        for (int i = 0; i < files.size(); i++) {
            int ry = ly + i * 26 - scroll;
            if (ry + 22 < ly || ry > ly + lh) continue;
            boolean hover = mx >= wx + 10 && mx <= wx + ww - 10 && my >= Math.max(ry, ly) && my <= Math.min(ry + 22, ly + lh);
            Ui.rowBg(c, wx + 10, ry, ww - 20, 22, hover, false);
            c.fill(wx + 10, ry, wx + 12, ry + 22, Ui.ACCENT);
            String name = files.get(i).getFileName().toString();
            if (tr.getWidth(name) > ww - 60) name = name.substring(0, Math.min(name.length(), 40)) + "...";
            c.drawText(tr, name, wx + 20, ry + 7, Ui.TEXT, true);
        }
        c.disableScissor();

        if (files.isEmpty()) {
            c.drawCenteredTextWithShadow(tr, Text.literal("Aucun schéma trouvé"), this.width / 2, ly + 20, Ui.RED);
            c.drawCenteredTextWithShadow(tr, Text.literal("Mets des .litematic / .schem / .nbt dans :"), this.width / 2, ly + 40, Ui.MUTED);
            c.drawCenteredTextWithShadow(tr, Text.literal(".minecraft/schematics"), this.width / 2, ly + 54, Ui.TEXT);
        }

        // bouton retour
        int bx = wx + ww / 2 - 50, by = wy + wh - 30;
        boolean bh = mx >= bx && mx <= bx + 100 && my >= by && my <= by + 22;
        Ui.rowBg(c, bx, by, 100, 22, bh, false);
        c.drawCenteredTextWithShadow(tr, Text.literal("Retour"), bx + 50, by + 7, Ui.TEXT);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        int bx = wx + ww / 2 - 50, by = wy + wh - 30;
        if (mx >= bx && mx <= bx + 100 && my >= by && my <= by + 22) {
            this.close();
            return true;
        }
        if (mx >= wx + 10 && mx <= wx + ww - 10 && my >= ly && my <= ly + lh) {
            int idx = (int) ((my - ly + scroll) / 26);
            int within = (int) ((my - ly + scroll) % 26);
            if (idx >= 0 && idx < files.size() && within <= 22) {
                load(files.get(idx));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= (int) (vertical * 26);
        clampScroll();
        return true;
    }

    private void load(Path f) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            Schematic s = Schematic.load(f);
            BuildState.setSchematic(s, f.getFileName().toString());
            if (BuildState.origin == null && mc.player != null) BuildState.origin = mc.player.getBlockPos();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("Schéma chargé : " + f.getFileName()
                        + " (" + s.sx + "x" + s.sy + "x" + s.sz + ")"), false);
            }
        } catch (Exception e) {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("Erreur de chargement : " + e.getMessage()), false);
            }
        }
        mc.setScreen(parent);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
