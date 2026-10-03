package test.redirect2;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/** Redirects the same call as redirect-mod: only one mod may. */
public final class RedirectClash implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player").method("title").redirect(Player::motd, () -> "mine");
    }
}
