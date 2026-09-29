package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.MiniToml;
import io.github.hronosin.miracle.rgct.Rgct;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Commandments: your mod's settings, carved into {@code config/<mod id>.toml} for players to
 * edit. Known as {@link Config} to those who don't take orders from stone tablets.
 *
 * <pre>{@code
 * Commandments c = Commandments.mine();          // or Commandments.of(rgct) in transform()
 * double power = c.number("jump_multiplier", 1.5, "How much higher players jump. 1 = vanilla.");
 * boolean mobs = c.flag("mobs_too", false, "Should mobs jump higher as well?");
 * }</pre>
 *
 * <p>Asking for a setting that isn't in the file yet adds it, with its default and your
 * comment, so the file always documents every setting there is. Values the player wrote stay as
 * they are. A value of the wrong type (a word where a number goes) is reported and the default
 * is used; the game still starts.
 *
 * <p>Safe to use anywhere, {@code transform()} included: no game classes involved. The file lives in the game
 * folder's {@code config/}; {@code -Dmiracle.configDir=...} moves it.
 */
public class Commandments {

    private final String modId;
    private final Path file;
    private Map<String, Object> values;
    /** A file we couldn't read is never written to: the player's edits are worth more than our defaults. */
    private boolean broken;

    protected Commandments(String modId, Path file) {
        this.modId = modId;
        this.file = file;
        reload();
    }

    /** The commandments of the mod calling this: {@code config/<mod id>.toml}. Works any time. */
    public static Commandments mine() {
        return of(Faithful.caller("Commandments.mine").id());
    }

    /** The commandments of your mod, from {@code transform()}: {@code config/<mod id>.toml}. */
    public static Commandments of(Rgct rgct) {
        return of(rgct.modId());
    }

    /** The commandments in {@code config/<name>.toml}. */
    public static Commandments of(String name) {
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("'" + name + "' won't do as a file name");
        }
        return new Commandments(name, Path.of(System.getProperty("miracle.configDir", "config")).resolve(name + ".toml"));
    }

    /** Where the tablets are kept. */
    public Path file() {
        return file;
    }

    /** Reads the file again, for mods that let players change settings while the game runs. */
    public final synchronized void reload() {
        broken = false;
        if (!Files.isRegularFile(file)) {
            values = new java.util.LinkedHashMap<>();
            return;
        }
        try {
            values = MiniToml.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (MiniToml.ParseException e) {
            warn(file + " is broken (" + e.getMessage() + "). Using defaults for everything until it's fixed.");
            values = new java.util.LinkedHashMap<>();
            broken = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A number setting. */
    public synchronized double number(String key, double fallback, String comment) {
        Object v = get(key, fallback, comment);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return mistyped(key, v, "a number", fallback);
    }

    /** A whole-number setting. A fraction where a whole number goes is rounded, and reported. */
    public synchronized long integer(String key, long fallback, String comment) {
        Object v = get(key, fallback, comment);
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Double d) {
            warn(file + ": " + key + " = " + d + " should be a whole number; using " + Math.round(d) + ".");
            return Math.round(d);
        }
        return mistyped(key, v, "a whole number", fallback);
    }

    /** A true/false setting. */
    public synchronized boolean flag(String key, boolean fallback, String comment) {
        Object v = get(key, fallback, comment);
        if (v instanceof Boolean b) {
            return b;
        }
        return mistyped(key, v, "true or false", fallback);
    }

    /** A text setting. */
    public synchronized String text(String key, String fallback, String comment) {
        Object v = get(key, fallback, comment);
        if (v instanceof String s) {
            return s;
        }
        return mistyped(key, v, "\"text in quotes\"", fallback);
    }

    /** A list-of-text setting. */
    @SuppressWarnings("unchecked")
    public synchronized List<String> list(String key, List<String> fallback, String comment) {
        Object v = get(key, fallback, comment);
        if (v instanceof List<?> l) {
            return List.copyOf((List<String>) l);
        }
        return mistyped(key, v, "[\"a\", \"list\"]", fallback);
    }

    private Object get(String key, Object fallback, String comment) {
        if (!key.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("setting name '" + key + "': letters, digits, _ and - only");
        }
        Object v = values.get(key);
        if (v == null) {
            values.put(key, fallback);
            if (!broken) {
                carve(key, fallback, comment);
            }
            return fallback;
        }
        return v;
    }

    private <T> T mistyped(String key, Object got, String wanted, T fallback) {
        warn(file + ": " + key + " should be " + wanted + ", not " + toml(got)
                + ". Thou shalt not. Using " + toml(fallback) + " instead.");
        return fallback;
    }

    /** Appends one setting to the file, with its comment. The rest of the file is left alone. */
    private void carve(String key, Object value, String comment) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            StringBuilder sb = new StringBuilder();
            if (!Files.exists(file)) {
                sb.append("# Settings for ").append(modId).append(". Edit freely; restart the game to apply.\n");
                sb.append("# Written by MiracleToolChain, which adds each setting the first time the mod asks for it.\n");
            } else {
                String existing = Files.readString(file, StandardCharsets.UTF_8);
                if (!existing.isEmpty() && !existing.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            sb.append('\n');
            if (comment != null && !comment.isBlank()) {
                for (String line : comment.strip().split("\n")) {
                    sb.append("# ").append(line.strip()).append('\n');
                }
            }
            sb.append(key).append(" = ").append(toml(value)).append('\n');
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            warn("could not write " + file + ": " + e.getMessage());
        }
    }

    static String toml(Object v) {
        return switch (v) {
            case String s -> quote(s);
            case List<?> l -> {
                List<String> items = new ArrayList<>();
                l.forEach(x -> items.add(quote(String.valueOf(x))));
                yield "[" + String.join(", ", items) + "]";
            }
            case Double d when d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15 ->
                    String.format(Locale.ROOT, "%.1f", d);
            case null -> "nothing";
            default -> String.valueOf(v);
        };
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static void warn(String msg) {
        io.github.hronosin.miracle.Log.warn("MiracleToolChain: " + msg);
    }
}
