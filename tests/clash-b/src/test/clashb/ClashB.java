package test.clashb;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

/** Sets "B", or whatever -Dclash.b says (to test agreeing mods). */
public final class ClashB implements MiracleMod {
    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("motd").interceptReturn(ctx -> ctx.setReturnValue(System.getProperty("clash.b", "B")));
    }
}
