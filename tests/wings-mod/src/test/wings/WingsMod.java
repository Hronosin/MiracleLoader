package test.wings;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Hooks Player#fly, which the obfuscated fake game doesn't have; its fallback hooks something else. */
public final class WingsMod implements MiracleMod {

    private static int flaps;

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("fly").atHead(self -> flap("flap"));
    }

    static void flap(String how) {
        flaps++;
        System.out.println("[wings-mod] " + how + " #" + flaps);
    }

    @Override
    public void onLaunch() {
        System.out.println("[wings-mod] main onLaunch");
    }
}
