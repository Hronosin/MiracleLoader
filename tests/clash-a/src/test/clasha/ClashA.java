package test.clasha;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

public final class ClashA implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("motd").interceptReturn(ctx -> ctx.setReturnValue("A"));
    }
}
