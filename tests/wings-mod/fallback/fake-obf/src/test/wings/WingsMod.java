package test.wings;

import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/**
 * Fallback for fake-obf, compiled against its readable API (no fly() there). Exercises every
 * rule: a replaced method, a lambda whose name would clash with the real class's, a serializable
 * lambda (interceptReturn), a native declaration pointing at the real flap(), a field
 * declaration pointing at the real field, and an added helper.
 */
final class WingsMod {

    private static int flaps;

    static native void flap(String how);

    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("jumpFromGround").atHead(self -> flap(label((Player) self)))
                .and()
                .method("getJumpPower").interceptReturn(ctx -> ctx.multiplyReturnValue(flaps + 1));
    }

    private static String label(Player p) {
        return "no wings in this version, " + p.getName() + " jumps instead";
    }
}
