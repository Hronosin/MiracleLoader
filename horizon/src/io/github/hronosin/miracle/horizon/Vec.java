package io.github.hronosin.miracle.horizon;

/**
 * A point or a direction in three dimensions, immutable, in doubles. The same axes as the game:
 * x east, y up, z south. {@link Geodesic} turns it into the game's {@code Vec3} and back.
 *
 * <pre>{@code
 * Vec aim = target.sub(eye).normalize();
 * Vec side = aim.cross(Vec.UP).normalize();
 * Vec halfway = a.lerp(b, 0.5);
 * }</pre>
 */
public record Vec(double x, double y, double z) {

    public static final Vec ZERO = new Vec(0, 0, 0);
    public static final Vec ONE = new Vec(1, 1, 1);
    public static final Vec UP = new Vec(0, 1, 0);
    public static final Vec DOWN = new Vec(0, -1, 0);
    public static final Vec NORTH = new Vec(0, 0, -1);
    public static final Vec SOUTH = new Vec(0, 0, 1);
    public static final Vec EAST = new Vec(1, 0, 0);
    public static final Vec WEST = new Vec(-1, 0, 0);

    public static Vec of(double x, double y, double z) {
        return new Vec(x, y, z);
    }

    /** The direction a yaw and pitch look in, in the game's degrees (yaw 0 = south, pitch -90 = up). */
    public static Vec look(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double c = Math.cos(pitch);
        return new Vec(-Math.sin(yaw) * c, -Math.sin(pitch), Math.cos(yaw) * c);
    }

    public Vec add(Vec o) {
        return new Vec(x + o.x, y + o.y, z + o.z);
    }

    public Vec add(double dx, double dy, double dz) {
        return new Vec(x + dx, y + dy, z + dz);
    }

    public Vec sub(Vec o) {
        return new Vec(x - o.x, y - o.y, z - o.z);
    }

    public Vec mul(double k) {
        return new Vec(x * k, y * k, z * k);
    }

    /** Component by component. */
    public Vec mul(Vec o) {
        return new Vec(x * o.x, y * o.y, z * o.z);
    }

    public Vec div(double k) {
        return new Vec(x / k, y / k, z / k);
    }

    public Vec negate() {
        return new Vec(-x, -y, -z);
    }

    public double dot(Vec o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec cross(Vec o) {
        return new Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double distance(Vec o) {
        return sub(o).length();
    }

    /** Length 1 in the same direction; the zero vector stays zero. */
    public Vec normalize() {
        double l = length();
        return l < 1e-12 ? ZERO : div(l);
    }

    /** Same direction, this length. */
    public Vec withLength(double length) {
        return normalize().mul(length);
    }

    /** At most this long. */
    public Vec clampLength(double max) {
        double l = length();
        return l > max ? mul(max / l) : this;
    }

    /** {@code t} = 0 is this, 1 is {@code o}; beyond that it keeps going. */
    public Vec lerp(Vec o, double t) {
        return new Vec(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** The angle to another direction, in degrees (0 to 180). */
    public double angleTo(Vec o) {
        double d = length() * o.length();
        if (d < 1e-12) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(o) / d))));
    }

    /** The part of this along {@code onto}. */
    public Vec project(Vec onto) {
        double l2 = onto.lengthSquared();
        return l2 < 1e-12 ? ZERO : onto.mul(dot(onto) / l2);
    }

    /** Bounced off a surface with this normal (the normal needn't be length 1). */
    public Vec reflect(Vec normal) {
        Vec n = normal.normalize();
        return sub(n.mul(2 * dot(n)));
    }

    /** Turned about an axis through the origin, by degrees (right-handed). */
    public Vec rotate(Vec axis, double degrees) {
        return Quat.axisAngle(axis, degrees).rotate(this);
    }

    /** Yaw in the game's degrees for this direction (0 = south, 90 = west). */
    public double yaw() {
        return Math.toDegrees(Math.atan2(-x, z));
    }

    /** Pitch in the game's degrees for this direction (-90 = straight up). */
    public double pitch() {
        double h = Math.sqrt(x * x + z * z);
        return Math.toDegrees(-Math.atan2(y, h));
    }

    /** The integer block coordinates this point is in: {x, y, z}. */
    public int[] block() {
        return new int[] {(int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)};
    }

    public boolean near(Vec o, double epsilon) {
        return Math.abs(x - o.x) <= epsilon && Math.abs(y - o.y) <= epsilon && Math.abs(z - o.z) <= epsilon;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", x, y, z);
    }
}
