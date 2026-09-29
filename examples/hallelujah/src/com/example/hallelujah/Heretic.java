package com.example.hallelujah;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/**
 * A zombie that refused communion. Looks like a zombie (Being.looksLike("zombie"), which is why
 * it extends Zombie: the zombie renderer reads zombie things), and holy water hits it twice as hard.
 */
public final class Heretic extends Zombie {

    public Heretic(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }
}
