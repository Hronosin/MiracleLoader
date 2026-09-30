package io.github.hronosin.miracle.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Animations in Bedrock's format ({@code .animation.json}, what Blockbench exports for Bedrock
 * models), read into keyframes and played by {@link Sculptor}. Pure data and arithmetic, no game
 * classes.
 *
 * <p>Supported: {@code rotation}, {@code position} and {@code scale} channels, as a constant
 * value or keyframes; {@code pre}/{@code post} values (a jump at a keyframe); linear and
 * {@code catmullrom} interpolation; {@code loop} ({@code true}, {@code false},
 * {@code "hold_on_last_frame"}) and {@code animation_length}; and values written in Molang, the
 * parts animators actually use (see {@link Molang}).
 *
 * <p>Values are converted as Blockbench converts them for Java: rotations carry over as they are,
 * positions flip their Y (Bedrock's Y is up, Java's down).
 */
final class Liturgy {

    enum Loop { ONCE, LOOP, HOLD }

    /** One keyframe: the value arriving ({@code pre}) and leaving ({@code post}), three expressions each. */
    record Key(double time, Molang[] pre, Molang[] post, boolean smooth) {
    }

    /** One channel of one bone: keyframes sorted by time. */
    record Channel(List<Key> keys) {
    }

    /** What an animation does to one bone. Any channel may be null. */
    record Bone(Channel rotation, Channel position, Channel scale) {
    }

    /** One animation. {@code name} is its full id ({@code animation.heretic.walk}). */
    record Rite(String name, double length, Loop loop, Map<String, Bone> bones) {

        /** The last part of the name, lower case: {@code walk} for {@code animation.heretic.walk}. */
        String kind() {
            String n = name.toLowerCase(Locale.ROOT);
            return n.substring(n.lastIndexOf('.') + 1);
        }

        /** Where the animation is at {@code seconds} since it started, by its loop mode. */
        double at(double seconds) {
            if (length <= 0) {
                return 0;
            }
            if (loop == Loop.LOOP) {
                return seconds % length;
            }
            return Math.min(seconds, length);
        }
    }

    /** Everything a Molang expression may ask about, for one frame. */
    record Scene(double animTime, double lifeTime, double groundSpeed, double distanceMoved, double headX, double headY) {
    }

    private Liturgy() {
    }

