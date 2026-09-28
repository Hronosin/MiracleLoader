package io.github.hronosin.miracle.api;

import io.github.hronosin.miracle.rgct.Rgct;

/**
 * Entrypoint of a MiracleLoader mod.
 *
 * <p>The class named by {@code entrypoint} in {@code miracle.mod.toml} must implement this
 * interface and have a public no-arg constructor.
 *
 * <p>Lifecycle, in order:
 * <ol>
 *   <li>{@link #transform(Rgct)} — every mod registers its class transforms. No game class has
 *       been loaded yet, and none may be: a game class touched here is loaded untransformed and
 *       the loader will refuse to start if anyone targets it.</li>
 *   <li>RGCT freezes. From now on, targeted game classes get patched as they load.</li>
 *   <li>{@link #onLaunch()} — right before the game's {@code main} runs.</li>
 * </ol>
 */
public interface MiracleMod {

    /** Phase 1: register class transforms. Do not touch game classes here. */
    default void transform(Rgct rgct) {
    }

    /** Phase 2: called right before the game's main method. Game classes may be used from here on. */
    default void onLaunch() {
    }
}
