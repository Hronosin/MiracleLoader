package io.github.hronosin.miracle.toolchain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** 1.21.11: setScreen is on Minecraft, under an obfuscated name the bake translates. */
final class Seer {

    static void display(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }
}
