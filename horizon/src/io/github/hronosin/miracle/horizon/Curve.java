package io.github.hronosin.miracle.horizon;

import java.util.ArrayList;
import java.util.List;

/**
 * A path through space, walked from {@code t} = 0 to 1: a straight line, a Bézier curve, or a
 * Catmull-Rom spline through points. For flight paths, particle trails, and anything that should
 * move gracefully instead of in a straight line.
 *
 * <pre>{@code
 * Curve arc = Curve.bezier(start, start.add(0, 10, 0), end.add(0, 10, 0), end);
 * Vec there = arc.at(0.25);
 * List<Vec> dots = arc.evenly(32);   // 32 points, equally far apart along the curve
 * }</pre>
 */
public interface Curve {

    /** The point at {@code t}, 0 to 1. */
    Vec at(double t);

    /** Straight from {@code a} to {@code b}. */
    static Curve line(Vec a, Vec b) {
        return t -> a.lerp(b, t);
    }

    /**
     * A Bézier curve: starts at the first point, ends at the last, and is pulled toward the ones
     * between. Any number of points, two or more (three: quadratic, four: cubic).
     */
    static Curve bezier(Vec... points) {
        if (points.length < 2) {
            throw new IllegalArgumentException("a Bézier curve needs at least two points");
        }
        Vec[] p = points.clone();
        return t -> {
            Vec[] q = p.clone();
            for (int n = q.length - 1; n > 0; n--) {
                for (int i = 0; i < n; i++) {
                    q[i] = q[i].lerp(q[i + 1], t);
                }
            }
            return q[0];
        };
    }

    /**
     * A smooth curve through every point, in order (a centripetal Catmull-Rom spline, which
     * doesn't loop or overshoot at sharp corners). Two or more points.
     */
    static Curve through(Vec... points) {
        if (points.length < 2) {
            throw new IllegalArgumentException("a curve through points needs at least two of them");
        }
        Vec[] p = points.clone();
        int segments = p.length - 1;
        return t -> {
            double s = Math.max(0, Math.min(1, t)) * segments;
            int i = Math.min((int) s, segments - 1);
            double u = s - i;
            Vec p0 = i > 0 ? p[i - 1] : p[i].sub(p[i + 1].sub(p[i]));
            Vec p1 = p[i];
            Vec p2 = p[i + 1];
            Vec p3 = i + 2 < p.length ? p[i + 2] : p[i + 1].add(p[i + 1].sub(p[i]));
            return catmullRom(p0, p1, p2, p3, u);
        };
    }

    /** {@code n} + 1 points at even steps of {@code t} (not of distance: see {@link #evenly}). */
    default List<Vec> sample(int n) {
        List<Vec> out = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            out.add(at((double) i / n));
        }
        return out;
    }

    /** About how long the curve is, measured along 256 short straight pieces. */
    default double length() {
        double sum = 0;
        Vec prev = at(0);
        for (int i = 1; i <= 256; i++) {
            Vec next = at(i / 256.0);
            sum += prev.distance(next);
            prev = next;
        }
        return sum;
    }

    /** {@code n} points (two or more) equally far apart along the curve, first and last included. */
    default List<Vec> evenly(int n) {
        int fine = Math.max(256, n * 8);
        double[] acc = new double[fine + 1];
        Vec[] pts = new Vec[fine + 1];
        pts[0] = at(0);
        for (int i = 1; i <= fine; i++) {
            pts[i] = at((double) i / fine);
            acc[i] = acc[i - 1] + pts[i - 1].distance(pts[i]);
        }
        List<Vec> out = new ArrayList<>(n);
        int j = 0;
        for (int k = 0; k < n; k++) {
            double want = acc[fine] * k / (n - 1);
            while (j < fine && acc[j + 1] < want) {
                j++;
            }
            if (j >= fine) {
                out.add(pts[fine]);
                continue;
            }
            double seg = acc[j + 1] - acc[j];
            double u = seg < 1e-12 ? 0 : (want - acc[j]) / seg;
            out.add(pts[j].lerp(pts[j + 1], u));
        }
        return out;
    }

    /** The direction the curve is heading at {@code t} (length 1). */
    default Vec direction(double t) {
        double h = 1e-4;
        Vec a = at(Math.max(0, t - h));
        Vec b = at(Math.min(1, t + h));
        return b.sub(a).normalize();
    }

    /** The same path, paced by an easing: {@code curve.eased(Ease.IN_OUT_SINE).at(t)}. */
    default Curve eased(Ease ease) {
        return t -> at(ease.applyAsDouble(t));
    }

    private static Vec catmullRom(Vec p0, Vec p1, Vec p2, Vec p3, double u) {
        // centripetal parametrization (alpha 0.5)
        double t0 = 0;
        double t1 = t0 + Math.sqrt(Math.max(p0.distance(p1), 1e-9));
        double t2 = t1 + Math.sqrt(Math.max(p1.distance(p2), 1e-9));
        double t3 = t2 + Math.sqrt(Math.max(p2.distance(p3), 1e-9));
        double t = t1 + (t2 - t1) * u;
        Vec a1 = p0.mul((t1 - t) / (t1 - t0)).add(p1.mul((t - t0) / (t1 - t0)));
        Vec a2 = p1.mul((t2 - t) / (t2 - t1)).add(p2.mul((t - t1) / (t2 - t1)));
        Vec a3 = p2.mul((t3 - t) / (t3 - t2)).add(p3.mul((t - t2) / (t3 - t2)));
        Vec b1 = a1.mul((t2 - t) / (t2 - t0)).add(a2.mul((t - t0) / (t2 - t0)));
        Vec b2 = a2.mul((t3 - t) / (t3 - t1)).add(a3.mul((t - t1) / (t3 - t1)));
        return b1.mul((t2 - t) / (t2 - t1)).add(b2.mul((t - t1) / (t2 - t1)));
    }
}
