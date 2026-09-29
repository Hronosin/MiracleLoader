package com.example.superjump;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/**
 * Players jump higher. Mobs stay grounded.
 *
 * <p>LivingEntity#jumpFromGround asks getJumpPower() how hard to jump. We let it answer, then
 * multiply the answer for players. Because it's a multiply effect, it stacks with other mods
 * (see sprint-jump) instead of overwriting them. 1.5x jump speed is about 2.5 blocks of height: enough to
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
                        // multiply, not set: stacks with any other mod that touches jump power
                        ctx.multiplyReturnValue(MULTIPLIER);
                    }
                });
    }

    @Override
    public void onLaunch() {
        System.out.println("[super-jump] Jump power x" + MULTIPLIER + " for players.");
    }
}
