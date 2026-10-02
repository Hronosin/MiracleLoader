package io.github.hronosin.miracle.horizon;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;

import java.util.function.Consumer;

/**
 * Event Horizon Extension: MiracleToolChain, extended past the point of no return. An experimental
 * library on top of the library: vector math ({@link Vec}, {@link Quat}, {@link Curve},
 * {@link Ease}, {@link Noise}, {@link Shape}, {@link Ballistics}, {@link Geodesic}), raycasts
 * ({@link Singularity}), forces ({@link Tidal}), attributes and stats ({@link Accretion}), auras
 * ({@link Ergosphere}), time ({@link Redshift}), particles ({@link Hawking}), scale
 * ({@link Spaghettification}), randomness ({@link QuantumFoam}), values ({@link Penrose}), links
 * between mods ({@link Wormhole}), shaders ({@link Lensing}), sounds ({@link Chirp}) and
 * {@code /horizon} ({@link Telescope}).
 *
 * <p>Mods use it with {@code depends = ["miracle-toolchain", "event-horizon"]}: Event Horizon needs
 * the library installed, and a mod that calls the library itself says so too, so the library
 * prepares what it needs. Unlike
 * MiracleToolChain's, its API is {@link Experimental}: it may change in any release.
 */
@Experimental
public final class EventHorizon implements MiracleMod {

    public static final String ID = "event-horizon";
    static final Consumer<String> LOG = s -> System.out.println("[Event Horizon] " + s);

    @Override
    public void transform(Rgct rgct) {
        // Lensing: the game's shader loading, so mods' shaders are translated for this version
        LensingWiring.install(rgct);
    }

    @Override
    public void onLaunch() {
        // In a class of its own: verifying handlers that take game types loads those types, and
        // this class is loaded (and verified) before transform(), when that's still too early.
        Ignition.wire();
    }
}
