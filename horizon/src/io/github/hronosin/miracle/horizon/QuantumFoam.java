package io.github.hronosin.miracle.horizon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * QuantumFoam (boring name: Dice): randomness for the especially lazy. Weighted pools, a bag that
 * deals everything once before repeating, luck that's fair over a streak, and streams of random
 * numbers that are the same every time you ask with the same key.
 *
 * <pre>{@code
 * static final QuantumFoam.Pool<Item> LOOT = QuantumFoam.<Item>pool("mymod:loot")
 *         .add(Items.DIAMOND, 1).add(Items.IRON_INGOT, 9).add(Items.DIRT, 90);
 * Item got = LOOT.roll();
 *
 * if (QuantumFoam.chance(0.1)) ...;   QuantumFoam.maybe(0.1, () -> ...);
 * String name = QuantumFoam.oneOf("Reimu", "Marisa", "Yukari");
 *
 * // the same reward for the same player on the same day, however often they relog
 * Item daily = QuantumFoam.of(level, "mymod:daily", player, day).roll(LOOT);
 *
 * static final QuantumFoam.Pity CRIT = QuantumFoam.pity(0.25);   // 25% that feels like 25%
 * if (CRIT.roll(player)) ...;
 * }</pre>
 *
 * <p>Keyed streams ({@link #keyed}, {@link #of}) depend only on their seed and key, never on what
 * was drawn before or elsewhere. A world's streams are derived from its seed through SHA-256, so
 * what drops can't be traced back to the seed.
 */
public final class QuantumFoam {

    private static final ThreadLocal<Foam> RANDOM = ThreadLocal.withInitial(() -> new Foam(new SecureRandom().nextLong()));
    private static final Map<String, Pool<?>> NAMED = new ConcurrentHashMap<>();
    private static final List<Pity> PITIES = new CopyOnWriteArrayList<>();

    private QuantumFoam() {
    }

    // --- streams ------------------------------------------------------------------------------

    /** This thread's own random stream, seeded from the system's secure randomness. */
    public static Foam random() {
        return RANDOM.get();
    }

    /** A stream that's the same for the same seed. */
    public static Foam seeded(long seed) {
        return new Foam(mix(seed));
    }

    /**
     * A stream that's the same for the same seed and key: strings, numbers, booleans, UUIDs,
     * enums, {@link Vec}s, int arrays, entities (by UUID) and block positions.
     */
    public static Foam keyed(long seed, Object... key) {
        return new Foam(key(mix(seed), key));
    }

    /** A stream for this world and key: the same in this world every time, different in others. */
    public static Foam of(ServerLevel level, Object... key) {
        return new Foam(key(secret(level.getSeed()), key));
    }

    /** The game's own random source, with Event Horizon's conveniences on top. */
    public static Foam from(RandomSource source) {
        return new Foam(source::nextLong);
    }

    // --- for the lazy: all on random() -----------------------------------------------------------

    public static boolean chance(double p) {
        return random().chance(p);
    }

    public static void maybe(double p, Runnable then) {
        random().maybe(p, then);
    }

    /** A whole number from {@code min} to {@code max}, both included. */
    public static int between(int min, int max) {
        return random().between(min, max);
    }

    /** A number from {@code min} (included) to {@code max} (not). */
    public static double between(double min, double max) {
        return random().between(min, max);
    }

    @SafeVarargs
    public static <T> T oneOf(T... options) {
        if (options.length == 0) {
            throw new IllegalArgumentException("oneOf() of nothing.");
        }
        return options[random().nextInt(options.length)];
    }

    public static <T> T pick(List<T> options) {
        return random().pick(options);
    }

    public static <T> List<T> sample(Collection<T> from, int count) {
        return random().sample(from, count);
    }

    public static <T> List<T> shuffled(Collection<T> items) {
        return random().shuffled(items);
    }

    /** A spot within {@code radius} where a mob can stand: solid ground, two blocks of room, no fluid. */
    public static Optional<Vec> somewhere(Level level, Vec center, double radius) {
        return Singularity.somewhere(level, center, radius, random());
    }

    // --- pools, bags, pity -----------------------------------------------------------------------

    /** An empty weighted pool. */
    public static <T> Pool<T> pool() {
        return new Pool<>(null);
    }

    /** An empty weighted pool with a name, so {@code /horizon dice <name>} can test it. Names are unique. */
    public static <T> Pool<T> pool(String id) {
        Pool<T> p = new Pool<>(id);
        if (NAMED.putIfAbsent(id, p) != null) {
            throw new IllegalArgumentException("A pool named '" + id + "' already exists.");
        }
        return p;
    }

    /** Named pools, for Telescope. */
    static Map<String, Pool<?>> named() {
        return Collections.unmodifiableMap(NAMED);
    }

    /** A bag that deals every item once, in random order, before dealing any of them again. */
    public static <T> Bag<T> bag(Collection<T> items) {
        return new Bag<>(items);
    }

    /**
     * A chance {@code p} that remembers misses: each miss makes the next try likelier, a hit starts
     * over. Over many tries it hits as often as {@code p} says, without long dry streaks or
     * lucky runs (the "pseudo-random distribution" of strategy games).
     */
    public static Pity pity(double p) {
        Pity pity = new Pity(p);
        PITIES.add(pity);
        return pity;
    }

    /** Forgets every pity's streaks; when the server stops. */
    static void reset() {
        PITIES.forEach(Pity::forgetAll);
    }

    /** What came up in {@code rolls} rolls against what should have, most likely first. */
    static <T> String histogram(Pool<T> pool, int rolls) {
        Map<T, Integer> seen = new java.util.LinkedHashMap<>();
        Foam rng = random();
        for (int i = 0; i < rolls; i++) {
            seen.merge(pool.roll(rng), 1, Integer::sum);
        }
        List<T> outcomes = new ArrayList<>(pool.outcomes());
        outcomes.sort((a, b) -> Double.compare(pool.chanceOf(b), pool.chanceOf(a)));
        StringBuilder sb = new StringBuilder(pool.id() + ", " + rolls + " rolls (actual / expected):");
        int shown = 0;
        for (T t : outcomes) {
            if (shown++ == 15) {
                sb.append("\n  ... and ").append(outcomes.size() - 15).append(" more");
                break;
            }
            int n = seen.getOrDefault(t, 0);
            sb.append(String.format(java.util.Locale.ROOT, "\n  %s: %d, %.2f%% / %.2f%%", t == null ? "(nothing)" : t, n,
                    100.0 * n / rolls, 100 * pool.chanceOf(t)));
        }
        return sb.toString();
    }

    // --- the stream ------------------------------------------------------------------------------

    /** A stream of random numbers (xoroshiro128++), or the game's own source behind the same methods. */
    public static final class Foam implements RandomGenerator {
        private final long origin;
        private final LongSupplier source;
        private long s0;
        private long s1;

        Foam(long seed) {
            this.origin = seed;
            this.source = null;
            long x = seed;
            s0 = splitmix(x += 0x9E3779B97F4A7C15L);
            s1 = splitmix(x + 0x9E3779B97F4A7C15L);
            if ((s0 | s1) == 0) {
                s1 = 1;
            }
        }

        Foam(LongSupplier source) {
            this.origin = 0;
            this.source = source;
        }

        @Override
        public long nextLong() {
            if (source != null) {
                return source.getAsLong();
            }
            long a = s0;
            long b = s1;
            long result = Long.rotateLeft(a + b, 17) + a;
            b ^= a;
            s0 = Long.rotateLeft(a, 49) ^ b ^ (b << 21);
            s1 = Long.rotateLeft(b, 28);
            return result;
        }

        /** A stream keyed further from where this one started; draws so far don't matter. */
        public Foam fork(Object... key) {
            return new Foam(QuantumFoam.key(origin, key));
        }

        public boolean chance(double p) {
            return p >= 1 || (p > 0 && nextDouble() < p);
        }

        public void maybe(double p, Runnable then) {
            if (chance(p)) {
                then.run();
            }
        }

        /** A whole number from {@code min} to {@code max}, both included. */
        public int between(int min, int max) {
            if (min > max) {
                throw new IllegalArgumentException("between(" + min + ", " + max + "): min is bigger than max.");
            }
            return (int) nextLong(min, (long) max + 1);
        }

        /** A number from {@code min} (included) to {@code max} (not). */
        public double between(double min, double max) {
            return min == max ? min : nextDouble(min, max);
        }

        @SafeVarargs
        public final <T> T oneOf(T... options) {
            if (options.length == 0) {
                throw new IllegalArgumentException("oneOf() of nothing.");
            }
            return options[nextInt(options.length)];
        }

        public <T> T pick(List<T> options) {
            if (options.isEmpty()) {
                throw new IllegalArgumentException("pick() from an empty list.");
            }
            return options.get(nextInt(options.size()));
        }

        /** {@code count} different items (all of them, shuffled, if there are fewer). */
        public <T> List<T> sample(Collection<T> from, int count) {
            List<T> all = shuffled(from);
            return new ArrayList<>(all.subList(0, Math.min(Math.max(count, 0), all.size())));
        }

        public <T> List<T> shuffled(Collection<T> items) {
            List<T> out = new ArrayList<>(items);
            for (int i = out.size() - 1; i > 0; i--) {
                Collections.swap(out, i, nextInt(i + 1));
            }
            return out;
        }

        /** A bell curve around {@code mean}; about 68% of draws land within one {@code deviation}. */
        public double gaussian(double mean, double deviation) {
            return mean + nextGaussian() * deviation;
        }

        /** A direction, every one as likely as any other. */
        public Vec direction() {
            double z = nextDouble() * 2 - 1;
            double a = nextDouble() * Math.PI * 2;
            double r = Math.sqrt(1 - z * z);
            return new Vec(r * Math.cos(a), z, r * Math.sin(a));
        }

        /** A point inside a shape, every one as likely as any other; empty if the shape seems empty. */
        public Optional<Vec> inside(Shape shape) {
            Vec[] b = shape.bounds();
            for (int i = 0; i < 4096; i++) {
                Vec p = new Vec(between(b[0].x(), b[1].x()), between(b[0].y(), b[1].y()), between(b[0].z(), b[1].z()));
                if (shape.contains(p)) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        }

        public <T> T roll(Pool<T> pool) {
            return pool.roll(this);
        }
    }

    // --- pools ---------------------------------------------------------------------------------

    /**
     * Things with weights: a thing of weight 9 comes up nine times as often as one of weight 1.
     * Entries may be other pools (roll one of them, then roll inside it) or nothing at all.
     */
    public static final class Pool<T> {
        private record Entry<T>(T item, Pool<T> sub, double weight) {
        }

        private final String id;
        private final List<Entry<T>> entries = new CopyOnWriteArrayList<>();
        private volatile double total;

        private Pool(String id) {
            this.id = id;
        }

        public Pool<T> add(T item, double weight) {
            return put(new Entry<>(item, null, weight));
        }

        /** Rolls inside {@code pool} as often as {@code weight} says. */
        public Pool<T> add(Pool<T> pool, double weight) {
            if (pool == this) {
                throw new IllegalArgumentException("A pool can't contain itself.");
            }
            return put(new Entry<>(null, pool, weight));
        }

        /** Rolling nothing ({@code null}) as often as {@code weight} says. */
        public Pool<T> nothing(double weight) {
            return put(new Entry<>(null, null, weight));
        }

        private Pool<T> put(Entry<T> e) {
            if (!(e.weight >= 0) || Double.isInfinite(e.weight)) {
                throw new IllegalArgumentException("A weight must be a number from 0 up, not " + e.weight + ".");
            }
            synchronized (entries) {
                entries.add(e);
                total += e.weight;
            }
            return this;
        }

        /** One roll on {@link QuantumFoam#random()}; null for nothing, or from an empty pool. */
        public T roll() {
            return roll(random());
        }

        public T roll(RandomGenerator rng) {
            double t = total;
            if (t <= 0) {
                return null;
            }
            double r = rng.nextDouble() * t;
            Entry<T> last = null;
            for (Entry<T> e : entries) {
                if (e.weight <= 0) {
                    continue;
                }
                last = e;
                r -= e.weight;
                if (r < 0) {
                    break;
                }
            }
            return last.sub != null ? last.sub.roll(rng) : last.item;
        }

        /** {@code count} rolls; nothing rolled is left out. */
        public List<T> roll(int count) {
            List<T> out = new ArrayList<>();
            Foam rng = random();
            for (int i = 0; i < count; i++) {
                T t = roll(rng);
                if (t != null) {
                    out.add(t);
                }
            }
            return out;
        }

        /** How likely one roll gives {@code item}, nested pools included; null asks about nothing. */
        public double chanceOf(T item) {
            double t = total;
            if (t <= 0) {
                return item == null ? 1 : 0;
            }
            double p = 0;
            for (Entry<T> e : entries) {
                if (e.sub != null) {
                    p += e.weight / t * e.sub.chanceOf(item);
                } else if (java.util.Objects.equals(e.item, item)) {
                    p += e.weight / t;
                }
            }
            return p;
        }

        /** Everything a roll can give, nested pools included (null for nothing), each once. */
        public List<T> outcomes() {
            List<T> out = new ArrayList<>();
            for (Entry<T> e : entries) {
                if (e.weight <= 0) {
                    continue;
                }
                for (T t : e.sub != null ? e.sub.outcomes() : java.util.Collections.singletonList(e.item)) {
                    if (!out.contains(t)) {
                        out.add(t);
                    }
                }
            }
            return out;
        }

        /** Its name, or null. */
        public String id() {
            return id;
        }

        public boolean isEmpty() {
            return total <= 0;
        }
    }

    // --- bags ----------------------------------------------------------------------------------

    /** Deals every item once in random order, then reshuffles; never the same item twice in a row across a reshuffle. */
    public static final class Bag<T> {
        private final List<T> items;
        private final List<T> left = new ArrayList<>();
        private T last;

        private Bag(Collection<T> items) {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("An empty bag deals nothing.");
            }
            this.items = List.copyOf(items);
        }

        public T next() {
            return next(random());
        }

        public synchronized T next(RandomGenerator rng) {
            if (left.isEmpty()) {
                left.addAll(items);
                for (int i = left.size() - 1; i > 0; i--) {
                    Collections.swap(left, i, rng.nextInt(i + 1));
                }
                if (left.size() > 1 && java.util.Objects.equals(left.get(left.size() - 1), last)) {
                    Collections.swap(left, left.size() - 1, 0);
                }
            }
            last = left.remove(left.size() - 1);
            return last;
        }

        /** How many are left before the next reshuffle. */
        public synchronized int left() {
            return left.size();
        }
    }

    // --- pity ----------------------------------------------------------------------------------

    /** A chance that grows with each miss and starts over on a hit; see {@link QuantumFoam#pity}. */
    public static final class Pity {
        private final double chance;
        private final double step;
        private final Map<Object, Integer> misses = new ConcurrentHashMap<>();

        private Pity(double p) {
            if (!(p > 0 && p <= 1)) {
                throw new IllegalArgumentException("A pity's chance must be above 0 and at most 1, not " + p + ".");
            }
            this.chance = p;
            this.step = stepFor(p);
        }

        /** One try for everyone together. */
        public boolean roll() {
            return roll("");
        }

        /** One try for {@code owner} (an entity, a UUID, a name...): each owner has a streak of its own. */
        public boolean roll(Object owner) {
            return roll(owner, random());
        }

        public boolean roll(Object owner, RandomGenerator rng) {
            Object k = owner(owner);
            boolean[] hit = {false};
            misses.compute(k, (key, m) -> {
                int n = (m == null ? 0 : m) + 1;
                hit[0] = rng.nextDouble() < Math.min(1, step * n);
                return hit[0] ? null : n;
            });
            return hit[0];
        }

        /** Misses since the last hit. */
        public int streak(Object owner) {
            return misses.getOrDefault(owner(owner), 0);
        }

        /** The most tries it can ever take to hit. */
        public int longest() {
            return (int) Math.ceil(1 / step);
        }

        public double chance() {
            return chance;
        }

        public void forget(Object owner) {
            misses.remove(owner(owner));
        }

        void forgetAll() {
            misses.clear();
        }

        private static Object owner(Object o) {
            if (o == null || o instanceof String || o instanceof UUID || o instanceof Number) {
                return o == null ? "" : o;
            }
            return GameKeys.owner(o);
        }

        /** The chance gained per miss that makes the long-run rate exactly p (by bisection). */
        static double stepFor(double p) {
            if (p >= 1) {
                return 1;
            }
            double lo = 0;
            double hi = p;
            for (int i = 0; i < 100; i++) {
                double mid = (lo + hi) / 2;
                if (rate(mid) < p) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            return (lo + hi) / 2;
        }

        /** The long-run hit rate for a step: 1 / (expected tries until a hit). */
        static double rate(double step) {
            double expected = 0;
            double stillMissing = 1;
            for (int n = 1; stillMissing > 0; n++) {
                expected += stillMissing;
                stillMissing *= 1 - Math.min(1, step * n);
            }
            return 1 / expected;
        }
    }

    // --- seeds and keys --------------------------------------------------------------------------

    private static volatile long[] lastSecret;

    /** A world's secret: SHA-256 of its seed, so draws reveal nothing about the seed. */
    static long secret(long worldSeed) {
        long[] cached = lastSecret;
        if (cached != null && cached[0] == worldSeed) {
            return cached[1];
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(("event-horizon:quantum-foam:" + worldSeed).getBytes(StandardCharsets.UTF_8));
            long s = 0;
            for (int i = 0; i < 8; i++) {
                s = s << 8 | (h[i] & 0xFF);
            }
            lastSecret = new long[] {worldSeed, s};
            return s;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This Java has no SHA-256, which every Java has.", e);
        }
    }

    static long key(long seed, Object... parts) {
        long h = mix(seed ^ 0x5851F42D4C957F2DL);
        for (Object part : parts) {
            h = mix(h ^ part(part));
        }
        return h;
    }

    private static long part(Object o) {
        if (o == null) {
            return 0x6E756C6CL;
        }
        if (o instanceof String s) {
            long h = 0xCBF29CE484222325L;
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                h = (h ^ (b & 0xFF)) * 0x100000001B3L;
            }
            return mix(h + 1);
        }
        if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte) {
            return mix(((Number) o).longValue() + 2);
        }
        if (o instanceof Double || o instanceof Float) {
            return mix(Double.doubleToLongBits(((Number) o).doubleValue()) + 3);
        }
        if (o instanceof Boolean b) {
            return mix(b ? 5 : 4);
        }
        if (o instanceof Character c) {
            return mix(c + 6L);
        }
        if (o instanceof UUID u) {
            return uuid(u);
        }
        if (o instanceof Enum<?> e) {
            return mix(part(e.getDeclaringClass().getName()) ^ part(e.name()) + 7);
        }
        if (o instanceof Vec v) {
            return mix(mix(mix(Double.doubleToLongBits(v.x())) ^ Double.doubleToLongBits(v.y())) ^ Double.doubleToLongBits(v.z()) + 8);
        }
        if (o instanceof int[] a) {
            long h = 9;
            for (int x : a) {
                h = mix(h ^ x);
            }
            return h;
        }
        Long game = GameKeys.part(o);
        if (game != null) {
            return game;
        }
        throw new IllegalArgumentException("QuantumFoam can't use a " + o.getClass().getName() + " in a key: use strings, numbers,"
                + " booleans, UUIDs, enums, Vecs, entities or block positions.");
    }

    static long uuid(UUID u) {
        return mix(mix(u.getMostSignificantBits()) ^ u.getLeastSignificantBits() + 10);
    }

    static long mix(long z) {
        return splitmix(z);
    }

    private static long splitmix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
