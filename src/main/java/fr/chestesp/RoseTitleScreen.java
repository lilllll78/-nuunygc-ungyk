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
        if (Config.sakuraDecor) SakuraDecor.corners(c, this.width, this.height);
        RoseLogo.draw(c, 10, 10, 3);
        c.drawText(this.textRenderer, "ROSECLIENT", 10 + RoseLogo.WIDTH * 3 + 8, 14,
                Ui.lerp(Ui.ACCENT2, 0xFFFFFFFF, Ui.pulse() * 0.4f), true);
        c.drawText(this.textRenderer, "minimal  -  pvp  -  build", 10 + RoseLogo.WIDTH * 3 + 8, 26, Ui.MUTED, true);
    }
}
