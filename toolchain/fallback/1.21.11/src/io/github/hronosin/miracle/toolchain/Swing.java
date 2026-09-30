package io.github.hronosin.miracle.toolchain;

import net.minecraft.world.entity.LivingEntity;

/** 1.21.11: the swing is getAttackAnim, under an obfuscated name the bake translates. */
final class Swing {

    static float progress(Object entity, float partialTick) {
        return entity instanceof LivingEntity l ? l.getAttackAnim(partialTick) : 0;
    }
}
