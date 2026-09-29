package io.github.hronosin.miracle.toolchain;

/**
 * {@link Telepathy}, for those who prefer cables to minds. Same class underneath:
 * {@code Networking.channel("my_mod:ping")} is {@code Telepathy.channel("my_mod:ping")}.
 */
public final class Networking extends Telepathy {

    private Networking() {
        super();
    }
}
