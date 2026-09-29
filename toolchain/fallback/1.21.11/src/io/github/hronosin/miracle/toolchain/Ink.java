package io.github.hronosin.miracle.toolchain;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 1.21.11: text is drawn through GuiGraphics. */
final class Ink {

    static void text(Object graphics, Font font, Component text, int x, int y) {
        ((GuiGraphics) graphics).drawString(font, text, x, y, 0xFF404040, false);
    }
}
