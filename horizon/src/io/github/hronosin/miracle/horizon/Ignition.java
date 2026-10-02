package io.github.hronosin.miracle.horizon;

import io.github.hronosin.miracle.toolchain.Omens;

/** Connects Event Horizon's clocks to the game's ticks, once the game may be touched. */
final class Ignition {

    private Ignition() {
    }

    static void wire() {
        Omens.serverTick(server -> {
            Redshift.server().tick();
            Accretion.tick();
            Ergosphere.tick();
        });
        Omens.serverStopping(server -> {
            Redshift.server().reset();
            Accretion.reset();
            Ergosphere.reset();
            QuantumFoam.reset();
        });
        Omens.entityDied((entity, source) -> Accretion.forget(entity));
        Omens.clientTick(() -> Redshift.client().tick());
        Telescope.install();
    }
}
