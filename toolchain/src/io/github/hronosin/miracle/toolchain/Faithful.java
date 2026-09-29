package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.api.Mods;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The congregation: who foresaw what at startup, and the handlers each mod has added since.
 * Handlers live in per-mod lists that the installed hooks hold on to, so firing an omen is a
 * loop over a list, with no lookups.
 */
final class Faithful {

    private static final String PACKAGE = Faithful.class.getPackageName();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final Map<String, Set<Object>> FORESEEN = new ConcurrentHashMap<>();
    private static final Map<String, Map<Object, List<Object>>> HANDLERS = new ConcurrentHashMap<>();

    private Faithful() {
    }

    /** At startup: the hook for {@code key} is installed in {@code modId}'s name. */
    static void foresee(String modId, Object key) {
        FORESEEN.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    /** The live list a hook loops over. Same list for the same mod and key, forever. */
    @SuppressWarnings("unchecked")
    static <T> List<T> list(String modId, Object key) {
        return (List<T>) HANDLERS.computeIfAbsent(modId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    }

    /**
     * Adds a handler for the mod that called the public API. {@code what} is how the call reads
     * in the mod's code, for error messages.
     */
    static void join(String what, Object key, Object handler) {
        Mods.Mod mod = caller(what);
        if (!Mods.launched()) {
            throw new IllegalStateException(mod.id() + " calls " + what + " in transform(). Call it in onLaunch() instead:"
                    + " a handler that takes, say, a ServerPlayer loads the ServerPlayer class as it's created,"
                    + " and during transform() that's too early for anyone to patch it (or its parent classes)."
                    + " MiracleToolChain prepares everything your mod needs at startup; you just show up later.");
        }
        Set<Object> foreseen = FORESEEN.getOrDefault(mod.id(), Set.of());
        if (!foreseen.contains(key)) {
            String why = mod.depends().contains(MiracleToolChain.ID)
                    ? "MiracleToolChain reads each mod's classes at startup to see which of its parts the mod uses, and"
                      + " didn't see this one: the call comes from code it can't read (a class made at runtime, reflection,"
                      + " another jar?). Call it from your mod's own classes."
                    : "Its miracle.mod.toml lacks depends = [\"" + MiracleToolChain.ID + "\"], so MiracleToolChain"
                      + " prepared nothing for it.";
            throw new IllegalStateException(mod.id() + " calls " + what + ", which wasn't prepared at startup. " + why);
        }
        list(mod.id(), key).add(handler);
    }

    /** The mod whose code called into the library: the first frame outside this package. */
    static Mods.Mod caller(String what) {
        Class<?> cls = WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> !c.getPackageName().equals(PACKAGE))
                .findFirst()
                .orElse(null));
        return cls == null ? null : Mods.owner(cls).orElseThrow(() -> new IllegalStateException(
                what + " was called by " + cls.getName() + ", which isn't in any mod's jar. MiracleToolChain works for mods."));
    }
}
