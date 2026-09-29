package test.fly;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Targets Player#fly, which the obfuscated fake game doesn't have. */
public final class FlyMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("fly").atHead(self -> System.out.println("[fly-mod] whee"));
    }
}
