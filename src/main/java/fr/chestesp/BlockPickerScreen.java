package fr.chestesp;

import net.minecraft.block.Block;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Choix des blocs du Custom ESP : tous les blocs, barre de recherche, clic pour (de)selectionner. */
public class BlockPickerScreen extends Screen {

    private static final int CELL = 22;
    private static List<Block> ALL = null;

    private final Screen parent;
    private TextFieldWidget search;
    private List<Block> filtered = new ArrayList<>();
    private int scroll = 0;
    private int wx, wy, ww, wh, gx, gy, gw, gh, cols;

    public BlockPickerScreen(Screen parent) {
        super(Text.literal("Custom ESP"));
        this.parent = parent;
    }

    private static List<Block> all() {
        if (ALL == null) {
            List<Block> list = new ArrayList<>();
            for (Block b : Registries.BLOCK) {
                if (b.asItem() == Items.AIR) continue;
                list.add(b);
            }
            list.sort(Comparator.comparing(b -> b.getName().getString().toLowerCase(Locale.ROOT)));
            ALL = list;
        }
        return ALL;
    }

    @Override
    protected void init() {
        ww = Math.min(450, this.width - 16);
        wh = Math.min(330, this.height - 16);
        wx = (this.width - ww) / 2;
        wy = (this.height - wh) / 2;
        gx = wx + 10;
        gy = wy + 62;
        gw = ww - 20;
        gh = wh - 62 - 36;
        cols = Math.max(1, gw / CELL);

        String previous = search != null ? search.getText() : "";
        search = new TextFieldWidget(this.textRenderer, wx + 10, wy + 36, ww - 20, 18, Text.literal("Recherche"));
        search.setPlaceholder(Text.literal("Rechercher un bloc..."));
        search.setMaxLength(60);
        search.setText(previous);
        search.setChangedListener(s -> { scroll = 0; applyFilter(); });
        this.addDrawableChild(search);
        this.setInitialFocus(search);
        applyFilter();
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Block> out = new ArrayList<>();
        for (Block b : all()) {
            if (q.isEmpty()
                    || b.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || Registries.BLOCK.getId(b).getPath().contains(q.replace(' ', '_'))) {
                out.add(b);
            }
        }
        filtered = out;
    }

    private int totalRows() {
        return (filtered.size() + cols - 1) / cols;
    }

    private void clampScroll() {
        int max = Math.max(0, totalRows() * CELL - gh);
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        TextRenderer tr = this.textRenderer;
        c.fill(0, 0, this.width, this.height, Ui.DIM);
        Ui.window(c, wx, wy, ww, wh);

        c.fillGradient(wx, wy, wx + ww, wy + 28, Ui.lerp(Ui.BG | 0xFF000000, Ui.ACCENT, 0.18f), Ui.BG);
        c.fill(wx, wy, wx + ww, wy + 2, Ui.lerp(Ui.ACCENT, Ui.CYAN, Ui.pulse()));
        RoseLogo.draw(c, wx + 10, wy + 6, 1);
        c.drawText(tr, "CUSTOM ESP : CHOISIR LES BLOCS", wx + 30, wy + 10, Ui.ACCENT2, true);
        String cnt = CustomEsp.count() + " choisi(s)";
        c.drawText(tr, cnt, wx + ww - 12 - tr.getWidth(cnt), wy + 10, Ui.MUTED, true);

        search.render(c, mx, my, delta);

        // grille
        clampScroll();
        Block hovered = null;
        c.enableScissor(gx, gy, gx + gw, gy + gh);
        int first = Math.max(0, scroll / CELL) * cols;
        for (int i = first; i < filtered.size(); i++) {
            int row = i / cols, col = i % cols;
            int x = gx + col * CELL, y = gy + row * CELL - scroll;
            if (y > gy + gh) break;
            if (y + CELL < gy) continue;
            Block b = filtered.get(i);
            boolean sel = CustomEsp.selected(b);
            boolean hover = mx >= x && mx < x + CELL && my >= Math.max(y, gy) && my < Math.min(y + CELL, gy + gh);
            if (hover) hovered = b;
            c.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, sel ? ((Ui.ACCENT & 0x00FFFFFF) | 0x55000000) : (hover ? Ui.ROW_HOVER : Ui.ROW));
            if (sel) c.drawBorder(x + 1, y + 1, CELL - 2, CELL - 2, Ui.ACCENT);
            c.drawItem(new ItemStack(b), x + 3, y + 3);
        }
        c.disableScissor();

        if (filtered.isEmpty()) {
            c.drawCenteredTextWithShadow(tr, Text.literal("Aucun bloc ne correspond"), this.width / 2, gy + 20, Ui.MUTED);
        }

        // barre de defilement
        int total = totalRows() * CELL;
        if (total > gh) {
            int barH = Math.max(20, gh * gh / total);
            int barY = gy + (int) ((long) (gh - barH) * scroll / Math.max(1, total - gh));
            c.fill(gx + gw + 2, gy, gx + gw + 4, gy + gh, Ui.OFF);
            c.fill(gx + gw + 2, barY, gx + gw + 4, barY + barH, Ui.ACCENT);
        }

        // boutons du bas
        int by = wy + wh - 28;
        boolean clearHover = mx >= wx + 10 && mx <= wx + 130 && my >= by && my <= by + 22;
        Ui.rowBg(c, wx + 10, by, 120, 22, clearHover, false);
        c.fill(wx + 10, by, wx + 12, by + 22, Ui.RED);
        c.drawText(tr, "Tout effacer", wx + 20, by + 7, Ui.TEXT, true);

        boolean doneHover = mx >= wx + ww - 130 && mx <= wx + ww - 10 && my >= by && my <= by + 22;
        Ui.rowBg(c, wx + ww - 130, by, 120, 22, doneHover, false);
        c.fill(wx + ww - 130, by, wx + ww - 128, by + 22, Ui.GREEN);
        c.drawText(tr, "Terminé", wx + ww - 120, by + 7, Ui.TEXT, true);

        String hint = "Clic : choisir / retirer un bloc";
        c.drawText(tr, hint, wx + ww / 2 - tr.getWidth(hint) / 2, by + 7, Ui.MUTED, true);

        if (hovered != null) {
            c.drawTooltip(tr, Text.literal(hovered.getName().getString() + "  (" + Registries.BLOCK.getId(hovered) + ")"), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int by = wy + wh - 28;
            if (mx >= wx + 10 && mx <= wx + 130 && my >= by && my <= by + 22) {
                CustomEsp.clear();
                return true;
            }
            if (mx >= wx + ww - 130 && mx <= wx + ww - 10 && my >= by && my <= by + 22) {
                this.close();
                return true;
            }
            if (mx >= gx && mx < gx + gw && my >= gy && my < gy + gh) {
                int col = (int) ((mx - gx) / CELL);
                int row = (int) ((my - gy + scroll) / CELL);
                int idx = row * cols + col;
                if (col < cols && idx >= 0 && idx < filtered.size()) {
                    CustomEsp.toggle(filtered.get(idx));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= (int) (vertical * CELL);
        clampScroll();
        return true;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public void removed() {
        Config.save();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
