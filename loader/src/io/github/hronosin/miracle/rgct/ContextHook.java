package io.github.hronosin.miracle.rgct;

import java.io.Serializable;

/**
 * Code that RGCT runs inside a game method and that may change what the method does.
 * Registered with {@code interceptHead} / {@code interceptReturn}.
 *
 * <p>Costs more than a plain {@link Hook}: the arguments are boxed into an array on every call.
 * Use a plain hook when you only need to look at {@code self}.
 *
 * <p>Serializable only so that RGCT can find the lambda's body at startup and check which effects
 * it may produce. Nothing is ever actually serialized.
 */
@FunctionalInterface
public interface ContextHook extends Serializable {
    void run(HookContext ctx);
}