    /** Every animation in a {@code .animation.json}, by full name, in file order. */
    static Map<String, Rite> read(String json, List<String> notes) {
        Object root = Clay.Json.parse(json);
        if (!(Clay.Json.get(root, "animations") instanceof Map<?, ?> anims)) {
            throw new IllegalArgumentException("no \"animations\" in it (a Bedrock animation file from Blockbench?)");
        }
        Map<String, Rite> out = new LinkedHashMap<>();
        for (var e : anims.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> a)) {
                continue;
            }
            Object loopValue = a.get("loop");
            Loop loop = Boolean.TRUE.equals(loopValue) ? Loop.LOOP
                    : "hold_on_last_frame".equals(loopValue) ? Loop.HOLD : Loop.ONCE;
            Map<String, Bone> bones = new LinkedHashMap<>();
            double last = 0;
            if (a.get("bones") instanceof Map<?, ?> bs) {
                for (var b : bs.entrySet()) {
                    if (!(b.getValue() instanceof Map<?, ?> bone)) {
                        continue;
                    }
                    String where = name + " / " + b.getKey();
                    Channel rot = channel(bone.get("rotation"), false, where + " rotation", notes);
                    Channel pos = channel(bone.get("position"), false, where + " position", notes);
                    Channel scl = channel(bone.get("scale"), true, where + " scale", notes);
                    for (Channel c : new Channel[]{rot, pos, scl}) {
                        if (c != null && !c.keys().isEmpty()) {
                            last = Math.max(last, c.keys().getLast().time());
                        }
                    }
                    bones.put(String.valueOf(b.getKey()), new Bone(rot, pos, scl));
                }
            }
            double length = Clay.Json.num(a.get("animation_length"), last);
            out.put(name, new Rite(name, length, loop, bones));
        }
        return out;
    }

    private static Channel channel(Object value, boolean scale, String where, List<String> notes) {
        if (value == null) {
            return null;
        }
        List<Key> keys = new ArrayList<>();
        if (value instanceof Map<?, ?> frames && !frames.containsKey("pre") && !frames.containsKey("post")) {
            for (var f : frames.entrySet()) {
                double t;
                try {
                    t = Double.parseDouble(String.valueOf(f.getKey()));
                } catch (NumberFormatException e) {
                    notes.add(where + ": keyframe time '" + f.getKey() + "' isn't a number; skipped");
                    continue;
                }
                keys.add(key(t, f.getValue(), scale, where, notes));
            }
            keys.sort(Comparator.comparingDouble(Key::time));
        } else {
            keys.add(key(0, value, scale, where, notes));
        }
        return new Channel(List.copyOf(keys));
    }

    private static Key key(double time, Object v, boolean scale, String where, List<String> notes) {
        if (v instanceof Map<?, ?> m && (m.containsKey("pre") || m.containsKey("post"))) {
            Molang[] pre = vec(m.containsKey("pre") ? m.get("pre") : m.get("post"), scale, where, notes);
            Molang[] post = vec(m.containsKey("post") ? m.get("post") : m.get("pre"), scale, where, notes);
            return new Key(time, pre, post, "catmullrom".equals(m.get("lerp_mode")));
        }
        Molang[] both = vec(v, scale, where, notes);
        return new Key(time, both, both, false);
    }

    /** {@code [x, y, z]}, or one value for all three (Bedrock allows that for scale). */
    private static Molang[] vec(Object v, boolean scale, String where, List<String> notes) {
        Molang[] out = new Molang[3];
        if (v instanceof List<?> l) {
            for (int i = 0; i < 3; i++) {
                out[i] = i < l.size() ? Molang.of(l.get(i), where, notes) : Molang.constant(scale ? 1 : 0);
            }
        } else {
            Molang one = Molang.of(v, where, notes);
            out[0] = one;
            out[1] = one;
            out[2] = one;
        }
        return out;
    }

    /**
     * A channel's value at {@code t} seconds: between two keyframes, the first one's
     * {@code post} and the next one's {@code pre}, linearly or (if either is smooth) along a
     * Catmull-Rom curve through the keyframes around them.
     */
    static double[] sample(Channel c, double t, Scene scene) {
        List<Key> keys = c.keys();
        if (keys.isEmpty()) {
            return null;
        }
        Key first = keys.getFirst();
        if (keys.size() == 1 || t <= first.time()) {
            return eval(t <= first.time() ? first.pre() : first.post(), scene);
        }
        Key last = keys.getLast();
        if (t >= last.time()) {
            return eval(last.post(), scene);
        }
        int i = 0;
        while (i + 1 < keys.size() && keys.get(i + 1).time() <= t) {
            i++;
        }
        Key a = keys.get(i);
        Key b = keys.get(i + 1);
        double span = b.time() - a.time();
        double alpha = span <= 0 ? 1 : (t - a.time()) / span;
        double[] from = eval(a.post(), scene);
        double[] to = eval(b.pre(), scene);
        double[] out = new double[3];
        if (a.smooth() || b.smooth()) {
            double[] before = i > 0 ? eval(keys.get(i - 1).post(), scene) : from;
            double[] after = i + 2 < keys.size() ? eval(keys.get(i + 2).pre(), scene) : to;
            for (int k = 0; k < 3; k++) {
                out[k] = catmullRom(before[k], from[k], to[k], after[k], alpha);
            }
        } else {
            for (int k = 0; k < 3; k++) {
                out[k] = from[k] + (to[k] - from[k]) * alpha;
            }
        }
        return out;
    }

    private static double[] eval(Molang[] v, Scene scene) {
        return new double[]{v[0].eval(scene), v[1].eval(scene), v[2].eval(scene)};
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /**
     * The part of Molang animations use: numbers; {@code + - * /}; comparisons, {@code &&},
     * {@code ||}, {@code !} and {@code ?:}; parentheses; {@code math.} {@code sin}, {@code cos}
     * (in degrees, as in Bedrock), {@code abs}, {@code min}, {@code max}, {@code clamp},
     * {@code lerp}, {@code sqrt}, {@code pow}, {@code exp}, {@code ln}, {@code floor},
     * {@code ceil}, {@code round}, {@code trunc}, {@code mod}, {@code random}, {@code pi}; and
     * {@code query.} (or {@code q.}) {@code anim_time}, {@code life_time}, {@code ground_speed},
     * {@code modified_move_speed}, {@code modified_distance_moved}, {@code head_x_rotation},
     * {@code head_y_rotation}. Variables ({@code variable.}, {@code v.}, {@code temp.}) and
     * unknown queries are 0; statements ({@code ;}, {@code return}) aren't supported.
     */
    interface Molang {

        double eval(Scene s);

        static Molang constant(double v) {
            return s -> v;
        }

        static Molang of(Object v, String where, List<String> notes) {
            if (v instanceof Number n) {
                return constant(n.doubleValue());
            }
            if (v instanceof String text) {
                String t = text.trim();
                if (t.isEmpty()) {
                    return constant(0);
                }
                try {
                    return new Parser(t, where, notes).parse();
                } catch (IllegalArgumentException e) {
                    notes.add(where + ": '" + t + "' " + e.getMessage() + "; read as 0");
                    return constant(0);
                }
            }
            return constant(0);
        }
    }

    /** Recursive descent over the Molang subset above; builds a tree of lambdas once. */
    static final class Parser {
        private final String s;
        private final String where;
        private final List<String> notes;
        private int i;

        Parser(String s, String where, List<String> notes) {
            this.s = s.toLowerCase(Locale.ROOT);
            this.where = where;
            this.notes = notes;
        }

        Molang parse() {
            if (s.contains(";") || s.startsWith("return")) {
                throw new IllegalArgumentException("uses statements, which aren't supported");
            }
            Molang e = ternary();
            ws();
            if (i != s.length()) {
                throw new IllegalArgumentException("has something unexpected at '" + s.substring(i) + "'");
            }
            return e;
        }

        private Molang ternary() {
            Molang c = or();
            ws();
            if (eat("?")) {
                Molang a = ternary();
                ws();
                if (!eat(":")) {
                    throw new IllegalArgumentException("has a ? without a :");
                }
                Molang b = ternary();
                return s -> c.eval(s) != 0 ? a.eval(s) : b.eval(s);
            }
            return c;
        }

        private Molang or() {
            Molang l = and();
            while (true) {
                ws();
                if (eat("||")) {
                    Molang a = l;
                    Molang r = and();
                    l = s -> a.eval(s) != 0 || r.eval(s) != 0 ? 1 : 0;
                } else {
                    return l;
                }
            }
        }

        private Molang and() {
            Molang l = comparison();
            while (true) {
                ws();
                if (eat("&&")) {
                    Molang a = l;
                    Molang r = comparison();
                    l = s -> a.eval(s) != 0 && r.eval(s) != 0 ? 1 : 0;
                } else {
                    return l;
                }
            }
        }

        private Molang comparison() {
            Molang l = additive();
            ws();
            for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
                if (eat(op)) {
                    Molang a = l;
                    Molang r = additive();
                    return switch (op) {
                        case "==" -> s -> a.eval(s) == r.eval(s) ? 1 : 0;
                        case "!=" -> s -> a.eval(s) != r.eval(s) ? 1 : 0;
                        case "<=" -> s -> a.eval(s) <= r.eval(s) ? 1 : 0;
                        case ">=" -> s -> a.eval(s) >= r.eval(s) ? 1 : 0;
                        case "<" -> s -> a.eval(s) < r.eval(s) ? 1 : 0;
                        default -> s -> a.eval(s) > r.eval(s) ? 1 : 0;
                    };
                }
            }
            return l;
        }

        private Molang additive() {
            Molang l = multiplicative();
            while (true) {
                ws();
                if (eat("+")) {
                    Molang a = l;
                    Molang r = multiplicative();
                    l = s -> a.eval(s) + r.eval(s);
                } else if (peek('-')) {
                    i++;
                    Molang a = l;
                    Molang r = multiplicative();
                    l = s -> a.eval(s) - r.eval(s);
                } else {
                    return l;
                }
            }
        }

        private Molang multiplicative() {
            Molang l = unary();
            while (true) {
                ws();
                if (eat("*")) {
                    Molang a = l;
                    Molang r = unary();
                    l = s -> a.eval(s) * r.eval(s);
                } else if (eat("/")) {
                    Molang a = l;
                    Molang r = unary();
                    l = s -> {
                        double d = r.eval(s);
                        return d == 0 ? 0 : a.eval(s) / d;
                    };
                } else {
                    return l;
                }
            }
        }

        private Molang unary() {
            ws();
            if (eat("-")) {
                Molang e = unary();
                return s -> -e.eval(s);
            }
            if (eat("+")) {
                return unary();
            }
            if (peek('!') && !s.startsWith("!=", i)) {
                i++;
                Molang e = unary();
                return s -> e.eval(s) == 0 ? 1 : 0;
            }
            return primary();
        }

        private Molang primary() {
            ws();
            if (eat("(")) {
                Molang e = ternary();
                ws();
                if (!eat(")")) {
                    throw new IllegalArgumentException("has an unclosed (");
                }
                return e;
            }
            if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                int start = i;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                if (i < s.length() && s.charAt(i) == 'f') {
                    i++; // 1.5f, as some exporters write
                }
                double v = Double.parseDouble(s.substring(start, i).replace("f", ""));
                return Molang.constant(v);
            }
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException(i < s.length() ? "has an unexpected '" + s.charAt(i) + "'" : "ends too soon");
            }
            String name = s.substring(start, i);
            ws();
            List<Molang> args = new ArrayList<>();
            if (eat("(")) {
                ws();
                if (!eat(")")) {
                    do {
                        args.add(ternary());
                        ws();
                    } while (eat(","));
                    if (!eat(")")) {
                        throw new IllegalArgumentException("has an unclosed call to " + name);
                    }
                }
            }
            return name(name, args);
        }

        private Molang name(String name, List<Molang> a) {
            String n = name.startsWith("q.") ? "query." + name.substring(2)
                    : name.startsWith("v.") ? "variable." + name.substring(2)
                    : name.startsWith("t.") ? "temp." + name.substring(2) : name;
            switch (n) {
                case "query.anim_time": return s -> s.animTime();
                case "query.life_time": return s -> s.lifeTime();
                case "query.ground_speed": return s -> s.groundSpeed();
                case "query.modified_move_speed": return s -> s.groundSpeed();
                case "query.modified_distance_moved": return s -> s.distanceMoved();
                case "query.head_x_rotation": return s -> s.headX();
                case "query.head_y_rotation": return s -> s.headY();
                case "math.pi": return Molang.constant(Math.PI);
                default: break;
            }
            if (n.startsWith("variable.") || n.startsWith("temp.")) {
                return Molang.constant(0);
            }
            if (n.startsWith("math.")) {
                return math(n.substring(5), a);
            }
            notes.add(where + ": '" + name + "' isn't something MiracleToolChain knows; read as 0");
            return Molang.constant(0);
        }

        private Molang math(String f, List<Molang> a) {
            int need = switch (f) {
                case "sin", "cos", "abs", "sqrt", "exp", "ln", "floor", "ceil", "round", "trunc" -> 1;
                case "min", "max", "pow", "mod", "random" -> 2;
                case "clamp", "lerp" -> 3;
                default -> throw new IllegalArgumentException("calls math." + f + ", which isn't supported");
            };
            if (a.size() != need) {
                throw new IllegalArgumentException("calls math." + f + " with " + a.size() + " argument(s), not " + need);
            }
            Molang x = a.get(0);
            Molang y = need > 1 ? a.get(1) : null;
            Molang z = need > 2 ? a.get(2) : null;
            return switch (f) {
                case "sin" -> s -> Math.sin(Math.toRadians(x.eval(s)));
                case "cos" -> s -> Math.cos(Math.toRadians(x.eval(s)));
                case "abs" -> s -> Math.abs(x.eval(s));
                case "sqrt" -> s -> Math.sqrt(Math.max(0, x.eval(s)));
                case "exp" -> s -> Math.exp(x.eval(s));
                case "ln" -> s -> Math.log(x.eval(s));
                case "floor" -> s -> Math.floor(x.eval(s));
                case "ceil" -> s -> Math.ceil(x.eval(s));
                case "round" -> s -> Math.round(x.eval(s));
                case "trunc" -> s -> (double) (long) x.eval(s);
                case "min" -> s -> Math.min(x.eval(s), y.eval(s));
                case "max" -> s -> Math.max(x.eval(s), y.eval(s));
                case "pow" -> s -> Math.pow(x.eval(s), y.eval(s));
                case "mod" -> s -> {
                    double d = y.eval(s);
                    return d == 0 ? 0 : x.eval(s) % d;
                };
                case "random" -> s -> {
                    double lo = x.eval(s);
                    return lo + Math.random() * (y.eval(s) - lo);
                };
                case "clamp" -> s -> Math.min(Math.max(x.eval(s), y.eval(s)), z.eval(s));
                default -> s -> {
                    double from = x.eval(s);
                    return from + (y.eval(s) - from) * z.eval(s);
                };
            };
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private boolean eat(String tok) {
            if (s.startsWith(tok, i)) {
                i += tok.length();
                return true;
            }
            return false;
        }
    }
}
