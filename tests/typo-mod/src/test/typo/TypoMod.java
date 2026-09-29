package test.typo;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Targets a class that doesn't exist. The loader must say so at startup. */
public final class TypoMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Playre").method("jump").atHead(self -> { });
    }
}
