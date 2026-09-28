package io.github.hronosin.miracle.rgct;

/**
 * Code that RGCT runs inside a game method and that may change what the method does.
 * Registered with {@code interceptHead} / {@code interceptReturn}.
 *
 * <p>Costs more than a plain {@link Hook}: the arguments are boxed into an array on every call.
 * Use a plain hook when you only need to look at {@code self}.
 */
@FunctionalInterface
public interface ContextHook {
    void run(HookContext ctx);
}
