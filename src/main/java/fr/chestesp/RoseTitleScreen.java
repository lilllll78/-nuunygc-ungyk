package fr.chestesp;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Ecran titre de Minecraft (fond et boutons d'origine) + la rose et l'acces au menu RoseClient. */
public class RoseTitleScreen extends TitleScreen {

    public RoseTitleScreen() {
        super(false);
    }

    @Override
    protected void init() {
        super.init();
        this.addDrawableChild(ButtonWidget.builder(Text.literal("RoseClient"),
                b -> this.client.setScreen(new EspMenuScreen(this)))
                .dimensions(6, this.height - 26, 90, 20).build());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        if (Config.sakuraDecor) SakuraDecor.petals(c, this.width, this.height);
    }
}
