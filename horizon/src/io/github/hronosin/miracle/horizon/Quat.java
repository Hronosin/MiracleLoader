package io.github.hronosin.miracle.horizon;

/**
 * A rotation, as a unit quaternion. Rotations compose without gimbal lock and blend smoothly
 * ({@link #slerp}), which yaw and pitch can't.
 *
 * <pre>{@code
 * Quat spin = Quat.axisAngle(Vec.UP, 90);
 * Vec east = spin.rotate(Vec.SOUTH);
 * Quat halfway = Quat.IDENTITY.slerp(spin, 0.5);
 * }</pre>
 */
public record Quat(double w, double x, double y, double z) {

    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    /** Turning by {@code degrees} about {@code axis} (right-handed; the axis needn't be length 1). */
    public static Quat axisAngle(Vec axis, double degrees) {
        Vec a = axis.normalize();
        double half = Math.toRadians(degrees) / 2;
        double s = Math.sin(half);
        return new Quat(Math.cos(half), a.x() * s, a.y() * s, a.z() * s);
    }

    /** The rotation that turns a south-facing thing to look the way an entity with this yaw and pitch does. */
    public static Quat yawPitch(double yawDegrees, double pitchDegrees) {
        return axisAngle(Vec.UP, -yawDegrees).mul(axisAngle(Vec.EAST, pitchDegrees));
    }

    /** The shortest rotation that turns direction {@code from} into direction {@code to}. */
    public static Quat between(Vec from, Vec to) {
        Vec a = from.normalize();
        Vec b = to.normalize();
        double d = a.dot(b);
        if (d > 1 - 1e-12) {
            return IDENTITY;
        }
        if (d < -1 + 1e-12) {
            Vec axis = Math.abs(a.x()) < 0.9 ? a.cross(Vec.EAST) : a.cross(Vec.UP);
            return axisAngle(axis, 180);
        }
        Vec c = a.cross(b);
        return new Quat(1 + d, c.x(), c.y(), c.z()).normalize();
    }

    /** This rotation after {@code o}: {@code a.mul(b).rotate(v) == a.rotate(b.rotate(v))}. */
    public Quat mul(Quat o) {
        return new Quat(
                w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quat conjugate() {
        return new Quat(w, -x, -y, -z);
    }

    /** The rotation that undoes this one. */
    public Quat inverse() {
        return conjugate();
    }

    public Quat normalize() {
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        return l < 1e-12 ? IDENTITY : new Quat(w / l, x / l, y / l, z / l);
    }

    public Vec rotate(Vec v) {
        // v' = v + 2w(q x v) + 2 q x (q x v)
        Vec q = new Vec(x, y, z);
        Vec t = q.cross(v).mul(2);
        return v.add(t.mul(w)).add(q.cross(t));
    }

    /** Spherical interpolation: the rotation {@code t} of the way to {@code o}, at an even pace. */
    public Quat slerp(Quat o, double t) {
        double cos = w * o.w + x * o.x + y * o.y + z * o.z;
        Quat b = o;
        if (cos < 0) {
            cos = -cos;
            b = new Quat(-o.w, -o.x, -o.y, -o.z);
        }
        if (cos > 0.9995) {
            return new Quat(w + (b.w - w) * t, x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t).normalize();
        }
        double angle = Math.acos(cos);
        double s = Math.sin(angle);
        double ka = Math.sin((1 - t) * angle) / s;
        double kb = Math.sin(t * angle) / s;
        return new Quat(w * ka + b.w * kb, x * ka + b.x * kb, y * ka + b.y * kb, z * ka + b.z * kb);
    }

    /** How far this turns, in degrees (0 to 180). */
    public double angle() {
        return Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(w))));
    }
}
