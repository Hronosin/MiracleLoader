package io.github.hronosin.miracle.horizon;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.DoubleConsumer;

/**
 * Redshift (boring name: Scheduler): time, stretched to taste. Run something later, again and
 * again, or smoothly over a stretch of ticks; cooldowns per entity. Everything runs on the server
 * thread, between ticks, in the order it was due; {@link #client()} is the same on the client.
 * A task bound to an entity ends when the entity does. Everything scheduled ends with the world.
 *
 * <pre>{@code
 * Redshift.after(40, () -> boom(pos));                                  // in two seconds
 * Redshift.every(20, () -> heal(player)).times(5).bound(player);       // five heals, while alive
 * Redshift.over(30, t -> door.setOpen(Ease.OUT_BACK.applyAsDouble(t)));
 * static final Redshift.Cooldown DASH = Redshift.cooldown(60);
 * if (DASH.tryStart(player)) dash(player);
 * }</pre>
 */
public final class Redshift {

    private static final Redshift SERVER = new Redshift("server");
    private static final Redshift CLIENT = new Redshift("client");

    private final String side;
    private final ConcurrentLinkedQueue<Task> incoming = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<Task> queue = new PriorityQueue<>((a, b) -> a.due != b.due ? Long.compare(a.due, b.due)
            : Long.compare(a.order, b.order));
    private volatile long now;
    private long order;

    private Redshift(String side) {
        this.side = side;
    }

    /** The server's clock (the default). */
    public static Redshift server() {
        return SERVER;
    }

    /** The client's clock: ticks while a client runs, paused or not; never on a dedicated server. */
    public static Redshift client() {
        return CLIENT;
    }

    public static Task after(int ticks, Runnable task) {
        return SERVER.in(ticks, task);
    }

    public static Task every(int period, Runnable task) {
        return SERVER.repeat(period, task);
    }

    public static Task over(int ticks, DoubleConsumer progress) {
        return SERVER.during(ticks, progress);
    }

    /** Server ticks since the loader started counting. */
    public static long now() {
        return SERVER.now;
    }

    // --- per clock ----------------------------------------------------------------------------

    /** Once, {@code ticks} from now (0: at the end of this tick). */
    public Task in(int ticks, Runnable task) {
        return schedule(new Task(this, Math.max(0, ticks), 0, task, null, 0));
    }

    /** Every {@code period} ticks, starting {@code period} from now, until cancelled (or {@link Task#times}). */
    public Task repeat(int period, Runnable task) {
        if (period < 1) {
            throw new IllegalArgumentException("period " + period + ": at least 1 tick");
        }
        return schedule(new Task(this, period, period, task, null, 0));
    }

    /**
     * Every tick for {@code ticks} ticks, with progress from 0 (now) to 1 (the last call, exactly
     * 1): animations, fades, anything eased.
     */
    public Task during(int ticks, DoubleConsumer progress) {
        int n = Math.max(1, ticks);
        return schedule(new Task(this, 0, 1, null, progress, n));
    }

    private Task schedule(Task t) {
        t.due = now + t.delay;
        incoming.add(t);
        return t;
    }

    /** Called by Event Horizon after every tick of this side. */
    void tick() {
        now++;
        for (Task t; (t = incoming.poll()) != null; ) {
            t.order = order++;
            queue.add(t);
        }
        while (!queue.isEmpty() && queue.peek().due <= now) {
            Task t = queue.poll();
            if (t.cancelled || t.boundGone()) {
                continue;
            }
            try {
                t.fire();
            } catch (RuntimeException e) {
                t.cancelled = true;
                EventHorizon.LOG.accept("a " + side + " task failed and was cancelled: " + e);
                e.printStackTrace();
                continue;
            }
            if (!t.cancelled && t.next()) {
                t.order = order++;
                queue.add(t);
            }
        }
    }

    /** The world ends: so does everything scheduled in it, and every cooldown. */
    void reset() {
        incoming.clear();
        queue.clear();
        if (this == SERVER) {
            Cooldown.ALL.forEach(c -> c.readyAt.clear());
        }
    }

    /** Something scheduled. */
    public static final class Task {
        private final Redshift clock;
        private final int delay;
        private final int period;
        private final Runnable action;
        private final DoubleConsumer progress;
        private final int steps;
        private volatile boolean cancelled;
        private long due;
        private long order;
        private int step;
        private long remaining = -1;
        private Entity bound;

        private Task(Redshift clock, int delay, int period, Runnable action, DoubleConsumer progress, int steps) {
            this.clock = clock;
            this.delay = delay;
            this.period = period;
            this.action = action;
            this.progress = progress;
            this.steps = steps;
        }

        /** No more runs. */
        public void cancel() {
            cancelled = true;
        }

        public boolean cancelled() {
            return cancelled;
        }

        /** A repeating task runs this many times, then stops. */
        public Task times(int n) {
            remaining = n;
            return this;
        }

        /** Ends when the entity is removed or dies. */
        public Task bound(Entity e) {
            bound = e;
            return this;
        }

        private boolean boundGone() {
            return bound != null && (bound.isRemoved() || !bound.isAlive());
        }

        private void fire() {
            if (progress != null) {
                progress.accept(steps <= 1 ? 1.0 : (double) step / (steps - 1));
            } else {
                action.run();
            }
            step++;
            if (remaining > 0) {
                remaining--;
            }
        }

        /** Whether it runs again; if so, when. */
        private boolean next() {
            if (period == 0 || remaining == 0 || (progress != null && step >= steps)) {
                return false;
            }
            due = clock.now + period;
            return true;
        }
    }

    // --- cooldowns ----------------------------------------------------------------------------

    /** A cooldown of {@code ticks}, kept per entity, on the server's clock. */
    public static Cooldown cooldown(int ticks) {
        return new Cooldown(ticks);
    }

    /** "Not again so soon", per entity. Forgotten when the server stops. */
    public static final class Cooldown {
        private static final java.util.List<Cooldown> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final int ticks;
        private final Map<UUID, Long> readyAt = new ConcurrentHashMap<>();

        private Cooldown(int ticks) {
            this.ticks = ticks;
            ALL.add(this);
        }

        public boolean ready(Entity e) {
            return remaining(e) == 0;
        }

        /** Ticks until ready: 0 if it is. */
        public long remaining(Entity e) {
            Long at = readyAt.get(e.getUUID());
            return at == null ? 0 : Math.max(0, at - now());
        }

        public void start(Entity e) {
            readyAt.put(e.getUUID(), now() + ticks);
        }

        /** Starts it and says true if it was ready; says false (and changes nothing) if not. */
        public boolean tryStart(Entity e) {
            if (!ready(e)) {
                return false;
            }
            start(e);
            return true;
        }

        public void reset(Entity e) {
            readyAt.remove(e.getUUID());
        }
    }
}
