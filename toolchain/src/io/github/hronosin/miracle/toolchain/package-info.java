/**
 * The MiracleToolChain library: everything you need, and several things you don't.
 *
 * <p>Every part comes in two names. The real one, and one for people who find the real one
 * unprofessional:
 * <ul>
 *   <li>{@link io.github.hronosin.miracle.toolchain.Omens} / {@link io.github.hronosin.miracle.toolchain.Events}
 *       — things that happen: joins, jumps, chat, ticks, broken blocks.</li>
 *   <li>{@link io.github.hronosin.miracle.toolchain.Blessings} / {@link io.github.hronosin.miracle.toolchain.Tweaks}
 *       — well-known values with merge rules: jump power, speed, fall damage, damage taken.</li>
 *   <li>{@link io.github.hronosin.miracle.toolchain.Sermons} / {@link io.github.hronosin.miracle.toolchain.ChatCommands}
 *       — commands.</li>
 *   <li>{@link io.github.hronosin.miracle.toolchain.Commandments} / {@link io.github.hronosin.miracle.toolchain.Config}
 *       — settings files.</li>
 *   <li>{@link io.github.hronosin.miracle.toolchain.Scripture} / {@link io.github.hronosin.miracle.toolchain.Resources}
 *       — your jar's data and assets, loaded like the game's own.</li>
 * </ul>
 *
 * <p>All of it is RGCT underneath, registered in your mod's name. Nothing is patched unless a
 * mod asks for it.
 */
package io.github.hronosin.miracle.toolchain;
