package test.clashhi;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

public final class ClashHi implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("motd").priority(5).interceptReturn(ctx -> ctx.setReturnValue("HI"));
    }
}
