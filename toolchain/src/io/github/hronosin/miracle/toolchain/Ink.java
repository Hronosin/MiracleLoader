package io.github.hronosin.miracle.toolchain;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Text on a screen, in vanilla's label grey. 26.x draws through GuiGraphicsExtractor; 1.21.11 has a fallback. */
final class Ink {

    static final int LABEL_COLOR = 0xFF404040;

    private Ink() {
    }

    static void text(Object graphics, Font font, Component text, int x, int y) {
        ((GuiGraphicsExtractor) graphics).text(font, text, x, y, LABEL_COLOR, false);
    }
}
