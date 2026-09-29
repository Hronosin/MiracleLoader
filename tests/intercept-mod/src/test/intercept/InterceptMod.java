package test.intercept;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/** Exercises every intercept feature against the fake game. */
public final class InterceptMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                // float return value
                .method("getJumpPower")
                .interceptReturn(ctx -> ctx.multiplyReturnValue(2f))
                .and()
                // change a primitive and a reference argument
                .method("damage")
                .interceptHead(ctx -> {
                    ctx.multiplyArg(0, 0.5);
                    ctx.setArg(1, "reduced " + ctx.arg(1));
                })
                .and()
                // cancel a void method
                .method("explode")
                .interceptHead(ctx -> {
                    System.out.println("[intercept-mod] explosion cancelled");
                    ctx.cancel();
                })
                .and()
                // long/double slots, boolean arg, double return
                .method("move")
                .interceptHead(ctx -> {
                    ctx.multiplyArg(0, 10);
                    ctx.setArg(2, true);
                })
                .interceptReturn(ctx -> {
                    System.out.println("[intercept-mod] move args at return: " + ctx.arg(0) + " " + ctx.arg(3));
                    ctx.addToReturnValue(0.25);
                })
                .and()
                // static method, reference return
                .method("motd")
                .interceptReturn(ctx -> ctx.setReturnValue("miracle, self=" + ctx.self()))
                .and()
                // cancel with a value, using self
                .method("getScore")
                .interceptHead(ctx -> {
                    if (((Player) ctx.self()).getName().isEmpty()) {
                        ctx.cancel(1337);
                    }
                })
                .and()
                // static int return
                .method("ticks")
                .interceptReturn(ctx -> ctx.addToReturnValue(100))
                .and()
                // not supported: should warn and be skipped
                .method("<init>")
                .interceptHead(ctx -> { });
    }
}
