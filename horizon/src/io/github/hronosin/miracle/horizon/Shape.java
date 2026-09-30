package io.github.hronosin.miracle.horizon;

import java.util.ArrayList;
import java.util.List;

/**
 * A region of space: a sphere, a box, a cylinder, a cone, a thick line, or several of them
 * together. It can say whether a point is inside and which blocks it covers; {@link Singularity}
 * asks the world what's in it, {@link Hawking} draws it.
 *
 * <pre>{@code
 * Shape blast = Shape.sphere(center, 4);
 * Shape breath = Shape.cone(eye, look, 30, 8);   // 30 degrees wide, 8 blocks long
 * for (int[] b : blast.blocks()) { ... }        // {x, y, z} of every block whose center is inside
 * }</pre>
 */
public interface Shape {

    boolean contains(Vec p);

    /** The smallest box around the shape: {min, max}. */
    Vec[] bounds();

    static Shape sphere(Vec center, double radius) {
        double r2 = radius * radius;
        return of(p -> p.sub(center).lengthSquared() <= r2,
                center.sub(Vec.ONE.mul(radius)), center.add(Vec.ONE.mul(radius)));
    }

    static Shape box(Vec a, Vec b) {
        Vec min = new Vec(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        Vec max = new Vec(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        return of(p -> p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z(), min, max);
    }

    /** Upright: its base centered on {@code base}, {@code height} tall. */
    static Shape cylinder(Vec base, double radius, double height) {
        double r2 = radius * radius;
        return of(p -> {
            double dx = p.x() - base.x();
            double dz = p.z() - base.z();
            return p.y() >= base.y() && p.y() <= base.y() + height && dx * dx + dz * dz <= r2;
        }, base.add(-radius, 0, -radius), base.add(radius, height, radius));
    }

    /**
     * From {@code apex} along {@code direction}, {@code angleDegrees} wide in total (its half on
     * either side), {@code length} long: a breath, a flashlight, a shotgun.
     */
    static Shape cone(Vec apex, Vec direction, double angleDegrees, double length) {
        Vec d = direction.normalize();
        double cos = Math.cos(Math.toRadians(angleDegrees / 2));
        double reach = length / Math.max(cos, 1e-6);
        return of(p -> {
            Vec v = p.sub(apex);
            double along = v.dot(d);
            if (along < 0 || along > length) {
                return false;
            }
            double l = v.length();
            return l < 1e-9 || along / l >= cos;
        }, apex.sub(Vec.ONE.mul(reach)), apex.add(Vec.ONE.mul(reach)));
    }

    /** Everything within {@code radius} of the segment from {@code a} to {@code b}: a beam. */
    static Shape line(Vec a, Vec b, double radius) {
        Vec ab = b.sub(a);
        double l2 = Math.max(ab.lengthSquared(), 1e-12);
        double r2 = radius * radius;
        Vec min = new Vec(Math.min(a.x(), b.x()) - radius, Math.min(a.y(), b.y()) - radius, Math.min(a.z(), b.z()) - radius);
        Vec max = new Vec(Math.max(a.x(), b.x()) + radius, Math.max(a.y(), b.y()) + radius, Math.max(a.z(), b.z()) + radius);
        return of(p -> {
            double t = Math.max(0, Math.min(1, p.sub(a).dot(ab) / l2));
            return p.sub(a.add(ab.mul(t))).lengthSquared() <= r2;
        }, min, max);
    }

    /** In any of them. */
    static Shape union(Shape... shapes) {
        Vec min = shapes[0].bounds()[0];
        Vec max = shapes[0].bounds()[1];
        for (Shape s : shapes) {
            Vec[] b = s.bounds();
            min = new Vec(Math.min(min.x(), b[0].x()), Math.min(min.y(), b[0].y()), Math.min(min.z(), b[0].z()));
            max = new Vec(Math.max(max.x(), b[1].x()), Math.max(max.y(), b[1].y()), Math.max(max.z(), b[1].z()));
        }
        Shape[] all = shapes.clone();
        return of(p -> {
            for (Shape s : all) {
                if (s.contains(p)) {
                    return true;
                }
            }
            return false;
        }, min, max);
    }

    /** In this one but not in {@code hole}: a hollow sphere is {@code sphere(c, 5).minus(sphere(c, 4))}. */
    default Shape minus(Shape hole) {
        Shape self = this;
        Vec[] b = bounds();
        return of(p -> self.contains(p) && !hole.contains(p), b[0], b[1]);
    }

    /** Every block whose center is inside, as {x, y, z}. */
    default List<int[]> blocks() {
        Vec[] b = bounds();
        List<int[]> out = new ArrayList<>();
        for (int x = (int) Math.floor(b[0].x()); x <= (int) Math.floor(b[1].x()); x++) {
            for (int y = (int) Math.floor(b[0].y()); y <= (int) Math.floor(b[1].y()); y++) {
                for (int z = (int) Math.floor(b[0].z()); z <= (int) Math.floor(b[1].z()); z++) {
                    if (contains(new Vec(x + 0.5, y + 0.5, z + 0.5))) {
                        out.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return out;
    }

    /** A shape from a test and its bounds. */
    static Shape of(java.util.function.Predicate<Vec> inside, Vec min, Vec max) {
        return new Shape() {
            @Override
            public boolean contains(Vec p) {
                return inside.test(p);
            }

            @Override
            public Vec[] bounds() {
                return new Vec[] {min, max};
            }
        };
    }
}
