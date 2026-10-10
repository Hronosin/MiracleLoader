package test.lanes;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/**
 * Mixes the context's lanes (one set or cancel, additions and factors for one slot) with
 * everything that has to go the full way, so both give the same results.
 */
public final class LanesMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                // a set and a factor on the same slot, then an addition from a second hook: (1 + 0.5) * 3
                .method("getJumpPower")
                .interceptReturn(ctx -> {
                    ctx.setReturnValue(1f);
                    ctx.multiplyReturnValue(3);
                })
                .interceptReturn(ctx -> ctx.addToReturnValue(0.5))
                .and()
                // one hook setting twice: the second one counts
                .method("getScore")
                .interceptReturn(ctx -> {
                    ctx.setReturnValue(1);
                    ctx.setReturnValue(7);
                })
                .and()
                // lane (add, multiply) plus a clamp and a set that go to the list: (10 + 1) * 2, at most 15
                .method("damage")
                .interceptHead(ctx -> {
                    ctx.addToArg(0, 1);
                    ctx.setArg(1, "lanes");
                    ctx.multiplyArg(0, 2);
                    ctx.clampArg(0, null, 15);
                })
                .and()
                // two slots: the first in the lane, the second in the list
                .method("move")
                .interceptHead(ctx -> {
                    ctx.addToArg(0, 1L);
                    ctx.multiplyArg(1, 2);
                })
                .and()
                // cancelling twice
                .method("explode")
                .interceptHead(ctx -> {
                    ctx.cancel();
                    ctx.cancel();
                })
                .and()
                // a cancel with a value, and a factor that the cancel makes moot
                .method("ticks")
                .interceptHead(ctx -> ctx.cancel(5));
    }
}
