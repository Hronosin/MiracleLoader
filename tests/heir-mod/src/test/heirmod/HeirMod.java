package test.heirmod;

import io.github.hronosin.miracle.api.MiracleMod;
import net.minecraft.world.entity.player.Player;

public final class HeirMod implements MiracleMod {
    @Override
    public void onLaunch() {
        Knight k = new Knight("Arthur");
        Player asGame = k;
        // getName is the game's, reached through the library class; getScore is overridden twice.
        System.out.println("[heir-mod] " + k.getName() + " scores " + asGame.getScore() + " and is " + k.blessing());
    }
}
