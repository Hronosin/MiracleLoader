package io.github.hronosin.miracle.horizon;

import io.github.hronosin.miracle.api.Mods;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lensing (boring name: Shaders): bring your own shaders. You write GLSL the way you already do,
 * in either of the game's two dialects, and Event Horizon carries it to whichever version is
 * running:
 * <ul>
 * <li><b>classic</b> (1.21.11 to 26.2): {@code #moj_import <...>}, {@code in}/{@code out} without
 *     locations;</li>
 * <li><b>separate</b> (26.3 on, OpenGL and Vulkan alike): {@code #include <...>},
 *     {@code #extension GL_ARB_separate_shader_objects : require}, and
 *     {@code layout(location = N)} on every {@code in} and {@code out}.</li>
 * </ul>
 * Every shader of a mod's own namespace ({@code assets/<yours>/shaders/...}) is translated as the
 * game loads it; vanilla's ({@code minecraft}) are left alone. Only the syntax changes: what the
 * game offers inside (includes, uniform names) is still the version's own business.
 *
 * <p>Locations, when they're added: in declaration order, separately for {@code in} and
 * {@code out}, skipping numbers already written. So declare a vertex shader's inputs in its
 * vertex format's order (vanilla does), and the outputs of a vertex shader in the same order as
 * the inputs of its fragment shader. Write {@code layout(location = N)} yourself where that's
 * not so; it's kept (and removed again for the classic dialect).
 *
 * <p>Post effects ({@code assets/<yours>/post_effect/<name>.json}):
 * <pre>{@code
 * Lensing.add(player, "mymod:warp");              // server, 26.3+: the game's own list, sent to the player
 * Lensing.forTicks(player, "mymod:warp", 40);
 * Lensing.here("mymod:warp");                     // client, any version: this screen only
 * Lensing.clearHere();
 * }</pre>
 * Before 26.3 the game has one post effect slot, on the client: {@link #here} takes it, and the
 * server can't reach it ({@link #add} says so and returns false). From 26.3 on, effects stack;
 * {@code here} adds to what the server set, and stays when the server changes its list.
 *
 * <p><b>Uniforms from code</b> (0.4): a uniform declared in the post effect's JSON (a block and its
 * fields, with their types and values) takes its value from your code instead, worked out every
 * frame on the render thread:
 * <pre>{@code
 * Lensing.uniform("mymod:warp", "Strength", () -> 0.5 + 0.5 * Math.sin(time) * Lensing.screenEffectScale());
 * Lensing.uniform("mymod:warp", "Center", () -> new float[] {cx, cy});      // a vec2
 * }</pre>
 * By name, in every pass of that effect whose block has it; the JSON's value is the default for
 * the fields you leave alone. Supported types: {@code float}, {@code int}, {@code vec2},
 * {@code vec3}, {@code vec4}, {@code ivec3}, {@code matrix4x4}.
 *
 * <p><b>A broken effect doesn't stop the game</b> (0.4): the game's own reaction to a post effect
 * that fails to compile is to reload resources without resource packs, which can't drop a mod's
 * and so ends in a crash. For any post effect outside {@code minecraft}, Event Horizon skips that
 * recovery: the effect stays off, the error stays in the log, F3+T tries again.
 *
 * <p>Effects are what you make them: keep flashing out of them, or offer a switch (and scale
 * strength by {@link #screenEffectScale()}, the player's own "Distortion Effects" setting);
 * players with photosensitive epilepsy play this game too.
 */
public final class Lensing {

    /** The two ways the game has written GLSL. */
    public enum Dialect {
        /** 1.21.11 to 26.2. */
        CLASSIC,
        /** 26.3 on. */
        SEPARATE
    }

    /** What a file is, by its extension. */
    public enum Stage {
        VERTEX, FRAGMENT, INCLUDE;

        /** By file name: .vsh, .fsh, .glsl; null for anything else. */
        public static Stage of(String path) {
            String p = path.toLowerCase(Locale.ROOT);
            return p.endsWith(".vsh") ? VERTEX : p.endsWith(".fsh") ? FRAGMENT : p.endsWith(".glsl") ? INCLUDE : null;
        }
    }

    static final String EXTENSION = "#extension GL_ARB_separate_shader_objects : require";
    private static final Pattern IMPORT = Pattern.compile("^(\\s*)#\\s*(moj_import|include)\\b(.*)$");
    private static final Pattern VERSION = Pattern.compile("^(\\s*#\\s*version\\s+)(\\d+)(.*)$");
    private static final Pattern SEPARATE_EXT = Pattern.compile("^\\s*#\\s*extension\\s+GL_ARB_separate_shader_objects\\b.*$");
    private static final Pattern DECL = Pattern.compile(
            "^(\\s*)(layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)(in|out)\\s+(\\w+\\s+\\w+\\s*(?:\\[[^\\]]*\\])?\\s*;.*)$");
    private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)");

    private Lensing() {
    }

    /** The dialect of the running game; null outside one. */
    public static Dialect dialect() {
        try {
            return Wormhole.compare(Mods.game().version(), "26.3") >= 0 ? Dialect.SEPARATE : Dialect.CLASSIC;
        } catch (RuntimeException | LinkageError notRunning) {
            return null;
        }
    }

    /** The dialect a source looks written in. */
    public static Dialect dialectOf(String source) {
        for (String line : source.split("\n", -1)) {
            if (SEPARATE_EXT.matcher(line).matches() || line.matches("^\\s*#\\s*include\\b.*")) {
                return Dialect.SEPARATE;
            }
            Matcher d = DECL.matcher(line);
            if (d.matches() && d.group(3) != null && LOCATION.matcher(d.group(3)).find()) {
                return Dialect.SEPARATE;
            }
        }
        return Dialect.CLASSIC;
    }

    /**
     * {@code source} in dialect {@code to}. Translating something already in that dialect changes
     * nothing, and classic to separate and back gives the same text again. Line numbers stay the
     * same, but for the one line the separate dialect's extension takes, right after
     * {@code #version}.
     */
    public static String translate(String source, Dialect to, Stage stage) {
        String nl = source.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = source.replace("\r\n", "\n").split("\n", -1);
        // explicit locations already taken, per direction
        Set<Integer> takenIn = new TreeSet<>();
        Set<Integer> takenOut = new TreeSet<>();
        scan(lines, (line, depth) -> {
            Matcher d = DECL.matcher(line);
            if (depth == 0 && d.matches() && d.group(3) != null) {
                Matcher loc = LOCATION.matcher(d.group(3));
                if (loc.find()) {
                    (d.group(5).equals("in") ? takenIn : takenOut).add(Integer.parseInt(loc.group(1)));
                }
            }
            return line;
        });
        int[] next = {0, 0};
        boolean[] hasExtension = {false};
        String[] out = scan(lines, (line, depth) -> {
            Matcher m = IMPORT.matcher(line);
            if (m.matches()) {
                return m.group(1) + (to == Dialect.SEPARATE ? "#include" : "#moj_import") + m.group(3);
            }
            if (SEPARATE_EXT.matcher(line).matches()) {
                hasExtension[0] = true;
                return to == Dialect.SEPARATE ? line : null;
            }
            Matcher v = VERSION.matcher(line);
            if (v.matches()) {
                int n = Integer.parseInt(v.group(2));
                return to == Dialect.SEPARATE && n < 330 ? v.group(1) + "330" + v.group(3) : line;
            }
            Matcher d = DECL.matcher(line);
            if (depth != 0 || !d.matches() || stage == Stage.INCLUDE || stage == null) {
                return line;
            }
            String indent = d.group(1);
            String inner = d.group(3);
            String quals = d.group(4);
            String dir = d.group(5);
            String rest = d.group(6);
            if (to == Dialect.CLASSIC) {
                if (inner == null) {
                    return line;
                }
                String left = LOCATION.matcher(inner).replaceAll("").replaceAll("^[\\s,]+|[\\s,]+$", "").replaceAll(",\\s*,", ",");
                return indent + (left.isBlank() ? "" : "layout(" + left.strip() + ") ") + quals + dir + " " + rest;
            }
            if (inner != null && LOCATION.matcher(inner).find()) {
                return line;
            }
            Set<Integer> taken = dir.equals("in") ? takenIn : takenOut;
            int i = dir.equals("in") ? 0 : 1;
            while (taken.contains(next[i])) {
                next[i]++;
            }
            int loc = next[i]++;
            String layout = inner == null ? "layout(location = " + loc + ") " : "layout(location = " + loc + ", " + inner.strip() + ") ";
            return indent + layout + quals + dir + " " + rest;
        });
        List<String> result = new ArrayList<>();
        for (String line : out) {
            if (line != null) {
                result.add(line);
            }
        }
        if (to == Dialect.SEPARATE && !hasExtension[0] && stage != Stage.INCLUDE) {
            int at = 0;
            for (int i = 0; i < result.size(); i++) {
                if (VERSION.matcher(result.get(i)).matches()) {
                    at = i + 1;
                    break;
                }
            }
            result.add(at, EXTENSION);
        }
        return String.join(nl, result);
    }

    @FunctionalInterface
    private interface LineFn {
        String apply(String line, int depth);
    }

    /** Runs {@code fn} over each line with the brace depth at its start, skipping block comments. */
    private static String[] scan(String[] lines, LineFn fn) {
        String[] out = new String[lines.length];
        int depth = 0;
        boolean comment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (comment) {
                out[i] = line;
            } else {
                out[i] = fn.apply(line, depth);
            }
            // brace depth and comments, for the next line
            for (int k = 0; k < line.length(); k++) {
                char c = line.charAt(k);
                if (comment) {
                    if (c == '*' && k + 1 < line.length() && line.charAt(k + 1) == '/') {
                        comment = false;
                        k++;
                    }
                } else if (c == '/' && k + 1 < line.length() && line.charAt(k + 1) == '/') {
                    break;
                } else if (c == '/' && k + 1 < line.length() && line.charAt(k + 1) == '*') {
                    comment = true;
                    k++;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                }
            }
        }
        return out;
    }

    // --- post effects ----------------------------------------------------------------------------

    /** Whether this version lets the server set post effects ({@link #add}): 26.3 and later. */
    public static boolean serverSide() {
        return LensingGame.supported();
    }

    /** Server, 26.3+: adds a post effect to a player's screen (the game's own list). False if it can't. */
    public static boolean add(net.minecraft.server.level.ServerPlayer player, String id) {
        return LensingGame.add(player, id);
    }

    public static boolean remove(net.minecraft.server.level.ServerPlayer player, String id) {
        return LensingGame.remove(player, id);
    }

    public static boolean clear(net.minecraft.server.level.ServerPlayer player) {
        return LensingGame.clear(player);
    }

    /** Server, 26.3+: adds, and takes it off again after {@code ticks}. */
    public static boolean forTicks(net.minecraft.server.level.ServerPlayer player, String id, int ticks) {
        return LensingGame.forTicks(player, id, ticks);
    }

    /** Client, any version: a post effect on this screen. Before 26.3, it replaces the one there was. */
    public static void here(String id) {
        LensingClient.here(id);
    }

    /** Client: here, then gone again after {@code ticks}. */
    public static void hereForTicks(String id, int ticks) {
        here(id);
        Redshift.client().in(ticks, () -> LensingClient.away(id));
    }

    /** Client: takes back what {@link #here} put on this screen. */
    public static void clearHere() {
        LensingClient.clear();
    }

    // --- uniforms -------------------------------------------------------------------------------

    /** Effect id -> uniform name -> where its value comes from. */
    static final Map<String, Map<String, Supplier<float[]>>> UNIFORMS = new ConcurrentHashMap<>();

    /** A uniform's value from code, worked out every frame: a float or an int. */
    public static void uniform(String effect, String name, DoubleSupplier value) {
        uniforms(effect).put(name, () -> new float[] {(float) value.getAsDouble()});
    }

    /** A vector's (or matrix's, column by column) value from code, worked out every frame. */
    public static void uniform(String effect, String name, Supplier<float[]> value) {
        uniforms(effect).put(name, value);
    }

    /** A value set once, until changed. */
    public static void uniform(String effect, String name, float... value) {
        float[] v = value.clone();
        uniforms(effect).put(name, () -> v);
    }

    /** Back to the JSON's values. */
    public static void clearUniforms(String effect) {
        UNIFORMS.remove(effect);
    }

    private static Map<String, Supplier<float[]>> uniforms(String effect) {
        if (!effect.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("A post effect id looks like \"mymod:warp\", not \"" + effect + "\".");
        }
        return UNIFORMS.computeIfAbsent(effect, k -> new ConcurrentHashMap<>());
    }

    /**
     * The graphics backend the game actually runs, once it has started: "opengl" or "vulkan";
     * "unknown" before the device exists, "none" outside a client. (Before it starts,
     * {@code Mods.graphicsBackend()} says what it's set to.)
     */
    public static String backend() {
        try {
            return LensingClient.backend();
        } catch (RuntimeException | LinkageError noClient) {
            return "none";
        }
    }

    /** The player's "Distortion Effects" setting, 0 to 1 (1 outside a client): scale your effects by it. */
    public static double screenEffectScale() {
        try {
            return LensingClient.screenEffectScale();
        } catch (RuntimeException | LinkageError noClient) {
            return 1;
        }
    }

    /** One field of a uniform block, as the post effect's JSON declares it. */
    public record Field(String name, String type, float[] value) {
    }

    /**
     * A uniform block's bytes by std140's rules, the fields in their declared order, each from
     * {@code values} (by name; null for the declared value). Native byte order, a direct buffer,
     * padded to a multiple of 16.
     */
    public static ByteBuffer std140(List<Field> fields, Function<String, float[]> values) {
        int size = 0;
        int[] at = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            int[] as = layout(fields.get(i).type());
            size = (size + as[0] - 1) / as[0] * as[0];
            at[i] = size;
            size += as[1];
        }
        ByteBuffer b = ByteBuffer.allocateDirect(Math.max(16, (size + 15) / 16 * 16)).order(ByteOrder.nativeOrder());
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            float[] v = values.apply(f.name());
            if (v == null) {
                v = f.value();
            }
            int n = layout(f.type())[2];
            boolean ints = f.type().equals("int") || f.type().equals("ivec3");
            for (int k = 0; k < n; k++) {
                float x = v != null && k < v.length ? v[k] : 0;
                if (ints) {
                    b.putInt(at[i] + 4 * k, Math.round(x));
                } else {
                    b.putFloat(at[i] + 4 * k, x);
                }
            }
        }
        return b;
    }

    /** {alignment, size, number of components} of a std140 type. */
    static int[] layout(String type) {
        return switch (type) {
            case "float", "int" -> new int[] {4, 4, 1};
            case "vec2" -> new int[] {8, 8, 2};
            case "vec3", "ivec3" -> new int[] {16, 12, 3};
            case "vec4" -> new int[] {16, 16, 4};
            case "matrix4x4" -> new int[] {16, 64, 16};
            default -> throw new IllegalArgumentException("Lensing doesn't know the uniform type '" + type + "'.");
        };
    }

    /** Client: the post effects this screen is showing (or asked to show), by id. */
    public static List<String> showing() {
        return LensingClient.showing();
    }
}
