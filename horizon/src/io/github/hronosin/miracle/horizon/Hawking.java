package io.github.hronosin.miracle.horizon;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Hawking (boring name: Particles): radiation from the edge of things. Draws with particles:
 * lines, circles, spheres, helices, curves, the outline of a {@link Shape}. Server side: every
 * player who can see the spot sees it.
 *
 * <pre>{@code
 * Hawking.circle(level, ParticleTypes.END_ROD, center, Vec.UP, 3, 48);
 * Hawking.curve(level, ParticleTypes.FLAME, Curve.bezier(a, a.add(0, 6, 0), b), 40);
 * Hawking.helix(level, ParticleTypes.WITCH, base, 1.5, 4, 3, 80);  // 3 turns, 4 tall
 * }</pre>
 */
public final class Hawking {

    private Hawking() {
    }

    /** One particle at each point, standing still. */
    public static void points(ServerLevel level, ParticleOptions particle, Iterable<Vec> points) {
        for (Vec p : points) {
            level.sendParticles(particle, p.x(), p.y(), p.z(), 1, 0, 0, 0, 0);
        }
    }

    /** {@code count} particles from {@code a} to {@code b}, both ends included. */
    public static void line(ServerLevel level, ParticleOptions particle, Vec a, Vec b, int count) {
        points(level, particle, Curve.line(a, b).sample(Math.max(1, count - 1)));
    }

    /** A ring around {@code center}, square to {@code normal} (Vec.UP: lying flat). */
    public static void circle(ServerLevel level, ParticleOptions particle, Vec center, Vec normal, double radius, int count) {
        points(level, particle, circle(center, normal, radius, count));
    }

    /** {@code count} points spread evenly over a sphere's surface. */
    public static void sphere(ServerLevel level, ParticleOptions particle, Vec center, double radius, int count) {
        points(level, particle, sphere(center, radius, count));
    }

    /** A spiral rising from {@code base}: {@code turns} times around, {@code height} tall. */
    public static void helix(ServerLevel level, ParticleOptions particle, Vec base, double radius, double height,
                             double turns, int count) {
        points(level, particle, helix(base, radius, height, turns, count));
    }

    /** Along a curve, evenly spaced. */
    public static void curve(ServerLevel level, ParticleOptions particle, Curve curve, int count) {
        points(level, particle, curve.evenly(Math.max(2, count)));
    }

    /**
     * The surface of a shape, dotted: one particle every {@code spacing} blocks where the inside
     * meets the outside. Big shapes and small spacings get expensive; start around 0.5.
     */
    public static void outline(ServerLevel level, ParticleOptions particle, Shape shape, double spacing) {
        points(level, particle, outline(shape, spacing));
    }

    // --- the points themselves, for drawing or anything else ---------------------------------

    public static List<Vec> circle(Vec center, Vec normal, double radius, int count) {
        Vec n = normal.normalize();
        Vec u = (Math.abs(n.y()) < 0.99 ? n.cross(Vec.UP) : n.cross(Vec.EAST)).normalize();
        Vec v = n.cross(u);
        List<Vec> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * i / count;
            out.add(center.add(u.mul(Math.cos(a) * radius)).add(v.mul(Math.sin(a) * radius)));
        }
        return out;
    }

    /** A Fibonacci sphere: no poles, no clumps. */
    public static List<Vec> sphere(Vec center, double radius, int count) {
        List<Vec> out = new ArrayList<>(count);
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < count; i++) {
            double y = count == 1 ? 0 : 1 - 2.0 * i / (count - 1);
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double a = golden * i;
            out.add(center.add(Math.cos(a) * r * radius, y * radius, Math.sin(a) * r * radius));
        }
        return out;
    }

    public static List<Vec> helix(Vec base, double radius, double height, double turns, int count) {
        List<Vec> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double t = count == 1 ? 0 : (double) i / (count - 1);
            double a = 2 * Math.PI * turns * t;
            out.add(base.add(Math.cos(a) * radius, height * t, Math.sin(a) * radius));
        }
        return out;
    }

    public static List<Vec> outline(Shape shape, double spacing) {
        Vec[] b = shape.bounds();
        List<Vec> out = new ArrayList<>();
        double s = Math.max(0.05, spacing);
        for (double x = b[0].x(); x <= b[1].x(); x += s) {
            for (double y = b[0].y(); y <= b[1].y(); y += s) {
                for (double z = b[0].z(); z <= b[1].z(); z += s) {
                    Vec p = new Vec(x, y, z);
                    if (shape.contains(p) && (!shape.contains(p.add(s, 0, 0)) || !shape.contains(p.add(-s, 0, 0))
                            || !shape.contains(p.add(0, s, 0)) || !shape.contains(p.add(0, -s, 0))
                            || !shape.contains(p.add(0, 0, s)) || !shape.contains(p.add(0, 0, -s)))) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }
}
