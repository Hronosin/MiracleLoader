package io.github.hronosin.miracle.horizon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * The game's types as keys, kept apart so the parts of Event Horizon that need no game (and its
 * self-test) never load a game class: without a game, these say "not one of mine".
 */
final class GameKeys {

    private GameKeys() {
    }

    /** A key part for an entity (by UUID) or a block position; null for anything else. */
    static Long part(Object o) {
        try {
            if (o instanceof Entity e) {
                return QuantumFoam.uuid(e.getUUID());
            }
            if (o instanceof BlockPos p) {
                return QuantumFoam.mix(p.asLong() + 11);
            }
        } catch (NoClassDefFoundError noGame) {
            // no game classes here: it can't be one
        }
        return null;
    }

    /** Who owns a streak: an entity by its UUID (so it survives unloading), anything else as itself. */
    static Object owner(Object o) {
        try {
            if (o instanceof Entity e) {
                return e.getUUID();
            }
        } catch (NoClassDefFoundError noGame) {
            // no game classes here: it can't be one
        }
        return o;
    }

    /** How to name a context in a report: an entity by its name, anything else as itself. */
    static String describe(Object o) {
        if (o == null || o instanceof String || o instanceof Number) {
            return String.valueOf(o);
        }
        try {
            if (o instanceof Entity e) {
                return e.getName().getString();
            }
        } catch (NoClassDefFoundError noGame) {
            // no game classes here: it can't be one
        }
        return String.valueOf(o);
    }
}
