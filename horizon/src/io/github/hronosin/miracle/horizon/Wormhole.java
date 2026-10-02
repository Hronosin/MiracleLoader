package io.github.hronosin.miracle.horizon;

import io.github.hronosin.miracle.api.Mods;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wormhole (boring name: Bridges): links between mods that don't need each other. Two kinds.
 *
 * <p><b>Bridges</b>: your code for another mod, run only if that mod is here. The bridge is a class
 * of its own, named by a string, so nothing of the other mod is touched (or even loaded) when it's
 * missing. List the other mod in {@code entangles} in {@code miracle.mod.toml}, so it's ready before
 * yours; compile against its jar with {@code against = ["libs/create.jar"]} in
 * {@code miracle.project.toml}.
 *
 * <pre>{@code
 * // in onLaunch()
 * Wormhole.to("create>=6.0").open("com.me.mymod.compat.CreateBridge");    // a Runnable, run once, now
 * }</pre>
 *
 * <p><b>Services</b>: something one mod offers under a name, for any mod that asks, with no class
 * of either in common. Use the JDK's types ({@code Function}, {@code Predicate}, {@code Map}...) or
 * a shared interface jar. The highest priority wins; two different offers at the same priority are
 * a conflict, and then nobody wins: the seeker gets nothing and goes on without (logged once).
 *
 * <pre>{@code
 * Wormhole.offer("mymod:grind", (Function<ItemStack, ItemStack>) this::grind);            // mymod
 * Wormhole.seek("mymod:grind", Function.class).ifPresent(g -> ...);                      // anyone
 * }</pre>
 *
 * <p>A bridge that throws is closed and logged, never rethrown: the game goes on without it.
 * {@code /horizon bridges} shows them all.
 */
public final class Wormhole {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final List<Bridge> BRIDGES = new CopyOnWriteArrayList<>();
    private static final Map<String, List<Offer>> OFFERS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Wormhole() {
    }

    // --- bridges ---------------------------------------------------------------------------------

