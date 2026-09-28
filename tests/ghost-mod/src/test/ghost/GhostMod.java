package test.ghost;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Targets a method that doesn't exist, like a mod built for another game version. */
public final class GhostMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("jumpFromGroundButItWasRenamedIn26_3")
                .atHead(self -> { });
    }
}
