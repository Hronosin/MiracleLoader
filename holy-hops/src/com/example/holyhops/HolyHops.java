package com.example.holyhops;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/** Let there be mod. */
public final class HolyHops implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        // Players jump a quarter higher. multiply, not set: it stacks with other mods.
        rgct.target("net.minecraft.world.entity.LivingEntity")
                .method("getJumpPower", "()F")
                .interceptReturn(ctx -> {
                    if (ctx.self() instanceof Player) {
                        ctx.multiplyReturnValue(1.25f);
                    }
                });
    }

    @Override
    public void onLaunch() {
        System.out.println("[holy-hops] Let there be mod.");
    }
}
