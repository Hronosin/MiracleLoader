package io.github.hronosin.miracle.horizon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Ergosphere (boring name: Auras): the region around a thing where nothing stays still. Every
 * few ticks, whatever is inside is handed to your code: heal allies, burn enemies, pull items in.
 * Around an entity (it moves with it, and ends when the entity does) or around a fixed point.
 * Server side.
 *
 * <pre>{@code
 * Ergosphere.around(priest, 6).every(20)
 *         .affecting(Player.class, p -> p != priest)
 *         .does((p, center) -> p.heal(1))
 *         .forTicks(600).start();
 * Ergosphere.at(level, altar, 10).affecting(ItemEntity.class, i -> true)
 *         .does((item, center) -> Tidal.pull(item, center, 0.1)).start();
 * }</pre>
 */
public final class Ergosphere {

    private static final List<Aura<?>> AURAS = new CopyOnWriteArrayList<>();

    private Ergosphere() {
    }

    /** An aura that follows an entity, centered on the middle of its body. */
    public static Aura<Entity> around(Entity source, double radius) {
        return new Aura<>(source, null, null, radius);
    }

    /** An aura at a fixed point. */
    public static Aura<Entity> at(ServerLevel level, Vec center, double radius) {
        return new Aura<>(null, level, center, radius);
    }

    /** How many auras are running. */
    public static int count() {
        return AURAS.size();
    }

    /** One aura. Set it up, then {@link #start()}. */
    public static final class Aura<T extends Entity> {
        private final Entity source;
        private final ServerLevel level;
        private final Vec center;
        private final double radius;
        private Class<T> type;
        private Predicate<? super T> filter = e -> true;
        private BiConsumer<? super T, Vec> effect = (e, c) -> { };
        private Function<Vec, Shape> shape;
        private int every = 20;
        private long until = -1;
        private long next;
        private int ticks = -1;
        private volatile boolean stopped;

        @SuppressWarnings("unchecked")
        private Aura(Entity source, ServerLevel level, Vec center, double radius) {
            this.source = source;
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.type = (Class<T>) Entity.class;
        }

        /** Only entities of this class that pass the filter. */
        @SuppressWarnings("unchecked")
        public <U extends Entity> Aura<U> affecting(Class<U> type, Predicate<? super U> filter) {
            Aura<U> self = (Aura<U>) this;
            self.type = type;
            self.filter = filter;
            return self;
        }

        /** What happens to each one inside, with the aura's center. */
        public Aura<T> does(BiConsumer<? super T, Vec> effect) {
            this.effect = effect;
            return this;
        }

        /** How often, in ticks (20 by default). */
        public Aura<T> every(int ticks) {
            this.every = Math.max(1, ticks);
            return this;
        }

        /** Another shape instead of the sphere, made around the center each time. */
        public Aura<T> shape(Function<Vec, Shape> shape) {
            this.shape = shape;
            return this;
        }

        /** Ends by itself after this many ticks. */
        public Aura<T> forTicks(int ticks) {
            this.ticks = ticks;
            return this;
        }

        public Aura<T> start() {
            if (ticks >= 0) {
                until = Redshift.now() + ticks;
            }
            next = Redshift.now() + every;
            AURAS.add(this);
            return this;
        }

        public void stop() {
            stopped = true;
            AURAS.remove(this);
        }

        public boolean running() {
            return !stopped;
        }

        /** False when it's over. */
        private boolean pulse() {
            long now = Redshift.now();
            if (stopped || (until >= 0 && now >= until) || (source != null && (source.isRemoved() || !source.isAlive()))) {
                stopped = true;
                return false;
            }
            if (now < next) {
                return true;
            }
            next = now + every;
            ServerLevel where = source != null ? (source.level() instanceof ServerLevel s ? s : null) : level;
            if (where == null) {
                return true; // a client-side entity: auras are the server's business
            }
            Vec c = source != null ? Geodesic.middle(source) : center;
            Shape s = shape != null ? shape.apply(c) : Shape.sphere(c, radius);
            for (T e : Singularity.entities(where, s, type, filter)) {
                effect.accept(e, c);
            }
            return true;
        }
    }

    static void tick() {
        for (Aura<?> a : AURAS) {
            try {
                if (!a.pulse()) {
                    AURAS.remove(a);
                }
            } catch (RuntimeException e) {
                AURAS.remove(a);
                EventHorizon.LOG.accept("an aura failed and was stopped: " + e);
                e.printStackTrace();
            }
        }
    }

    static void reset() {
        AURAS.clear();
    }
}
