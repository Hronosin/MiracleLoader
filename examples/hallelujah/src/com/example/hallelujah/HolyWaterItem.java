package com.example.hallelujah;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Holy water: right-click to throw it. Heals the living, burns the undead, purifies heretics. */
public final class HolyWaterItem extends Item {

    public HolyWaterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            Projectile.spawnProjectileFromRotation(ThrownHolyWater::new, server, stack, player, 0f, 1.5f, 1f);
        }
        stack.consume(1, player);
        return InteractionResult.SUCCESS;
    }
}
