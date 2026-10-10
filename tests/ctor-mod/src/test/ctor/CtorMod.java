package test.ctor;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** interceptHead on a constructor: before super(), no self, arguments that can change, no cancel. */
public final class CtorMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("<init>")
                .interceptHead(ctx -> {
                    System.out.println("[ctor-mod] making '" + ctx.arg(0) + "', self=" + ctx.self());
                    if ("Steve".equals(ctx.arg(0))) {
                        ctx.setArg(0, "Saint Steve");
                    }
                    if (Boolean.getBoolean("ctor.cancel")) {
                        ctx.cancel();
                    }
                });
    }
}
