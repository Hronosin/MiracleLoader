package com.example.sprintjump;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/**
 * A running start helps: +0.1 jump power while sprinting.
 *
 * <p>The point of this mod is to share a method with super-jump. Both hook
 * LivingEntity#getJumpPower; RGCT merges their effects instead of letting one overwrite the
 * other: (vanilla + 0.1) x 1.5, in whatever order the two mods load.
 *
 * <p>Easy to check in game. Jump heights: vanilla 1.25 blocks, sprint-jump alone 1.84,
 * super-jump alone 2.59, both together 3.79. So only with both mods can you sprint-jump onto a
 * 3-block wall. (Land on flat ground from that height and it costs half a heart.)
 */
public final class SprintJump implements MiracleMod {

    static final float BONUS = 0.1f;

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.LivingEntity")
                .method("getJumpPower", "()F")
                .interceptReturn(ctx -> {
                    if (ctx.self() instanceof Player p && p.isSprinting()) {
                        ctx.addToReturnValue(BONUS);
                    }
                });
    }

    @Override
    public void onLaunch() {
        System.out.println("[sprint-jump] +" + BONUS + " jump power while sprinting.");
    }
}
