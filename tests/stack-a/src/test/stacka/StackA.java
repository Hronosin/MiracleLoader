package test.stacka;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Multiplies jump power, caps ticks, cancels explosions. */
public final class StackA implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("getJumpPower").interceptReturn(ctx -> ctx.multiplyReturnValue(1.5f))
                .and()
                .method("ticks").interceptReturn(ctx -> ctx.clampReturnValue(0, 50))
                .and()
                .method("explode").interceptHead(ctx -> ctx.cancel());
    }
}
