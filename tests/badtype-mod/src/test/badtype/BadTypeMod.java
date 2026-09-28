package test.badtype;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Returns a Double where a float is expected. RGCT must say so and name this mod. */
public final class BadTypeMod implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("getJumpPower")
                .interceptReturn(ctx -> ctx.setReturnValue(2.0));
    }
}
