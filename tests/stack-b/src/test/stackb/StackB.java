package test.stackb;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.HookContext;
import io.github.hronosin.miracle.rgct.Rgct;

/** Adds to jump power, and reports what it sees: always the vanilla value. */
public final class StackB implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("getJumpPower").interceptReturn(StackB::jump);
    }

    // A method reference and a helper call: EffectScan has to follow both.
    private static void jump(HookContext ctx) {
        System.out.println("[stack-b] sees jump power " + ctx.returnValue());
        bump(ctx);
    }

    private static void bump(HookContext ctx) {
        ctx.addToReturnValue(0.1);
    }
}
