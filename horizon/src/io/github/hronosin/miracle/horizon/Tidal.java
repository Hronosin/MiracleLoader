package io.github.hronosin.miracle.horizon;

import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * Tidal (boring name: Physics): forces on things. Push, pull, fling, throw at a target. Server
 * side; players' clients are told about the new motion right away.
 *
 * <pre>{@code
 * Tidal.away(mob, blastCenter, 1.2);                       // knocked away from a point
 * Tidal.pull(item, Geodesic.eyes(player), 0.3);             // drawn toward a point
 * Tidal.launch(player, landingSpot, 30);                   // lands there in a second and a half
 * }</pre>
 */
public final class Tidal {

    private Tidal() {
    }

    /** Adds to its motion (blocks per tick). */
    public static void push(Entity e, Vec delta) {
        set(e, Geodesic.vec(e.getDeltaMovement()).add(delta));
    }

    /** Replaces its motion. */
    public static void set(Entity e, Vec velocity) {
        e.setDeltaMovement(Geodesic.vec3(velocity));
        sync(e);
    }

    /** Toward a point, by {@code strength} blocks per tick. */
    public static void pull(Entity e, Vec point, double strength) {
        push(e, point.sub(Geodesic.middle(e)).withLength(strength));
    }

    /** Away from a point, by {@code strength} blocks per tick, with a little lift so it leaves the ground. */
    public static void away(Entity e, Vec point, double strength) {
        Vec dir = Geodesic.middle(e).sub(point);
        if (dir.lengthSquared() < 1e-6) {
            dir = Vec.UP;
        }
        push(e, dir.withLength(strength).add(0, strength * 0.25, 0));
    }

    /** Pulled toward a point harder the closer it is, like gravity; nothing beyond {@code range}. */
    public static void attract(Entity e, Vec point, double strength, double range) {
        double d = Geodesic.middle(e).distance(point);
        if (d > range || d < 1e-3) {
            return;
        }
        pull(e, point, strength * (1 - d / range));
    }

    /**
     * Flung so it lands on {@code target} after {@code ticks} ticks (air drag aside), and its fall
     * forgiven. Always possible; short times make for steep, fast flights.
     */
    public static void launch(Entity e, Vec target, int ticks) {
        set(e, Ballistics.inTicks(Geodesic.position(e), target, Math.max(1, ticks), body(e)));
        e.resetFallDistance();
    }

    /**
     * Thrown at {@code target} at {@code speed} blocks per tick, if it can reach; false if the
     * target is out of range at that speed.
     */
    public static boolean throwAt(Entity e, Vec target, double speed, boolean high) {
        Optional<Vec> v = Ballistics.aim(Geodesic.position(e), target, speed, body(e), high);
        v.ifPresent(velocity -> set(e, velocity));
        return v.isPresent();
    }

    /** How this entity flies: a mob's air, an arrow's, a snowball's; its own gravity either way. */
    public static Ballistics.Body body(Entity e) {
        Ballistics.Body kind;
        if (e instanceof net.minecraft.world.entity.LivingEntity) {
            kind = Ballistics.Body.LIVING;
        } else if (e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
            kind = Ballistics.Body.ARROW;
        } else if (e instanceof net.minecraft.world.entity.projectile.Projectile) {
            kind = Ballistics.Body.THROWN;
        } else {
            kind = new Ballistics.Body(0, 0.98, 0.98, true); // items, blocks and the rest: close enough
        }
        return kind.withGravity(e.getGravity());
    }

    /** Stops it where it is. */
    public static void stop(Entity e) {
        set(e, Vec.ZERO);
    }

    /**
     * Makes the server send the new motion to clients, players' own included. The flag for that
     * has had three names (hurtMarked, then syncVelocity in 26.3, with needsSync meaning something
     * else); the game's own markHurt() sets whichever it is, so that's what this calls. It's
     * protected, hence the lookup; 1.21.11, where names are obfuscated, has a fallback instead.
     */
    static void sync(Entity e) {
        try {
            MarkHurt.HANDLE.invokeExact(e);
        } catch (Throwable t) {
            throw new IllegalStateException("Entity.markHurt() failed", t);
        }
    }

    /** Looked up on first use only: on 1.21.11 the fallback never gets here, and the name wouldn't exist. */
    private static final class MarkHurt {
        static final java.lang.invoke.MethodHandle HANDLE = find();

        private static java.lang.invoke.MethodHandle find() {
            try {
                return java.lang.invoke.MethodHandles.privateLookupIn(Entity.class, java.lang.invoke.MethodHandles.lookup())
                        .findVirtual(Entity.class, "markHurt", java.lang.invoke.MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
