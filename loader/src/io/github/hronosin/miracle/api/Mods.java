package io.github.hronosin.miracle.api;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who else is here: every mod the loader found, in load order, and which game is running.
 * Filled in by the loader before the first {@link MiracleMod#transform} call.
 */
public final class Mods {

    /**
     * One loaded mod. {@code library} is true for a mod without an entrypoint: it only brings
     * classes for other mods to use. {@code depends} holds the ids from its {@code depends}
     * (without version requirements). {@code icon} is the path of its icon inside the jar, or
     * null if it has none.
     */
    public record Mod(String id, String name, String version, List<String> authors, Path jar, boolean library,
                      List<String> depends, String icon) {

        /** The icon's bytes (a PNG), or null. Read from the jar each time; cache it if you show it often. */
        public byte[] iconBytes() {
            if (icon == null) {
                return null;
            }
            try (var jf = new java.util.jar.JarFile(jar.toFile())) {
                var e = jf.getJarEntry(icon);
                if (e == null) {
                    return null;
                }
                try (var in = jf.getInputStream(e)) {
                    return in.readAllBytes();
                }
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }

    /**
     * The running game. {@code obfuscated} is true for 1.21.11 and older; {@code client} is false
     * on a dedicated server, whose jar has no client classes at all.
     */
    public record Game(String version, boolean obfuscated, boolean client) {
    }

    private static volatile Map<String, Mod> mods;
    private static volatile Game game;
    private static volatile boolean launched;

    private Mods() {
    }

    /** Every mod, in the order the loader runs them (dependencies first). */
    public static List<Mod> all() {
        return List.copyOf(require().values());
    }

    public static Optional<Mod> get(String id) {
        return Optional.ofNullable(require().get(id));
    }

    /** The mod with this id; throws if it isn't loaded. */
    public static Mod of(String id) {
        return get(id).orElseThrow(() -> new IllegalArgumentException("no mod '" + id + "' is loaded"));
    }

    public static boolean isLoaded(String id) {
        return require().containsKey(id);
    }

    public static Game game() {
        require();
        return game;
    }

    /**
     * True once every mod's {@code transform()} has run and RGCT is frozen: from here on, game
     * classes may be used freely, and no more patches can be registered.
     */
    public static boolean launched() {
        return launched;
    }

    /**
     * The mod whose jar a class came from (its baked variants included), or empty for classes of
     * the game, the JDK or the loader.
     */
    public static Optional<Mod> owner(Class<?> cls) {
        var domain = cls.getProtectionDomain();
        var source = domain == null ? null : domain.getCodeSource();
        if (source == null || source.getLocation() == null) {
            return Optional.empty();
        }
        String url = source.getLocation().toString();
        if (url.startsWith("jar:")) {
            int bang = url.indexOf("!/");
            url = url.substring(4, bang < 0 ? url.length() : bang);
        }
        Path where;
        try {
            where = Path.of(java.net.URI.create(url)).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        for (Mod m : require().values()) {
            if (m.jar().toAbsolutePath().normalize().equals(where)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Loader use only: called once RGCT is frozen, right before the first onLaunch(). */
    public static void launch() {
        require();
        launched = true;
    }

    /** Loader use only: called once, before any mod code runs. */
    public static synchronized void revealed(List<Mod> loaded, Game running) {
        if (mods != null) {
            throw new IllegalStateException("Mods were already revealed. Nice try.");
        }
        Map<String, Mod> m = new LinkedHashMap<>();
        loaded.forEach(x -> m.put(x.id(), x));
        game = running;
        mods = m;
    }

    private static Map<String, Mod> require() {
        Map<String, Mod> m = mods;
        if (m == null) {
            throw new IllegalStateException("MiracleLoader hasn't discovered mods yet (or isn't running at all).");
        }
        return m;
    }
}
