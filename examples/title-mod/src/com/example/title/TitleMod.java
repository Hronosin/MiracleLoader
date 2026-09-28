package com.example.title;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/**
 * First mod for the real game. Compiles against the loader alone — it never imports a
 * Minecraft class, it only names one. Watch the game log (Prism: the instance's log window).
 */
public final class TitleMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.client.gui.screens.TitleScreen")
                .method("init")
                .atHead(self -> System.out.println("[title-mod] Title screen opened. Чудо свершилось. (" + self.getClass().getName() + ")"));
    }

    @Override
    public void onLaunch() {
        System.out.println("[title-mod] Loaded. If you see the title-screen line later, RGCT works on real Minecraft.");
    }
}
