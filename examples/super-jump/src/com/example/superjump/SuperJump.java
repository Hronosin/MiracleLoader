package com.example.superjump;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/**
 * Players jump higher. Mobs stay grounded.
 *
 * <p>LivingEntity#jumpFromGround asks getJumpPower() how hard to jump. We let it answer, then
 * multiply the answer for players. 1.5x jump speed is about 2.5 blocks of height: enough to
 * clear a 2-block wall, not enough to take fall damage on the way down.
 */
public final class SuperJump implements MiracleMod {

    static final float MULTIPLIER = 1.5f;

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.LivingEntity")
                .method("getJumpPower", "()F")
                .interceptReturn(ctx -> {
                    if (ctx.self() instanceof Player) {
                        ctx.setReturnValue((float) ctx.returnValue() * MULTIPLIER);
                    }
                });
    }

    @Override
    public void onLaunch() {
        System.out.println("[super-jump] Jump power x" + MULTIPLIER + " for players.");
    }
}
