package fr.chestesp;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Menu pause de Minecraft + acces au menu RoseClient. */
public class RoseGameMenuScreen extends GameMenuScreen {

    public RoseGameMenuScreen(boolean showMenu) {
        super(showMenu);
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(ButtonWidget.builder(Text.literal("RoseClient"),
                b -> this.client.setScreen(new EspMenuScreen(this)))
                .dimensions(6, this.height - 26, 90, 20).build());
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        super.renderBackground(c, mx, my, delta);
        if (Config.sakuraDecor) {
            SakuraDecor.moon(c, this.width, this.height);
            SakuraDecor.corners(c, this.width, this.height);
        }
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
    }
}
