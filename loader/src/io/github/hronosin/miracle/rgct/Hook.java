package io.github.hronosin.miracle.rgct;

/**
 * Code that RGCT runs inside a game method.
 *
 * <p>{@code self} is the instance the method was called on, or {@code null} for static methods
 * and for the head of a constructor (where {@code this} is not initialized yet).
 */
@FunctionalInterface
public interface Hook {
    void run(Object self);
}
