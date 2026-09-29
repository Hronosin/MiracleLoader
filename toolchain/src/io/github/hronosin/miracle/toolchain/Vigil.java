package io.github.hronosin.miracle.toolchain;

/**
 * A block entity that keeps vigil: acts every tick while its chunk is loaded. Implement it on a
 * block entity held by a {@link Sanctuary}, and override the side you need. The block entity's
 * {@code getLevel()}, {@code getBlockPos()} and {@code getBlockState()} tell where it stands.
 *
 * <pre>{@code
 * class AltarEntity extends Reliquary implements Vigil {
 *     private int age;
 *     public void serverTick() {
 *         if (++age % 100 == 0) { ... }
 *     }
 * }
 * }</pre>
 *
 * <p>Twenty times a second, for every one of them in the world: keep it cheap, and count ticks
 * rather than doing everything every time.
 */
public interface Vigil {

    /** Every server tick. Where the real work happens. */
    default void serverTick() {
    }

    /** Every client tick, for looks only (particles, sounds): the server decides what's true. */
    default void clientTick() {
    }
}
