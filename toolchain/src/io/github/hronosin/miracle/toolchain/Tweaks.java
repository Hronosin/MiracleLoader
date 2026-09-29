package io.github.hronosin.miracle.toolchain;

/**
 * {@link Blessings}, for people who'd rather tweak than bless. Same class underneath:
 * {@code Tweaks.of(rgct).jumpPower()} is {@code Blessings.of(rgct).jumpPower()}.
 */
public final class Tweaks extends Blessings {

    private Tweaks() {
        super();
    }
}
