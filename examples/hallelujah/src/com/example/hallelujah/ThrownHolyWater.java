package com.example.hallelujah;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Holy water in flight. Drawn as the item it carries (Being.looksLikeItem), so it needs no
 * renderer, model or texture of its own.
 */
public final class ThrownHolyWater extends ThrowableItemProjectile {

    /** For the entity type: how the game makes one (on the client, when the server says one exists). */
    public ThrownHolyWater(EntityType<? extends ThrownHolyWater> type, Level level) {
        super(type, level);
    }

    /** Thrown by someone. */
    ThrownHolyWater(Level level, LivingEntity thrower, ItemStack stack) {
        super(Hallelujah.thrownHolyWater.get(), thrower, level, stack);
    }

    @Override
    protected Item getDefaultItem() {
        return Hallelujah.holyWater.get();
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (level() instanceof ServerLevel level && hit.getEntity() instanceof LivingEntity target) {
            if (target instanceof Heretic) {
                target.hurtServer(level, damageSources().thrown(this, getOwner()), 12f); // purified
            } else if (target.isInvertedHealAndHarm()) {
                target.hurtServer(level, damageSources().thrown(this, getOwner()), 6f);  // undead: it burns
            } else {
                target.heal(2f);
            }
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SPLASH, getX(), getY(), getZ(), 24, 0.3, 0.3, 0.3, 0.1);
            discard();
        }
    }
}
