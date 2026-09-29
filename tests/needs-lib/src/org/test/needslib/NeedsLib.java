package org.test.needslib;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.api.Mods;
import org.test.deplib.Psalm;

public final class NeedsLib implements MiracleMod {
    @Override
    public void onLaunch() {
        System.out.println("[needs-lib] " + Psalm.text());
        System.out.println("[needs-lib] order: " + Mods.all().stream().map(Mods.Mod::id).toList());
        System.out.println("[needs-lib] owner: " + Mods.owner(getClass()).map(Mods.Mod::id).orElse("?")
                + ", psalm owner: " + Mods.owner(Psalm.class).map(Mods.Mod::id).orElse("?")
                + ", String owner: " + Mods.owner(String.class).map(Mods.Mod::id).orElse("none"));
        System.out.println("[needs-lib] dep-lib is a library: " + Mods.of("dep-lib").library()
                + ", launched: " + Mods.launched() + ", client: " + Mods.game().client());
    }
}
