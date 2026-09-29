package io.github.hronosin.miracle.toolchain;

/**
 * {@link Omens}, renamed for people who find omens unprofessional. Same class underneath:
 * {@code Events.of(rgct).playerJoined(...)} is {@code Omens.of(rgct).playerJoined(...)}.
 */
public final class Events extends Omens {

    private Events() {
        super();
    }
}
