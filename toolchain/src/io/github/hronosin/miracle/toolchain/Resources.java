package io.github.hronosin.miracle.toolchain;

/**
 * {@link Scripture}, for people who just want their recipes loaded. Same class underneath:
 * {@code Resources.of(rgct).reveal()} is {@code Scripture.of(rgct).reveal()}.
 */
public final class Resources extends Scripture {

    private Resources() {
        super();
    }
}
