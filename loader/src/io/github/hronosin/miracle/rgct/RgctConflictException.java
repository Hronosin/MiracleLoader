package io.github.hronosin.miracle.rgct;

/**
 * Two or more mods asked for incompatible changes to the same value, with nothing to decide
 * between them. Thrown the moment it happens, naming every mod involved, instead of letting one
 * of them silently win.
 */
public final class RgctConflictException extends IllegalStateException {
    RgctConflictException(String message) {
        super(message);
    }
}
