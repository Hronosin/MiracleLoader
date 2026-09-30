package io.github.hronosin.miracle.horizon;

import java.util.List;
import java.util.Random;

/**
 * Event Horizon's own checks, for the parts that need no game: the math, the shapes, the
 * scheduler. {@code java -cp miracle-loader.jar:miracle-toolchain.jar:event-horizon.jar
 * io.github.hronosin.miracle.horizon.SelfTest}; exits 1 if anything's off.
 */
final class SelfTest {

    private static int passed;
    private static int failed;

    private SelfTest() {
    }

    public static void main(String[] args) {
        vectors();
        rotations();
        curves();
        easing();
        noise();
        shapes();
        ballistics();
        frames();
        scheduler();
        System.out.println("event-horizon self-test: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void vectors() {
        Vec a = new Vec(1, 2, 3);
        Vec b = new Vec(4, -5, 6);
        check("vec: dot", near(a.dot(b), 4 - 10 + 18));
        check("vec: cross is square to both", near(a.cross(b).dot(a), 0) && near(a.cross(b).dot(b), 0));
        check("vec: normalize", near(b.normalize().length(), 1) && Vec.ZERO.normalize().equals(Vec.ZERO));
        check("vec: look south, west, up", Vec.look(0, 0).near(Vec.SOUTH, 1e-9) && Vec.look(90, 0).near(Vec.WEST, 1e-9)
                && Vec.look(0, -90).near(Vec.UP, 1e-9));
        Vec l = Vec.look(37, -21);
        check("vec: yaw and pitch round trip", near(l.yaw(), 37) && near(l.pitch(), -21));
        check("vec: angle", near(Vec.EAST.angleTo(Vec.UP), 90) && near(Vec.EAST.angleTo(Vec.WEST), 180));
        check("vec: reflect off the floor", new Vec(1, -1, 0).reflect(Vec.UP).near(new Vec(1, 1, 0), 1e-9));
        check("vec: rotate", Vec.SOUTH.rotate(Vec.UP, 90).near(Vec.EAST, 1e-9));
        check("vec: block", java.util.Arrays.equals(new Vec(-0.5, 2.9, 3).block(), new int[] {-1, 2, 3}));
    }

    private static void rotations() {
        Random r = new Random(7);
        boolean ok = true;
        for (int i = 0; i < 50; i++) {
            double yaw = r.nextDouble() * 360 - 180;
            double pitch = r.nextDouble() * 180 - 90;
            ok &= Quat.yawPitch(yaw, pitch).rotate(Vec.SOUTH).near(Vec.look(yaw, pitch), 1e-9);
        }
        check("quat: yaw and pitch look where the game looks", ok);
        Vec from = new Vec(1, 2, -1);
        Vec to = new Vec(-3, 0.5, 2);
        check("quat: between", Quat.between(from, to).rotate(from.normalize()).near(to.normalize(), 1e-9));
        check("quat: between opposites", Quat.between(Vec.UP, Vec.DOWN).rotate(Vec.UP).near(Vec.DOWN, 1e-9));
        Quat q = Quat.axisAngle(Vec.UP, 120);
        check("quat: slerp halfway", near(Quat.IDENTITY.slerp(q, 0.5).angle(), 60));
        check("quat: inverse undoes", q.inverse().mul(q).rotate(Vec.EAST).near(Vec.EAST, 1e-9));
        check("quat: composition", Quat.axisAngle(Vec.UP, 90).mul(Quat.axisAngle(Vec.UP, 90)).rotate(Vec.SOUTH)
                .near(Vec.NORTH, 1e-9));
    }

    private static void curves() {
        Vec a = Vec.ZERO;
        Vec b = new Vec(10, 0, 0);
        Curve bz = Curve.bezier(a, new Vec(5, 10, 0), b);
        check("curve: bezier ends", bz.at(0).near(a, 1e-9) && bz.at(1).near(b, 1e-9));
        check("curve: quadratic bezier middle", bz.at(0.5).near(new Vec(5, 5, 0), 1e-9));
        Vec[] pts = {Vec.ZERO, new Vec(3, 1, 0), new Vec(6, -2, 1), new Vec(9, 0, 4)};
        Curve through = Curve.through(pts);
        boolean passes = true;
        for (int i = 0; i < pts.length; i++) {
            passes &= through.at((double) i / (pts.length - 1)).near(pts[i], 1e-6);
        }
        check("curve: through every point", passes);
        check("curve: line length", Math.abs(Curve.line(a, b).length() - 10) < 1e-9);
        List<Vec> even = bz.evenly(9);
        double first = even.get(0).distance(even.get(1));
        boolean evenly = even.size() == 9 && even.get(8).near(b, 1e-6);
        for (int i = 1; i < even.size(); i++) {
            evenly &= Math.abs(even.get(i - 1).distance(even.get(i)) - first) < first * 0.02;
        }
        check("curve: evenly spaced", evenly);
        check("curve: direction", Curve.line(a, b).direction(0.5).near(Vec.EAST, 1e-6));
    }

    private static void easing() {
        boolean ends = true;
        for (Ease e : Ease.values()) {
            ends &= near(e.applyAsDouble(0), 0) && Math.abs(e.applyAsDouble(1) - 1) < 1e-3;
        }
        check("ease: every one goes from 0 to 1", ends);
        check("ease: clamps", near(Ease.LINEAR.applyAsDouble(2), 1) && near(Ease.IN_QUAD.between(10, 20, 0.5), 12.5));
        check("ease: back overshoots", Ease.OUT_BACK.applyAsDouble(0.7) > 1);
    }

    private static void noise() {
        Noise n = new Noise(42);
        Noise same = new Noise(42);
        Noise other = new Noise(43);
        check("noise: the same seed, the same noise", n.at(1.3, 2.7, -0.4) == same.at(1.3, 2.7, -0.4));
        check("noise: another seed, other noise", n.at(1.3, 2.7, -0.4) != other.at(1.3, 2.7, -0.4));
        check("noise: zero on whole numbers", near(n.at(3, -2, 7), 0));
        double lo = 0;
        double hi = 0;
        for (int i = 0; i < 2000; i++) {
            double v = n.fbm(i * 0.137, i * 0.071, i * 0.019, 4);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        check("noise: fbm stays near -1..1 and varies", lo > -1.2 && hi < 1.2 && hi - lo > 0.5);
        check("noise: smooth", Math.abs(n.at(0.50, 0.3, 0.2) - n.at(0.501, 0.3, 0.2)) < 0.01);
    }

    private static void shapes() {
        Vec c = new Vec(0.5, 64.5, 0.5);
        Shape s = Shape.sphere(c, 2);
        check("shape: sphere contains", s.contains(c.add(1.9, 0, 0)) && !s.contains(c.add(2.1, 0, 0)));
        check("shape: sphere blocks", s.blocks().size() == 33);
        Shape hollow = Shape.sphere(c, 3).minus(Shape.sphere(c, 2));
        check("shape: hollow", !hollow.contains(c) && hollow.contains(c.add(2.5, 0, 0)));
        Shape cone = Shape.cone(Vec.ZERO, Vec.SOUTH, 60, 10);
        check("shape: cone", cone.contains(new Vec(0, 0, 5)) && cone.contains(new Vec(2.5, 0, 5))
                && !cone.contains(new Vec(3.5, 0, 5)) && !cone.contains(new Vec(0, 0, -1)) && !cone.contains(new Vec(0, 0, 11)));
        Shape beam = Shape.line(Vec.ZERO, new Vec(10, 0, 0), 0.5);
        check("shape: line", beam.contains(new Vec(5, 0.4, 0)) && !beam.contains(new Vec(5, 0.6, 0)) && !beam.contains(new Vec(11, 0, 0)));
        Shape cyl = Shape.cylinder(Vec.ZERO, 1, 3);
        check("shape: cylinder", cyl.contains(new Vec(0.5, 2.9, 0.5)) && !cyl.contains(new Vec(0, 3.1, 0)));
        check("shape: box and union", Shape.union(Shape.box(Vec.ZERO, Vec.ONE), Shape.box(new Vec(5, 5, 5), new Vec(6, 6, 6)))
                .blocks().size() == 2);
        check("shape: outline of a sphere is a shell", Hawking.outline(Shape.sphere(Vec.ZERO, 3), 0.5).stream()
                .allMatch(p -> p.length() > 2.2 && p.length() <= 3.0 + 1e-9));
    }

    private static void ballistics() {
        Vec from = new Vec(0, 64, 0);
        Vec to = new Vec(12, 70, -5);
        for (Ballistics.Body body : new Ballistics.Body[] {Ballistics.Body.LIVING, Ballistics.Body.ARROW, Ballistics.Body.VACUUM}) {
            Vec v = Ballistics.inTicks(from, to, 25, body);
            check("ballistics: lands in exactly that many ticks " + body, Ballistics.after(from, v, 25, body).near(to, 1e-9));
            // step it the way the game does
            Vec p = from;
            Vec vel = v;
            for (int i = 0; i < 25; i++) {
                p = p.add(vel);
                double vy = body.gravityFirst() ? (vel.y() - body.gravity()) * body.dragUp()
                        : vel.y() * body.dragUp() - body.gravity();
                vel = new Vec(vel.x() * body.dragSide(), vy, vel.z() * body.dragSide());
            }
            check("ballistics: the same as the game's own steps " + body, p.near(to, 1e-9));
        }
        var low = Ballistics.aim(from, new Vec(20, 64, 0), 1.5, Ballistics.Body.ARROW, false);
        var high = Ballistics.aim(from, new Vec(20, 64, 0), 1.5, Ballistics.Body.ARROW, true);
        check("ballistics: flat and lobbed shots", low.isPresent() && high.isPresent() && low.get().y() < high.get().y()
                && low.get().length() <= 1.5 && high.get().length() <= 1.5 && high.get().length() > 1.4);
        check("ballistics: out of range", Ballistics.aim(from, new Vec(1000, 64, 0), 1, Ballistics.Body.ARROW, false).isEmpty());
        check("ballistics: apex in a vacuum", near(Ballistics.apex(0.42, Ballistics.Body.VACUUM), 0.42 * 6 - 0.08 * 15));
        check("ballistics: a player's jump is about 1.25 blocks", Math.abs(Ballistics.apex(0.42, Ballistics.Body.LIVING) - 1.25) < 0.02);
    }

    private static void frames() {
        Geodesic.Frame f = Geodesic.Frame.of(new Vec(0, 65.62, 0), 0, 0);
        check("frame: right of south is west", f.right().near(Vec.WEST, 1e-9) && f.up().near(Vec.UP, 1e-9));
        check("frame: to world and back", f.toWorld(-1, 0, 2).near(new Vec(1, 65.62, 2), 1e-9)
                && f.toLocal(f.toWorld(3, -1, 4)).near(new Vec(3, -1, 4), 1e-9));
        Geodesic.Frame up = Geodesic.Frame.of(Vec.ZERO, 0, -90);
        check("frame: looking up, forward is up", up.forward().near(Vec.UP, 1e-9));
    }

    private static void scheduler() {
        Redshift clock = Redshift.client(); // no game here: tick it by hand
        StringBuilder log = new StringBuilder();
        clock.in(2, () -> log.append("a"));
        clock.in(0, () -> log.append("0"));
        Redshift.Task rep = clock.repeat(3, () -> log.append("r")).times(2);
        double[] last = {-1};
        int[] calls = {0};
        clock.during(4, t -> {
            last[0] = t;
            calls[0]++;
        });
        for (int i = 0; i < 10; i++) {
            clock.tick();
        }
        check("redshift: order and repeats", log.toString().equals("0arr") && !rep.cancelled());
        check("redshift: over ends at exactly 1", calls[0] == 4 && last[0] == 1.0);
        Redshift.Task cancelled = clock.in(1, () -> log.append("x"));
        cancelled.cancel();
        clock.tick();
        clock.tick();
        check("redshift: cancelled never runs", !log.toString().contains("x"));
        clock.reset();
    }
}
