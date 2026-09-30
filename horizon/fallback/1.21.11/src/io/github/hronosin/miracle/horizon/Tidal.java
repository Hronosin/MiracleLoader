package io.github.hronosin.miracle.horizon;

import net.minecraft.world.entity.Entity;

final class Tidal {

    /** 1.21.11 calls it hurtMarked; needsSync there is something else. */
    static void sync(Entity e) {
        e.hurtMarked = true;
    }
}
