package org.test.entangler;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.api.Mods;

public final class Entangler implements MiracleMod {
    @Override
    public void onLaunch() {
        System.out.println("[entangler] order: " + Mods.all().stream().map(Mods.Mod::id).toList());
        System.out.println("[entangler] entangled: " + Mods.entangled("a-entangler") + ", z-partner's: "
                + Mods.entangled("z-partner") + ", nobody's: " + Mods.entangled("nobody"));
    }
}