    /** A bridge to a mod: {@code "create"}, or {@code "create>=6.0"} for a version at least that. */
    public static Bridge to(String mod) {
        String spec = Objects.requireNonNull(mod).strip();
        int ge = spec.indexOf(">=");
        String id = (ge < 0 ? spec : spec.substring(0, ge)).strip();
        String atLeast = ge < 0 ? null : spec.substring(ge + 2).strip();
        if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("'" + mod + "' is not a mod id (optionally followed by >=version).");
        }
        Class<?> caller = callerClass();
        return new Bridge(owner(caller), id, atLeast, caller == null ? Wormhole.class.getClassLoader() : caller.getClassLoader());
    }

    /** Every bridge anyone tried to open, in order. */
    public static List<Bridge> bridges() {
        return List.copyOf(BRIDGES);
    }

    /** What happened to a bridge. */
    public enum State {
        /** Not opened yet. */
        WAITING,
        /** Run, and done. */
        OPEN,
        /** The other mod isn't here, or not in a version that's new enough: nothing was loaded. */
        CLOSED,
        /** The bridge was loaded but failed; the game goes on without it. */
        FAILED
    }

    /** A link from one mod to another; {@link #open(String)} runs it, if the other side is there. */
    public static final class Bridge {
        private final String from;
        private final String to;
        private final String atLeast;
        private final ClassLoader loader;
        private volatile String bridgeClass;
        private volatile State state = State.WAITING;
        private volatile String why = "";

        private Bridge(String from, String to, String atLeast, ClassLoader loader) {
            this.from = from;
            this.to = to;
            this.atLeast = atLeast;
            this.loader = loader;
        }

        /** Whether the other mod is here, in a version that's new enough; nothing is loaded to find out. */
        public boolean possible() {
            return reason() == null;
        }

        private String reason() {
            Optional<Mods.Mod> other;
            try {
                other = Mods.get(to);
            } catch (RuntimeException notRunning) {
                return "no MiracleLoader running";
            }
            if (other.isEmpty()) {
                return to + " isn't here";
            }
            if (atLeast != null && compare(other.get().version(), atLeast) < 0) {
                return to + " " + other.get().version() + " is here, older than " + atLeast;
            }
            return null;
        }

        /**
         * Loads {@code className} (a {@link Runnable} with a constructor that takes nothing) and runs
         * it, if the other mod is here; true if it ran without failing. Once per bridge.
         */
        public boolean open(String className) {
            synchronized (this) {
                if (state != State.WAITING) {
                    throw new IllegalStateException("This bridge to " + to + " was opened already (" + state + ").");
                }
                bridgeClass = className;
                BRIDGES.add(this);
                String closed = reason();
                if (closed != null) {
                    state = State.CLOSED;
                    why = closed;
                    return false;
                }
                try {
                    if (!entangled()) {
                        report(from + " opens a bridge to " + to + " without entangles = [\"" + to + "\"] in its miracle.mod.toml:"
                                + " " + to + " may not be ready yet. Add it, and " + to + " loads first.");
                    }
                    Class<?> c = Class.forName(className, true, loader);
                    if (!Runnable.class.isAssignableFrom(c)) {
                        throw new IllegalArgumentException(className + " isn't a Runnable: a bridge implements Runnable.");
                    }
                    ((Runnable) c.getDeclaredConstructor().newInstance()).run();
                    state = State.OPEN;
                    why = "";
                    EventHorizon.LOG.accept("Wormhole: " + from + " -> " + to + " open (" + className + ").");
                    return true;
                } catch (ClassNotFoundException e) {
                    fail("there's no class " + className + " in " + from, null);
                } catch (LinkageError e) {
                    fail("it needs something " + to + " doesn't have (" + e + "): a version it wasn't written for?", e);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    fail("its constructor threw " + e.getCause(), e.getCause());
                } catch (ReflectiveOperationException e) {
                    fail("it can't be made (" + e + "): a public constructor that takes nothing, please", e);
                } catch (RuntimeException e) {
                    fail("it threw " + e, e);
                }
                return false;
            }
        }

        private void fail(String what, Throwable t) {
            state = State.FAILED;
            why = what;
            EventHorizon.LOG.accept("Wormhole: " + from + " -> " + to + " failed and stays closed: " + what
                    + ". The game goes on without it.");
            if (t != null) {
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) {
                    EventHorizon.LOG.accept("    at " + st[i]);
                }
            }
        }

        private boolean entangled() {
            try {
                return Mods.entangled(from).contains(to);
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        }

        public String from() {
            return from;
        }

        public String to() {
            return to;
        }

        public State state() {
            return state;
        }

        /** Why it's closed or failed; empty when open. */
        public String why() {
            return why;
        }

        @Override
        public String toString() {
            return from + " -> " + to + (atLeast != null ? " >= " + atLeast : "") + ": " + state.name().toLowerCase(java.util.Locale.ROOT)
                    + (why.isEmpty() ? "" : " (" + why + ")") + (bridgeClass != null ? " [" + bridgeClass + "]" : "");
        }
    }

    // --- services --------------------------------------------------------------------------------

    /** Offers {@code service} under {@code id} ("yourmod:name") at priority 0. */
    public static Offer offer(String id, Object service) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("A service id looks like \"yourmod:name\", not \"" + id + "\".");
        }
        Offer o = new Offer(id, owner(callerClass()), Objects.requireNonNull(service, "offer(id, null)"));
        OFFERS.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(o);
        return o;
    }

    /** One mod's offer; keep it to change its priority or take it back. */
    public static final class Offer {
        private final String id;
        private final String mod;
        private final Object service;
        private volatile int priority;

        private Offer(String id, String mod, Object service) {
            this.id = id;
            this.mod = mod;
            this.service = service;
        }

        /** The highest priority wins (default 0). */
        public Offer priority(int p) {
            priority = p;
            return this;
        }

        public void withdraw() {
            List<Offer> l = OFFERS.get(id);
            if (l != null) {
                l.remove(this);
            }
        }

        public String mod() {
            return mod;
        }

        public int priority() {
            return priority;
        }

        @Override
        public String toString() {
            return id + " by " + mod + (priority != 0 ? " at priority " + priority : "") + " (" + service.getClass().getName() + ")";
        }
    }

    /**
     * The winning offer under {@code id} that is a {@code type}: the highest priority; empty if
     * there's none, or if two different ones tie for the top (logged once).
     */
    public static <T> Optional<T> seek(String id, Class<T> type) {
        List<Offer> fit = fitting(id, type);
        if (fit.isEmpty()) {
            return Optional.empty();
        }
        int top = fit.get(0).priority;
        List<Offer> best = fit.stream().filter(o -> o.priority == top).toList();
        Map<Object, Boolean> distinct = new IdentityHashMap<>();
        best.forEach(o -> distinct.put(o.service, true));
        if (distinct.size() > 1) {
            StringBuilder sb = new StringBuilder("Wormhole: a tie for " + id + " at priority " + top + ":");
            best.forEach(o -> sb.append(" '").append(o.mod).append("' (").append(o.service.getClass().getName()).append(");"));
            sb.append(" nobody wins, so whoever asks gets nothing. One of them needs a higher priority.");
            report(sb.toString());
            return Optional.empty();
        }
        return Optional.of(type.cast(best.get(0).service));
    }

    /** Every offer under {@code id} that is a {@code type}, highest priority first, then by mod id. */
    public static <T> List<T> all(String id, Class<T> type) {
        return fitting(id, type).stream().map(o -> type.cast(o.service)).toList();
    }

    /** Every offer, by id, for Telescope. */
    static Map<String, List<Offer>> offers() {
        Map<String, List<Offer>> out = new TreeMap<>();
        OFFERS.forEach((k, v) -> {
            if (!v.isEmpty()) {
                out.put(k, List.copyOf(v));
            }
        });
        return out;
    }

    private static List<Offer> fitting(String id, Class<?> type) {
        List<Offer> l = OFFERS.get(id);
        if (l == null) {
            return List.of();
        }
        List<Offer> fit = new ArrayList<>();
        for (Offer o : l) {
            if (type.isInstance(o.service)) {
                fit.add(o);
            }
        }
        fit.sort(Comparator.comparingInt((Offer o) -> -o.priority).thenComparing(o -> o.mod));
        return fit;
    }

    /** Every bridge and every service, the winner marked: what /horizon bridges shows. */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        List<Bridge> bridges = bridges();
        sb.append(bridges.isEmpty() ? "No Wormhole bridges." : bridges.size() + " bridge(s):");
        bridges.forEach(b -> sb.append("\n  ").append(b));
        Map<String, List<Offer>> offers = offers();
        if (!offers.isEmpty()) {
            sb.append("\n").append(offers.size()).append(" service(s):");
            offers.forEach((id, list) -> {
                Object winner = seek(id, Object.class).orElse(null);
                sb.append("\n  ").append(id).append(winner == null ? ": nobody wins" : ":");
                list.stream().sorted(Comparator.comparingInt((Offer o) -> -o.priority).thenComparing(o -> o.mod))
                        .forEach(o -> sb.append("\n    ").append(o.mod).append(o.priority != 0 ? " at priority " + o.priority : "")
                                .append(" (").append(o.service.getClass().getName()).append(')')
                                .append(o.service == winner ? "  <- wins" : ""));
            });
        }
        return sb.toString();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static void report(String what) {
        if (REPORTED.add(what)) {
            EventHorizon.LOG.accept(what);
        }
    }

    private static Class<?> callerClass() {
        return WALKER.walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(k -> !k.getName().startsWith("io.github.hronosin.miracle.horizon."))
                .findFirst()).orElse(null);
    }

    private static String owner(Class<?> c) {
        if (c == null) {
            return "event-horizon";
        }
        try {
            Optional<Mods.Mod> m = Mods.owner(c);
            if (m.isPresent()) {
                return m.get().id();
            }
        } catch (RuntimeException notRunning) {
            // outside a game: name the package
        }
        return c.getPackageName();
    }

    /** 1.10 > 1.9; missing parts count as 0; anything after - or + is ignored. The loader's rule. */
    static int compare(String a, String b) {
        String[] x = a.split("[-+]", 2)[0].split("\\.");
        String[] y = b.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? number(x[i]) : 0;
            long q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
