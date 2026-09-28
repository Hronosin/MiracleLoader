package test.eager;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/** Breaks the one rule: touches a game class inside transform(). The loader must refuse to start. */
public final class EagerMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("jumpFromGround")
                .atHead(self -> { });
        System.out.println("[eager-mod] peeking early: " + Player.ticks());
    }
}
