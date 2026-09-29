package io.github.hronosin.miracle.toolchain;

/**
 * {@link Proclamations}, minus the town crier. Same class underneath:
 * {@code Notices.overlay(player, "hi")} is {@code Proclamations.overlay(player, "hi")}.
 */
public final class Notices extends Proclamations {

    private Notices() {
        super();
    }
}
