package com.example.hallelujah;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/**
 * A zombie that refused communion: behaves like a zombie, but wears its own robe, hood and
 * horn (Being.sculpted(), a Blockbench model in resources/assets/hallelujah/geo/). Holy water
 * hits it twice as hard.
 */
public final class Heretic extends Zombie {

    public Heretic(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }
}
