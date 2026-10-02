package io.github.hronosin.miracle.horizon;

import io.github.hronosin.miracle.api.Mods;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/**
 * Penrose (boring name: Values): the loader's own math, for your mod's numbers. MiracleLoader
 * folds every mod's changes to a game value into one result by fixed rules, in any load order;
 * Penrose does the same for values a mod declares itself: a sword's damage, a spawn rate, whether
 * a feature is on. Other mods change them by name, without depending on yours, and nobody has to
 * agree on a shared class: a number is a number.
 *
 * <pre>{@code
 * // in your mod
 * static final Penrose.Number BLADE = Penrose.number("mymod:blade_damage", 7).range(0, 100);
 * double damage = BLADE.get(player);
 *
 * // in someone else's mod; nothing happens unless mymod is there
 * Penrose.touch("mymod:blade_damage").add(2);
 * Penrose.touch("mymod:blade_damage", Player.class).when(Player::isCrouching).multiply(1.5);
 *
 * // and to see why it came out as it did: /horizon why mymod:blade_damage @s
 * }</pre>
 *
 * <p><b>The rules</b>, the same as MiracleLoader's layers and MiracleToolChain's Blessings:
 * {@code value = range( clamp( (base + Σ adds) × Π factors ) )}.
 * <ul>
 * <li>{@code set} replaces the base. The highest priority wins; within one mod, its last {@code set}
 *     at that priority. Two mods setting different values at the same (highest) priority is a
 *     conflict, and then nobody wins: the declared base stays.</li>
 * <li>{@code add}s are summed, {@code multiply} factors multiplied, both in an order fixed by mod id,
 *     so the result is the same to the last bit whatever order mods were loaded in.</li>
 * <li>{@code clamp} ranges all apply (their intersection). Ranges that don't overlap are a
 *     conflict, and then none of them applies.</li>
 * <li>The owner's {@link Number#range} applies last, always.</li>
 * </ul>
 * <p>Choices ({@link #flag}, {@link #choice}) only take {@code set}, by the same rule. Conflicts
 * are logged once each, never thrown: a value is read in the middle of play, and a game that
 * stops there is worse than a value that stays at its base. {@code /horizon why} shows them.
 *
 * <p>Layers can be added and removed at any time, from any thread. A condition or formula that
 * throws makes its layer not count, once logged.
 */
public final class Penrose {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SEQ = new AtomicLong();

    private Penrose() {
    }

    // --- declaring -------------------------------------------------------------------------------

    /** Declares a number of your own. {@code id}: "yourmod:name", once per id. */
    public static Number number(String id, double base) {
        return declare(new Number(slot(id), caller(), base));
    }

    /** Declares a yes-or-no of your own. */
    public static Choice<Boolean> flag(String id, boolean base) {
        return declare(new Choice<>(slot(id), caller(), base));
    }

    /** Declares a choice of your own: one of some values of a type (an enum, a string...). */
    public static <T> Choice<T> choice(String id, T base) {
        return declare(new Choice<>(slot(id), caller(), Objects.requireNonNull(base, "A choice needs a base value.")));
    }

    private static <V extends Value<?>> V declare(V v) {
        synchronized (v.slot) {
            Value<?> old = v.slot.declared;
            if (old != null) {
                throw new IllegalArgumentException("'" + v.slot.id + "' is already declared, by " + old.owner + ".");
            }
            v.slot.declared = v;
        }
        return v;
    }

    // --- touching --------------------------------------------------------------------------------

    /** Changes to a value by its id, whoever declared it, and whether or not it's declared yet. */
    public static Touch<Object> touch(String id) {
        return new Touch<>(slot(id), null, null, 0, null);
    }

    /** The same, but only when the value is read for a {@code context} of this type (a player, say). */
    public static <C> Touch<C> touch(String id, Class<C> context) {
        return new Touch<>(slot(id), Objects.requireNonNull(context), null, 0, null);
    }

    /** The value declared under this id, if any. */
    public static Optional<Value<?>> declared(String id) {
        Slot s = SLOTS.get(id);
        return Optional.ofNullable(s == null ? null : s.declared);
    }

