package org.test.patron;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;

/** A library with an entrypoint that patches on behalf of the mods that depend on it. */
public final class Patron implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        System.out.println("[patron] launched before transform: " + Mods.launched());
        for (Mods.Mod m : Mods.all()) {
            if (m.depends().contains("patron-lib")) {
                String id = m.id();
                rgct.onBehalfOf(id).target("net.minecraft.world.entity.player.Player")
                        .method("jumpFromGround")
                        .atHead(self -> System.out.println("[patron] jump, on behalf of " + id));
            }
        }
        try {
            rgct.onBehalfOf("patron-lib-victim");
        } catch (RuntimeException e) {
            System.out.println("[patron] refused: " + e.getMessage());
        }
    }
}
