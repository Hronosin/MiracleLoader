package io.github.hronosin.miracle;

/** A loader-side failure with a human message meant for the crash banner. */
final class MiracleFailure extends RuntimeException {
    MiracleFailure(String message) {
        super(message);
    }

    MiracleFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