    /** Every id anyone has declared or touched, sorted. */
    public static List<String> ids() {
        return new ArrayList<>(new TreeMap<>(SLOTS).keySet());
    }

    /** The layers on an id, in the order they're applied. */
    public static List<Layer> layers(String id) {
        Slot s = SLOTS.get(id);
        return s == null ? List.of() : List.of(s.sorted);
    }

    private static Slot slot(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A Penrose id looks like \"yourmod:name\" (lowercase, digits, _ . - /), not \"" + id + "\".");
        }
        return SLOTS.computeIfAbsent(id, Slot::new);
    }

    /** One id: what was declared there and every layer on it. */
    static final class Slot {
        final String id;
        volatile Value<?> declared;
        final List<Layer> layers = new CopyOnWriteArrayList<>();
        volatile Layer[] sorted = new Layer[0];
        volatile boolean constant = true;
        volatile long version;

        Slot(String id) {
            this.id = id;
        }

        synchronized void changed() {
            Layer[] s = layers.toArray(new Layer[0]);
            java.util.Arrays.sort(s, ORDER);
            boolean c = true;
            for (Layer l : s) {
                c &= l.type == null && l.when == null && l.formula == null;
            }
            sorted = s;
            constant = c;
            version++;
        }
    }

    private static final Comparator<Layer> ORDER = Comparator.<Layer, String>comparing(l -> l.mod)
            .thenComparing(l -> l.op)
            .thenComparingInt(l -> l.priority)
            .thenComparingDouble(l -> l.formula == null ? l.amount : Double.NaN)
            .thenComparingLong(l -> l.seq);

    // --- layers ----------------------------------------------------------------------------------

    /** The kinds of change, in the order they're applied. */
    public enum Op {
        SET, ADD, MULTIPLY, CLAMP
    }

    /** One mod's change to one value. Keep it to {@link #remove()} it later. */
    public static final class Layer {
        final Slot slot;
        final String mod;
        final Op op;
        final double amount;
        final ToDoubleFunction<Object> formula;
        final double min;
        final double max;
        final Object value;
        final int priority;
        final Class<?> type;
        final Predicate<Object> when;
        final long seq = SEQ.incrementAndGet();

        private Layer(Slot slot, String mod, Op op, double amount, ToDoubleFunction<Object> formula, double min, double max,
                Object value, int priority, Class<?> type, Predicate<Object> when) {
            this.slot = slot;
            this.mod = mod;
            this.op = op;
            this.amount = amount;
            this.formula = formula;
            this.min = min;
            this.max = max;
            this.value = value;
            this.priority = priority;
            this.type = type;
            this.when = when;
        }

        /** Takes this change back. */
        public void remove() {
            if (slot.layers.remove(this)) {
                slot.changed();
            }
        }

        /** The mod that made it. */
        public String mod() {
            return mod;
        }

        public Op op() {
            return op;
        }

        public String id() {
            return slot.id;
        }

        boolean applies(Object ctx) {
            if (type != null && !type.isInstance(ctx)) {
                return false;
            }
            if (when == null) {
                return true;
            }
            try {
                return when.test(ctx);
            } catch (RuntimeException e) {
                report(slot.id + ": a condition of " + mod + " threw " + e + "; that layer doesn't count.");
                return false;
            }
        }

        /** The amount for this context, or NaN if the formula failed. */
        double amount(Object ctx) {
            if (formula == null) {
                return amount;
            }
            try {
                return formula.applyAsDouble(ctx);
            } catch (RuntimeException e) {
                report(slot.id + ": a formula of " + mod + " threw " + e + "; that layer doesn't count.");
                return Double.NaN;
            }
        }

        @Override
        public String toString() {
            return what() + " (" + mod + ")";
        }

        /** What it does, without whose it is. */
        String what() {
            String what = switch (op) {
                case SET -> "set " + show(value) + (priority != 0 ? " at priority " + priority : "");
                case ADD -> formula != null ? "add (formula)" : "add " + num(amount);
                case MULTIPLY -> formula != null ? "multiply by (formula)" : "multiply by " + num(amount);
                case CLAMP -> "clamp " + num(min) + ".." + num(max);
            };
            String cond = (type != null ? " for " + type.getSimpleName() : "") + (when != null ? " when..." : "");
            return what + cond;
        }
    }

    /** Where changes to one value are made from; narrowing returns a new one, like Blessings. */
    public static final class Touch<C> {
        private final Slot slot;
        private final Class<C> type;
        private final Predicate<Object> when;
        private final int priority;
        private final String by;

        private Touch(Slot slot, Class<C> type, Predicate<Object> when, int priority, String by) {
            this.slot = slot;
            this.type = type;
            this.when = when;
            this.priority = priority;
            this.by = by;
        }

        /** Only when this holds, checked every time the value is read. Conditions add up: all must hold. */
        @SuppressWarnings("unchecked")
        public Touch<C> when(Predicate<? super C> condition) {
            Predicate<Object> c = o -> ((Predicate<Object>) (Predicate<?>) condition).test(o);
            Predicate<Object> outer = when;
            return new Touch<>(slot, type, outer == null ? c : o -> outer.test(o) && c.test(o), priority, by);
        }

        /** Priority for {@code set} (default 0): the highest wins. Adding, multiplying and clamping always all apply. */
        public Touch<C> priority(int p) {
            return new Touch<>(slot, type, when, p, by);
        }

        /** In whose name; by default the mod whose code calls. */
        public Touch<C> by(String modId) {
            return new Touch<>(slot, type, when, priority, Objects.requireNonNull(modId));
        }

        public Layer add(double amount) {
            return put(Op.ADD, finite(amount), null, 0, 0, null);
        }

        /** Adds a formula of the context, worked out every time the value is read. */
        @SuppressWarnings("unchecked")
        public Layer add(ToDoubleFunction<? super C> amount) {
            return put(Op.ADD, 0, o -> ((ToDoubleFunction<Object>) (ToDoubleFunction<?>) amount).applyAsDouble(o), 0, 0, null);
        }

        public Layer multiply(double factor) {
            return put(Op.MULTIPLY, finite(factor), null, 0, 0, null);
        }

        @SuppressWarnings("unchecked")
        public Layer multiply(ToDoubleFunction<? super C> factor) {
            return put(Op.MULTIPLY, 1, o -> ((ToDoubleFunction<Object>) (ToDoubleFunction<?>) factor).applyAsDouble(o), 0, 0, null);
        }

        public Layer clamp(double min, double max) {
            if (!(min <= max)) {
                throw new IllegalArgumentException("clamp(" + min + ", " + max + "): min must not be bigger than max.");
            }
            return put(Op.CLAMP, 0, null, min, max, null);
        }

        public Layer set(double value) {
            return put(Op.SET, finite(value), null, 0, 0, value);
        }

        public Layer set(boolean value) {
            return put(Op.SET, 0, null, 0, 0, value);
        }

        /** For choices: one of the choice's values. */
        public Layer set(Object value) {
            return put(Op.SET, 0, null, 0, 0, Objects.requireNonNull(value, "set(null)"));
        }

        private Layer put(Op op, double amount, ToDoubleFunction<Object> formula, double min, double max, Object value) {
            Layer l = new Layer(slot, by != null ? by : caller(), op, amount, formula, min, max, value,
                    op == Op.SET ? priority : 0, type, when);
            slot.layers.add(l);
            slot.changed();
            return l;
        }

        private static double finite(double d) {
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("Penrose takes finite numbers, not " + d + ".");
            }
            return d;
        }
    }

    // --- values ----------------------------------------------------------------------------------

    /** A declared value: a {@link Number} or a {@link Choice}. */
    public abstract static sealed class Value<T> permits Number, Choice {
        final Slot slot;
        final String owner;

        Value(Slot slot, String owner) {
            this.slot = slot;
            this.owner = owner;
        }

        public String id() {
            return slot.id;
        }

        /** The mod that declared it. */
        public String owner() {
            return owner;
        }

        /** The value for no context in particular. */
        public T get() {
            return value(null);
        }

        abstract T value(Object context);

        /** How the value came out for this context, step by step, conflicts included. */
        public abstract String explain(Object context);

        /** The conflicts, if any, when read for this context. */
        public List<String> conflicts(Object context) {
            List<String> out = new ArrayList<>();
            fold(context, out, null);
            return out;
        }

        abstract Object fold(Object context, List<String> conflicts, List<String> steps);

        /** Picks the winning set among the layers, or null (none, or a tie: then nobody wins). */
        Layer winningSet(Layer[] layers, Object ctx, Predicate<Object> fits, List<String> conflicts, List<String> steps) {
            // each mod's last applicable set per priority
            Map<Integer, Map<String, Layer>> byPriority = new TreeMap<>(Comparator.reverseOrder());
            for (Layer l : layers) {
                if (l.op != Op.SET || !l.applies(ctx)) {
                    continue;
                }
                if (!fits.test(l.value)) {
                    mismatch(l, conflicts, steps);
                    continue;
                }
                Map<String, Layer> mods = byPriority.computeIfAbsent(l.priority, k -> new TreeMap<>());
                Layer had = mods.get(l.mod);
                if (had == null || had.seq < l.seq) {
                    mods.put(l.mod, l);
                }
            }
            if (byPriority.isEmpty()) {
                return null;
            }
            var top = byPriority.entrySet().iterator().next();
            Layer first = null;
            boolean agree = true;
            for (Layer l : top.getValue().values()) {
                if (first == null) {
                    first = l;
                } else if (!same(first.value, l.value)) {
                    agree = false;
                }
            }
            if (!agree) {
                StringBuilder sb = new StringBuilder(slot.id + ": a tie at priority " + top.getKey() + ":");
                for (Layer l : top.getValue().values()) {
                    sb.append(" '").append(l.mod).append("' sets ").append(show(l.value)).append(';');
                }
                sb.append(" nobody wins, so the base stays. One of them needs a higher priority.");
                conflict(sb.toString(), conflicts);
                return null;
            }
            return first;
        }

        void mismatch(Layer l, List<String> conflicts, List<String> steps) {
            conflict(slot.id + ": '" + l.mod + "' tries to " + l.what() + " on a " + kind() + "; that layer doesn't count.", conflicts);
        }

        abstract String kind();

        @Override
        public String toString() {
            return kind() + " " + slot.id + " (" + owner + ")";
        }
    }

    /** A number of your own; see {@link Penrose} for how changes combine. */
    public static final class Number extends Value<Double> {
        private final double base;
        private volatile double low = Double.NEGATIVE_INFINITY;
        private volatile double high = Double.POSITIVE_INFINITY;
        private volatile long cachedVersion = -1;
        private volatile double cached;

        private Number(Slot slot, String owner, double base) {
            super(slot, owner);
            if (!Double.isFinite(base)) {
                throw new IllegalArgumentException("A Penrose number's base must be finite, not " + base + ".");
            }
            this.base = base;
        }

        /** Hard bounds of your own, applied last, whatever anyone else does. */
        public Number range(double min, double max) {
            if (!(min <= max)) {
                throw new IllegalArgumentException("range(" + min + ", " + max + "): min must not be bigger than max.");
            }
            low = min;
            high = max;
            slot.changed();
            return this;
        }

        public double base() {
            return base;
        }

        @Override
        public Double get() {
            return getAsDouble(null);
        }

        /** The value for no context in particular. */
        public double getAsDouble() {
            return getAsDouble(null);
        }

        /** The value for a context (an entity, a world, whatever the layers were written for). */
        public double getAsDouble(Object context) {
            if (context == null && slot.constant) {
                long v = slot.version;
                if (cachedVersion == v) {
                    return cached;
                }
                double d = (double) fold(null, null, null);
                cached = d;
                cachedVersion = v;
                return d;
            }
            return (double) fold(context, null, null);
        }

        /** Shorter for {@link #getAsDouble(Object)}. */
        public double get(Object context) {
            return getAsDouble(context);
        }

        /** Rounded to the nearest whole number. */
        public int getInt(Object context) {
            return (int) Math.round(getAsDouble(context));
        }

        /** Everyone's changes applied to a base of your choosing (the game's own value, say) instead of the declared one. */
        public double apply(double base, Object context) {
            return (double) fold(context, null, null, base);
        }

        @Override
        Double value(Object context) {
            return getAsDouble(context);
        }

        @Override
        Object fold(Object ctx, List<String> conflicts, List<String> steps) {
            return fold(ctx, conflicts, steps, base);
        }

        private Object fold(Object ctx, List<String> conflicts, List<String> steps, double declared) {
            Layer[] layers = slot.sorted;
            double v = declared;
            if (steps != null) {
                steps.add("base " + num(declared) + " (" + owner + ")");
            }
            Layer set = winningSet(layers, ctx, o -> o instanceof java.lang.Number, conflicts, steps);
            if (set != null) {
                v = ((java.lang.Number) set.value).doubleValue();
                step(steps, "= " + num(v), set);
            }
            for (Layer l : layers) {
                if (l.op == Op.ADD && l.applies(ctx)) {
                    double a = l.amount(ctx);
                    if (Double.isFinite(a)) {
                        v += a;
                        step(steps, (a < 0 ? "- " + num(-a) : "+ " + num(a)), l);
                    }
                }
            }
            for (Layer l : layers) {
                if (l.op == Op.MULTIPLY && l.applies(ctx)) {
                    double f = l.amount(ctx);
                    if (Double.isFinite(f)) {
                        v *= f;
                        step(steps, "× " + num(f), l);
                    }
                }
            }
            double lo = Double.NEGATIVE_INFINITY;
            double hi = Double.POSITIVE_INFINITY;
            List<Layer> clamps = new ArrayList<>();
            for (Layer l : layers) {
                if (l.op == Op.CLAMP && l.applies(ctx)) {
                    clamps.add(l);
                    lo = Math.max(lo, l.min);
                    hi = Math.min(hi, l.max);
                }
            }
            if (!clamps.isEmpty()) {
                if (lo > hi) {
                    StringBuilder sb = new StringBuilder(slot.id + ": ranges that don't overlap:");
                    for (Layer l : clamps) {
                        sb.append(" '").append(l.mod).append("' ").append(num(l.min)).append("..").append(num(l.max)).append(';');
                    }
                    sb.append(" none of them applies.");
                    conflict(sb.toString(), conflicts);
                    if (steps != null) {
                        steps.add("(clamps skipped: they don't overlap)");
                    }
                } else {
                    double c = Math.max(lo, Math.min(hi, v));
                    if (steps != null) {
                        steps.add("clamp " + num(lo) + ".." + num(hi) + " (" + mods(clamps) + ")" + (c != v ? " → " + num(c) : ""));
                    }
                    v = c;
                }
            }
            double r = Math.max(low, Math.min(high, v));
            if (steps != null && (low != Double.NEGATIVE_INFINITY || high != Double.POSITIVE_INFINITY)) {
                steps.add("range " + num(low) + ".." + num(high) + " (" + owner + ")" + (r != v ? " → " + num(r) : ""));
            }
            return r;
        }

        @Override
        public String explain(Object context) {
            List<String> steps = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            double v = (double) fold(context, conflicts, steps);
            return render(this, num(v), context, steps, conflicts);
        }

        @Override
        String kind() {
            return "number";
        }
    }

    /** One of some values (a flag is a choice of true or false); changed only by {@code set}. */
    public static final class Choice<T> extends Value<T> {
        private final T base;
        private final Class<?> type;

        private Choice(Slot slot, String owner, T base) {
            super(slot, owner);
            this.base = base;
            this.type = base instanceof Enum<?> e ? e.getDeclaringClass() : base.getClass();
        }

        /** The value for a context. */
        public T get(Object context) {
            return value(context);
        }

        public T base() {
            return base;
        }

        @Override
        @SuppressWarnings("unchecked")
        T value(Object context) {
            return (T) fold(context, null, null);
        }

        @Override
        Object fold(Object ctx, List<String> conflicts, List<String> steps) {
            Layer[] layers = slot.sorted;
            if (steps != null) {
                steps.add("base " + show(base) + " (" + owner + ")");
            }
            for (Layer l : layers) {
                if (l.op != Op.SET && l.applies(ctx)) {
                    mismatch(l, conflicts, steps);
                }
            }
            Layer set = winningSet(layers, ctx, type::isInstance, conflicts, steps);
            if (set == null) {
                return base;
            }
            step(steps, "= " + show(set.value), set);
            return set.value;
        }

        @Override
        public String explain(Object context) {
            List<String> steps = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            Object v = fold(context, conflicts, steps);
            return render(this, show(v), context, steps, conflicts);
        }

        @Override
        String kind() {
            return type == Boolean.class ? "flag" : "choice";
        }
    }

    /** Conflicts across every declared value, read for no context in particular. */
    public static List<String> conflicts() {
        List<String> out = new ArrayList<>();
        for (String id : ids()) {
            Value<?> v = SLOTS.get(id).declared;
            if (v != null) {
                out.addAll(v.conflicts(null));
            }
        }
        return out;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static void step(List<String> steps, String what, Layer l) {
        if (steps != null) {
            String cond = (l.type != null ? ", for " + l.type.getSimpleName() : "") + (l.when != null ? ", when..." : "");
            steps.add(what + " (" + l.mod + (l.op == Op.SET && l.priority != 0 ? ", priority " + l.priority : "") + cond + ")");
        }
    }

    private static void conflict(String what, List<String> conflicts) {
        if (conflicts != null) {
            conflicts.add(what);
        }
        report(what);
    }

    private static void report(String what) {
        if (REPORTED.add(what)) {
            EventHorizon.LOG.accept("Penrose: " + what);
        }
    }

    private static String render(Value<?> v, String result, Object ctx, List<String> steps, List<String> conflicts) {
        StringBuilder sb = new StringBuilder(v.slot.id).append(" = ").append(result);
        if (ctx != null) {
            sb.append(" (for ").append(GameKeys.describe(ctx)).append(')');
        }
        for (String s : steps) {
            sb.append("\n  ").append(s);
        }
        for (String c : conflicts) {
            sb.append("\n  ! ").append(c);
        }
        return sb.toString();
    }

    private static String mods(List<Layer> layers) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        layers.forEach(l -> m.put(l.mod, true));
        return String.join(", ", m.keySet());
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof java.lang.Number x && b instanceof java.lang.Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return Objects.equals(a, b);
    }

    static String num(double d) {
        if (d == Double.POSITIVE_INFINITY) {
            return "∞";
        }
        if (d == Double.NEGATIVE_INFINITY) {
            return "-∞";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d) + ".0";
        }
        String s = String.format(java.util.Locale.ROOT, "%.6f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    private static String show(Object o) {
        if (o instanceof java.lang.Number n) {
            return num(n.doubleValue());
        }
        return o instanceof String s ? '"' + s + '"' : String.valueOf(o);
    }

    /** The mod whose code is calling: the first class outside Event Horizon, by its jar. */
    static String caller() {
        Optional<Class<?>> c = WALKER.walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(k -> !k.getName().startsWith("io.github.hronosin.miracle.horizon."))
                .findFirst());
        if (c.isEmpty()) {
            return "event-horizon";
        }
        try {
            Optional<Mods.Mod> m = Mods.owner(c.get());
            if (m.isPresent()) {
                return m.get().id();
            }
        } catch (RuntimeException notRunning) {
            // outside a game (a test, a tool): name the package instead
        }
        return c.get().getPackageName();
    }

    /** Every id's slot, for Telescope. */
    static Collection<Slot> slots() {
        return SLOTS.values();
    }
}
