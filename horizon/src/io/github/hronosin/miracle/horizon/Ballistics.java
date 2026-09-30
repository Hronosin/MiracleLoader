package io.github.hronosin.miracle.horizon;

import java.util.Optional;

/**
 * Throwing things so they land where you mean them to, in the game's units (blocks, ticks) and by
 * the game's own step: every tick a thing moves by its velocity, then gravity pulls and the air
 * slows it. How much depends on the kind of {@link Body}: a mob in the air keeps only 91% of its
 * sideways speed each tick, an arrow 99%. Worked out tick by tick, so the landing is exact, not an
 * approximation from school physics.
 *
 * <pre>{@code
 * Vec v = Ballistics.inTicks(from, to, 20, Ballistics.Body.LIVING);    // a jump pad: there in one second
 * Ballistics.aim(bow, target, 3.0, Ballistics.Body.ARROW, false).ifPresent(...);
 * }</pre>
 */
public final class Ballistics {

    private Ballistics() {
    }

    /**
     * How a kind of thing flies: gravity per tick, what's left of its vertical and sideways speed
     * after a tick of air, and whether gravity is taken before the air (mobs) or after (arrows).
     */
    public record Body(double gravity, double dragUp, double dragSide, boolean gravityFirst) {

        /** Mobs and players in the air. */
        public static final Body LIVING = new Body(0.08, 0.98, 0.91, true);
        /** Arrows and tridents. */
        public static final Body ARROW = new Body(0.05, 0.99, 0.99, false);
        /** Snowballs, eggs, ender pearls, potions. */
        public static final Body THROWN = new Body(0.03, 0.99, 0.99, false);
        /** No air at all: school physics, in ticks. */
        public static final Body VACUUM = new Body(0.08, 1, 1, true);

        public Body withGravity(double g) {
            return new Body(g, dragUp, dragSide, gravityFirst);
        }

        /** Sum over n ticks of how far a sideways speed of 1 carries. */
        double side(int n) {
            double sum = 0;
            double k = 1;
            for (int i = 0; i < n; i++) {
                sum += k;
                k *= dragSide;
            }
            return sum;
        }

        /** {a, b}: after n ticks, height = a * vy - b * gravity. */
        double[] up(int n) {
            double a = 0;
            double b = 0;
            double k = 1;
            double g = 0; // how much gravity has taken from the speed so far, in units of gravity
            for (int i = 0; i < n; i++) {
                a += k;
                b += g;
                k *= dragUp;
                g = gravityFirst ? (g + 1) * dragUp : g * dragUp + 1;
            }
            return new double[] {a, b};
        }
    }

    /** The velocity that reaches {@code to} from {@code from} in exactly {@code ticks}. Always exists. */
    public static Vec inTicks(Vec from, Vec to, int ticks, Body body) {
        int n = Math.max(1, ticks);
        Vec d = to.sub(from);
        double h = body.side(n);
        double[] up = body.up(n);
        return new Vec(d.x() / h, (d.y() + up[1] * body.gravity()) / up[0], d.z() / h);
    }

    /** Where something launched with {@code velocity} is after {@code ticks}. */
    public static Vec after(Vec from, Vec velocity, int ticks, Body body) {
        double h = body.side(ticks);
        double[] up = body.up(ticks);
        return from.add(velocity.x() * h, velocity.y() * up[0] - up[1] * body.gravity(), velocity.z() * h);
    }

    /**
     * A velocity of at most {@code speed} blocks per tick that lands on {@code to}, if it can reach
     * within 400 ticks: the flat shot, or with {@code high} the lob. Empty if it's out of range.
     */
    public static Optional<Vec> aim(Vec from, Vec to, double speed, Body body, boolean high) {
        Vec best = null;
        for (int n = 1; n <= 400; n++) {
            Vec v = inTicks(from, to, n, body);
            if (v.length() <= speed) {
                best = v;
                if (!high) {
                    return Optional.of(v);
                }
            } else if (best != null) {
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /** How high above its start something launched straight up at {@code vy} gets. */
    public static double apex(double vy, Body body) {
        double best = 0;
        for (int n = 1; n <= 400; n++) {
            double[] up = body.up(n);
            double y = vy * up[0] - up[1] * body.gravity();
            if (y < best) {
                break;
            }
            best = y;
        }
        return best;
    }
}
