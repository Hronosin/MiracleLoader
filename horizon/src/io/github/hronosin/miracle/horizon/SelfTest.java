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
        foam();
        penrose();
        wormhole();
        lensing();
        if (args.length == 2) {
            corpus(java.nio.file.Path.of(args[0]), java.nio.file.Path.of(args[1]));
        }
        System.out.println("event-horizon self-test: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            throw new AssertionError(failed + " check(s) failed");   // exits with 1, no System.exit
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

    private static void foam() {
        QuantumFoam.Foam a = QuantumFoam.seeded(1);
        QuantumFoam.Foam b = QuantumFoam.seeded(1);
        boolean same = true;
        for (int i = 0; i < 100; i++) {
            same &= a.nextLong() == b.nextLong();
        }
        check("foam: the same seed, the same stream", same);
        check("foam: another seed, another stream", QuantumFoam.seeded(1).nextLong() != QuantumFoam.seeded(2).nextLong());
        check("foam: the same key, the same stream", QuantumFoam.keyed(5, "mymod:daily", 3, true).nextLong()
                == QuantumFoam.keyed(5, "mymod:daily", 3, true).nextLong());
        check("foam: another key, another stream", QuantumFoam.keyed(5, "mymod:daily", 3).nextLong()
                != QuantumFoam.keyed(5, "mymod:daily", 4).nextLong());
        check("foam: 1 and 1L are the same key, \"1\" isn't", QuantumFoam.keyed(5, 1).nextLong() == QuantumFoam.keyed(5, 1L).nextLong()
                && QuantumFoam.keyed(5, 1).nextLong() != QuantumFoam.keyed(5, "1").nextLong());
        java.util.UUID u = java.util.UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        check("foam: UUIDs and Vecs as keys", QuantumFoam.keyed(9, u, new Vec(1, 2, 3)).nextLong()
                == QuantumFoam.keyed(9, java.util.UUID.fromString(u.toString()), new Vec(1, 2, 3)).nextLong());
        QuantumFoam.Foam used = QuantumFoam.keyed(7, "x");
        used.nextLong();
        used.nextLong();
        check("foam: fork doesn't care about draws", used.fork("y").nextLong() == QuantumFoam.keyed(7, "x").fork("y").nextLong());
        boolean refused = false;
        try {
            QuantumFoam.keyed(1, new Object());
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("foam: an unusable key is refused", refused);
        check("foam: a world's secret isn't its seed, and is stable", QuantumFoam.secret(42) != 42
                && QuantumFoam.secret(42) == QuantumFoam.secret(42) && QuantumFoam.secret(42) != QuantumFoam.secret(43));

        QuantumFoam.Foam r = QuantumFoam.seeded(99);
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int i = 0; i < 10_000; i++) {
            int v = r.between(-3, 3);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        check("foam: between includes both ends", lo == -3 && hi == 3);
        check("foam: between on the edge", r.between(Integer.MAX_VALUE - 1, Integer.MAX_VALUE) >= Integer.MAX_VALUE - 1);
        List<Integer> nums = List.of(1, 2, 3, 4, 5, 6, 7, 8);
        List<Integer> sample = r.sample(nums, 5);
        check("foam: sample is distinct", sample.size() == 5 && new java.util.HashSet<>(sample).size() == 5 && nums.containsAll(sample));
        List<Integer> sh = r.shuffled(nums);
        check("foam: shuffled keeps everything", sh.size() == 8 && new java.util.HashSet<>(sh).equals(new java.util.HashSet<>(nums)));
        Vec sum = Vec.ZERO;
        boolean unit = true;
        for (int i = 0; i < 20_000; i++) {
            Vec d = r.direction();
            unit &= near(d.length(), 1);
            sum = sum.add(d);
        }
        check("foam: directions are unit and even", unit && sum.length() / 20_000 < 0.02);
        Shape ball = Shape.sphere(new Vec(10, 64, 10), 3);
        boolean in = true;
        for (int i = 0; i < 200; i++) {
            in &= r.inside(ball).map(ball::contains).orElse(false);
        }
        check("foam: points inside a shape", in);

        QuantumFoam.Pool<String> loot = QuantumFoam.<String>pool().add("diamond", 1).add("iron", 9).add("dirt", 90);
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < 200_000; i++) {
            seen.merge(loot.roll(r), 1, Integer::sum);
        }
        check("foam: pool weights", Math.abs(seen.get("dirt") / 200_000.0 - 0.9) < 0.005
                && Math.abs(seen.get("iron") / 200_000.0 - 0.09) < 0.004 && Math.abs(seen.get("diamond") / 200_000.0 - 0.01) < 0.002);
        QuantumFoam.Pool<String> rare = QuantumFoam.<String>pool().add("star", 1).add("moon", 3);
        QuantumFoam.Pool<String> chest = QuantumFoam.<String>pool().add(rare, 1).add("moon", 1).nothing(2);
        check("foam: chance of, nested pools included", near(chest.chanceOf("moon"), 0.25 * 0.75 + 0.25)
                && near(chest.chanceOf("star"), 0.0625) && near(chest.chanceOf(null), 0.5));
        check("foam: outcomes", chest.outcomes().containsAll(java.util.Arrays.asList("star", "moon", null)) && chest.outcomes().size() == 3);
        check("foam: an empty pool rolls nothing", QuantumFoam.<String>pool().roll(r) == null);
        boolean negative = false;
        try {
            QuantumFoam.<String>pool().add("x", -1);
        } catch (IllegalArgumentException e) {
            negative = true;
        }
        check("foam: negative weights refused", negative);
        QuantumFoam.Pool<String> named = QuantumFoam.pool("selftest:named");
        named.add("a", 1);
        check("foam: named pools are listed", QuantumFoam.named().get("selftest:named") == named);
        boolean twice = false;
        try {
            QuantumFoam.pool("selftest:named");
        } catch (IllegalArgumentException e) {
            twice = true;
        }
        check("foam: pool names are unique", twice);
        check("foam: histogram", QuantumFoam.histogram(loot, 1000).startsWith("null, 1000 rolls")
                && QuantumFoam.histogram(loot, 1000).contains("dirt:"));

        QuantumFoam.Bag<String> bag = QuantumFoam.bag(List.of("a", "b", "c", "d"));
        boolean deals = true;
        String last = null;
        boolean noRepeat = true;
        for (int round = 0; round < 300; round++) {
            java.util.Set<String> got = new java.util.HashSet<>();
            for (int i = 0; i < 4; i++) {
                String s = bag.next(r);
                noRepeat &= !s.equals(last);
                last = s;
                got.add(s);
            }
            deals &= got.size() == 4;
        }
        check("foam: a bag deals everything once a round", deals);
        check("foam: a bag never repeats across a reshuffle", noRepeat);

        QuantumFoam.Pity pity = QuantumFoam.pity(0.25);
        check("foam: pity's step gives exactly its chance", Math.abs(QuantumFoam.Pity.rate(QuantumFoam.Pity.stepFor(0.25)) - 0.25) < 1e-9);
        int hits = 0;
        int streak = 0;
        int longest = 0;
        for (int i = 0; i < 200_000; i++) {
            if (pity.roll("p", r)) {
                hits++;
                streak = 0;
            } else {
                longest = Math.max(longest, ++streak);
            }
        }
        check("foam: pity hits as often as it says", Math.abs(hits / 200_000.0 - 0.25) < 0.005);
        check("foam: pity's dry streaks are short", longest < pity.longest() && pity.longest() <= 12);
        pity.roll("q", QuantumFoam.seeded(3));
        check("foam: pity streaks are per owner", pity.streak("q") <= 1 && pity.streak("nobody") == 0);
        QuantumFoam.reset();
        check("foam: reset forgets streaks", pity.streak("p") == 0 && pity.streak("q") == 0);
    }

    private enum Weather { CLEAR, RAIN, THUNDER }

    private static void penrose() {
        Penrose.Number blade = Penrose.number("selftest:blade", 7).range(0, 100);
        Penrose.touch("selftest:blade").by("swordplus").add(2);
        Penrose.touch("selftest:blade").by("enchanty").multiply(1.5);
        Penrose.Layer cap = Penrose.touch("selftest:blade").by("mymod").clamp(0, 12);
        check("penrose: clamp((base + adds) * factors)", blade.getAsDouble() == 12.0);
        cap.remove();
        check("penrose: removing a layer, and the cache notices", blade.getAsDouble() == 13.5);
        check("penrose: owner and explain", blade.explain(null).contains("+ 2.0 (swordplus)") && blade.explain(null).contains("× 1.5 (enchanty)")
                && blade.explain(null).startsWith("selftest:blade = 13.5"));
        check("penrose: apply to a base of one's own", blade.apply(10, null) == 18.0);

        // the same result to the last bit, whatever the order
        Random rnd = new Random(1);
        boolean stable = true;
        double expected = Double.NaN;
        List<Object[]> ops = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ops.add(new Object[] {"mod" + rnd.nextInt(6), rnd.nextInt(3), rnd.nextDouble() * 3 - 1});
        }
        for (int trial = 0; trial < 200; trial++) {
            String id = "selftest:shuffle_" + trial;
            Penrose.Number n = Penrose.number(id, 0.1);
            List<Object[]> order = new java.util.ArrayList<>(ops);
            java.util.Collections.shuffle(order, rnd);
            for (Object[] op : order) {
                Penrose.Touch<Object> t = Penrose.touch(id).by((String) op[0]);
                double x = (double) op[2];
                switch ((int) op[1]) {
                    case 0 -> t.add(x);
                    case 1 -> t.multiply(1 + x / 10);
                    default -> t.add(x / 7);
                }
            }
            double v = n.getAsDouble();
            if (trial == 0) {
                expected = v;
            } else {
                stable &= Double.doubleToLongBits(v) == Double.doubleToLongBits(expected);
            }
        }
        check("penrose: any load order, the same bits", stable);

        Penrose.Number speed = Penrose.number("selftest:speed", 1);
        Penrose.touch("selftest:speed").by("a").set(2);
        Penrose.touch("selftest:speed").by("b").priority(5).set(3);
        check("penrose: the highest priority set wins", speed.getAsDouble() == 3.0 && speed.conflicts(null).isEmpty());
        Penrose.touch("selftest:speed").by("c").priority(5).set(4);
        check("penrose: a tie with different values: nobody wins", speed.getAsDouble() == 1.0
                && speed.conflicts(null).size() == 1 && speed.conflicts(null).get(0).contains("'b' sets 3.0")
                && speed.conflicts(null).get(0).contains("'c' sets 4.0"));
        Penrose.touch("selftest:speed").by("b").priority(5).set(4);
        check("penrose: within a mod, its last set counts; agreeing is fine", speed.getAsDouble() == 4.0 && speed.conflicts(null).isEmpty());

        Penrose.Number hp = Penrose.number("selftest:hp", 20).range(1, 30);
        Penrose.touch("selftest:hp").by("x").clamp(0, 5);
        Penrose.touch("selftest:hp").by("y").clamp(10, 50);
        check("penrose: ranges that don't overlap: none applies, the owner's range still does", hp.getAsDouble() == 20.0
                && hp.conflicts(null).size() == 1);
        Penrose.touch("selftest:hp").by("z").add(100);
        check("penrose: the owner's range is last", hp.getAsDouble() == 30.0);

        Penrose.Number ctx = Penrose.number("selftest:ctx", 10);
        Penrose.touch("selftest:ctx", String.class).by("s").add(5);
        Penrose.touch("selftest:ctx", Integer.class).by("i").when(i -> i > 3).multiply(2);
        Penrose.touch("selftest:ctx").by("f").add(o -> o instanceof Integer i ? i : 0);
        check("penrose: typed and conditional layers", ctx.getAsDouble() == 10.0 && ctx.getAsDouble("hi") == 15.0
                && ctx.getAsDouble(2) == 12.0 && ctx.getAsDouble(4) == 28.0);
        Penrose.touch("selftest:ctx").by("bad").when(o -> o.toString().isEmpty()).add(1000);
        check("penrose: a condition that throws doesn't count", ctx.getAsDouble() == 10.0);

        Penrose.touch("selftest:late").by("early").add(3);
        check("penrose: touched before declared, waiting", Penrose.declared("selftest:late").isEmpty()
                && Penrose.layers("selftest:late").size() == 1);
        check("penrose: declared later picks them up", Penrose.number("selftest:late", 1).getAsDouble() == 4.0);
        boolean again = false;
        try {
            Penrose.number("selftest:late", 2);
        } catch (IllegalArgumentException e) {
            again = true;
        }
        check("penrose: one declaration per id", again);
        boolean badId = false;
        try {
            Penrose.touch("No Spaces Please");
        } catch (IllegalArgumentException e) {
            badId = true;
        }
        check("penrose: ids look like mod:name", badId);

        Penrose.Choice<Boolean> pvp = Penrose.flag("selftest:pvp", true);
        Penrose.touch("selftest:pvp").by("peace").set(false);
        Penrose.touch("selftest:pvp").by("chaos").add(1);
        check("penrose: flags take set, and nothing else", !pvp.get() && pvp.conflicts(null).size() == 1);
        Penrose.Choice<Weather> weather = Penrose.choice("selftest:weather", Weather.CLEAR);
        Penrose.touch("selftest:weather").by("storms").priority(1).set(Weather.THUNDER);
        Penrose.touch("selftest:weather").by("wrong").priority(2).set("RAIN");
        check("penrose: choices, and a wrong type doesn't count", weather.get() == Weather.THUNDER
                && weather.explain(null).contains("THUNDER (storms, priority 1)"));
        check("penrose: everything is listed", Penrose.ids().containsAll(List.of("selftest:blade", "selftest:pvp", "selftest:late")));
    }

    private static void wormhole() {
        java.util.function.Function<String, String> up = String::toUpperCase;
        java.util.function.Function<String, String> down = String::toLowerCase;
        Wormhole.Offer a = Wormhole.offer("selftest:case", up);
        check("wormhole: one offer, found", Wormhole.seek("selftest:case", java.util.function.Function.class).orElse(null) == up);
        check("wormhole: the wrong type finds nothing", Wormhole.seek("selftest:case", Runnable.class).isEmpty()
                && Wormhole.seek("selftest:nobody", Object.class).isEmpty());
        Wormhole.Offer b = Wormhole.offer("selftest:case", down).priority(5);
        check("wormhole: the higher priority wins; all() lists both", Wormhole.seek("selftest:case", Object.class).orElse(null) == down
                && Wormhole.all("selftest:case", Object.class).equals(List.of(down, up)));
        Wormhole.Offer same = Wormhole.offer("selftest:case", down).priority(5);
        check("wormhole: the same offer twice is no tie", Wormhole.seek("selftest:case", Object.class).orElse(null) == down);
        Wormhole.Offer c = Wormhole.offer("selftest:case", (java.util.function.Function<String, String>) String::strip).priority(5);
        check("wormhole: a tie: nobody wins", Wormhole.seek("selftest:case", Object.class).isEmpty()
                && Wormhole.report().contains("selftest:case: nobody wins"));
        c.withdraw();
        same.withdraw();
        check("wormhole: withdrawn", Wormhole.seek("selftest:case", Object.class).orElse(null) == down
                && Wormhole.report().contains("  <- wins") && a.mod().equals("event-horizon"));
        b.withdraw();
        a.withdraw();
        check("wormhole: all gone", Wormhole.all("selftest:case", Object.class).isEmpty());
        Wormhole.Bridge bridge = Wormhole.to("create>=6.0");
        check("wormhole: no game, no bridge", !bridge.possible() && !bridge.open("nowhere.Bridge")
                && bridge.state() == Wormhole.State.CLOSED && bridge.why().equals("no MiracleLoader running"));
        boolean twice = false;
        try {
            bridge.open("nowhere.Bridge");
        } catch (IllegalStateException e) {
            twice = true;
        }
        check("wormhole: a bridge opens once", twice && Wormhole.bridges().contains(bridge)
                && Wormhole.report().contains("event-horizon -> create >= 6.0: closed (no MiracleLoader running) [nowhere.Bridge]"));
        boolean bad = false;
        try {
            Wormhole.to("Not A Mod");
        } catch (IllegalArgumentException e) {
            bad = true;
        }
        check("wormhole: mod ids are checked", bad);
        check("wormhole: versions compare like the loader's", Wormhole.compare("1.10", "1.9") > 0 && Wormhole.compare("6.0", "6") == 0
                && Wormhole.compare("6.0.1-beta", "6.0.2") < 0);
    }

    private static final String CLASSIC_VSH = String.join("\n",
            "#version 150",
            "",
            "#moj_import <minecraft:projection.glsl>",
            "",
            "in vec3 Position;",
            "in vec4 Color;",
            "",
            "out vec4 vertexColor;",
            "flat out int glow;",
            "",
            "void helper(in vec3 a, out vec3 b) {",
            "    b = a;",
            "}",
            "",
            "void main() {",
            "    gl_Position = ProjMat * vec4(Position, 1.0);",
            "    vertexColor = Color;",
            "}");

    private static void lensing() {
        String sep = Lensing.translate(CLASSIC_VSH, Lensing.Dialect.SEPARATE, Lensing.Stage.VERTEX);
        check("lensing: #moj_import becomes #include", sep.contains("#include <minecraft:projection.glsl>") && !sep.contains("moj_import"));
        check("lensing: the extension right after #version, raised to 330", sep.startsWith("#version 330\n" + Lensing.EXTENSION + "\n"));
        check("lensing: inputs and outputs numbered in order", sep.contains("layout(location = 0) in vec3 Position;")
                && sep.contains("layout(location = 1) in vec4 Color;") && sep.contains("layout(location = 0) out vec4 vertexColor;")
                && sep.contains("layout(location = 1) flat out int glow;"));
        check("lensing: function parameters left alone", sep.contains("void helper(in vec3 a, out vec3 b) {"));
        check("lensing: already separate, nothing changes", Lensing.translate(sep, Lensing.Dialect.SEPARATE, Lensing.Stage.VERTEX).equals(sep));
        String back = Lensing.translate(sep, Lensing.Dialect.CLASSIC, Lensing.Stage.VERTEX);
        check("lensing: and back to classic", back.contains("#moj_import <minecraft:projection.glsl>") && !back.contains("location")
                && !back.contains("#extension") && back.contains("\nin vec3 Position;") && back.contains("\nflat out int glow;"));
        check("lensing: classic stays classic", Lensing.translate(CLASSIC_VSH, Lensing.Dialect.CLASSIC, Lensing.Stage.VERTEX).equals(CLASSIC_VSH));
        String mixed = "#version 330\nlayout(location = 0) out vec4 fragColor;\nout vec4 extra;\nin vec2 texCoord;\n";
        String m2 = Lensing.translate(mixed, Lensing.Dialect.SEPARATE, Lensing.Stage.FRAGMENT);
        check("lensing: explicit locations kept, others take the free ones", m2.contains("layout(location = 0) out vec4 fragColor;")
                && m2.contains("layout(location = 1) out vec4 extra;") && m2.contains("layout(location = 0) in vec2 texCoord;"));
        String commented = "#version 330\n/*\nin vec3 Fake;\n*/\nin vec3 Real;\nlayout(std140) uniform Block {\n    vec4 Thing;\n};\n";
        String c2 = Lensing.translate(commented, Lensing.Dialect.SEPARATE, Lensing.Stage.VERTEX);
        check("lensing: comments and uniform blocks untouched", c2.contains("\nin vec3 Fake;\n") && c2.contains("layout(location = 0) in vec3 Real;")
                && c2.contains("layout(std140) uniform Block {"));
        check("lensing: includes keep their in/out, lose nothing else", Lensing.translate("#moj_import <a:b.glsl>\nout vec4 x;\n",
                Lensing.Dialect.SEPARATE, Lensing.Stage.INCLUDE).equals("#include <a:b.glsl>\nout vec4 x;\n"));
        check("lensing: windows line ends survive", Lensing.translate("#version 330\r\nin vec3 P;\r\n", Lensing.Dialect.SEPARATE,
                Lensing.Stage.VERTEX).equals("#version 330\r\n" + Lensing.EXTENSION + "\r\nlayout(location = 0) in vec3 P;\r\n"));
        check("lensing: telling dialects apart", Lensing.dialectOf(CLASSIC_VSH) == Lensing.Dialect.CLASSIC
                && Lensing.dialectOf(sep) == Lensing.Dialect.SEPARATE);
        check("lensing: stages by file name", Lensing.Stage.of("mymod:shaders/post/warp.fsh") == Lensing.Stage.FRAGMENT
                && Lensing.Stage.of("x.VSH") == Lensing.Stage.VERTEX && Lensing.Stage.of("x.glsl") == Lensing.Stage.INCLUDE
                && Lensing.Stage.of("x.json") == null);
        check("lensing: no game, no dialect", Lensing.dialect() == null);
        List<Lensing.Field> fields = List.of(new Lensing.Field("A", "float", new float[] {1.5f}),
                new Lensing.Field("B", "vec3", new float[] {1, 2, 3}), new Lensing.Field("C", "vec2", new float[] {4, 5}),
                new Lensing.Field("D", "int", new float[] {7}));
        java.nio.ByteBuffer b = Lensing.std140(fields, n -> n.equals("C") ? new float[] {9, 10} : null);
        check("lensing: std140 offsets and padding", b.capacity() == 48 && b.getFloat(0) == 1.5f && b.getFloat(16) == 1
                && b.getFloat(24) == 3 && b.getFloat(32) == 9 && b.getFloat(36) == 10 && b.getInt(40) == 7);
        check("lensing: std140 vec4 and matrices align to 16", Lensing.std140(List.of(new Lensing.Field("x", "float", new float[] {1}),
                new Lensing.Field("m", "matrix4x4", new float[16])), n -> null).capacity() == 80
                && Lensing.std140(List.of(new Lensing.Field("v", "vec4", new float[] {1, 2, 3, 4})), n -> null).getFloat(12) == 4);
        boolean badType = false;
        try {
            Lensing.std140(List.of(new Lensing.Field("q", "quaternion", new float[0])), n -> null);
        } catch (IllegalArgumentException e) {
            badType = true;
        }
        check("lensing: unknown uniform types refused", badType);
        Lensing.uniform("selftest:warp", "Strength", () -> 0.5);
        Lensing.uniform("selftest:warp", "Center", 1f, 2f);
        check("lensing: uniforms kept by effect and name", Lensing.UNIFORMS.get("selftest:warp").get("Strength").get()[0] == 0.5f
                && Lensing.UNIFORMS.get("selftest:warp").get("Center").get()[1] == 2f && Lensing.screenEffectScale() == 1);
        Lensing.clearUniforms("selftest:warp");
        check("lensing: and forgotten", !Lensing.UNIFORMS.containsKey("selftest:warp"));
    }

    /** Every vanilla shader of a classic version and of a separate one, through the translator. */
    private static void corpus(java.nio.file.Path classicJar, java.nio.file.Path separateJar) {
        int[] n = {0, 0};
        boolean[] ok = {true, true, true, true};
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (int k = 0; k < 2; k++) {
            boolean classic = k == 0;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile((classic ? classicJar : separateJar).toFile())) {
                for (var e : java.util.Collections.list(z.entries())) {
                    Lensing.Stage stage = Lensing.Stage.of(e.getName());
                    if (!e.getName().startsWith("assets/minecraft/shaders/") || stage == null) {
                        continue;
                    }
                    String src = new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    n[k]++;
                    Lensing.Dialect own = classic ? Lensing.Dialect.CLASSIC : Lensing.Dialect.SEPARATE;
                    Lensing.Dialect other = classic ? Lensing.Dialect.SEPARATE : Lensing.Dialect.CLASSIC;
                    if (!Lensing.translate(src, own, stage).equals(src)) {
                        ok[k] = false;
                        bad.add("changed in its own dialect: " + e.getName());
                    }
                    String there = Lensing.translate(src, other, stage);
                    String back = Lensing.translate(there, own, stage);
                    if (classic && !back.equals(src)) {
                        ok[2] = false;
                        bad.add("classic round trip differs: " + e.getName());
                    }
                    if (stage != Lensing.Stage.INCLUDE) {
                        for (String line : there.split("\n")) {
                            String t = line.strip();
                            boolean decl = (t.startsWith("in ") || t.startsWith("out ") || t.startsWith("flat ")) && t.endsWith(";");
                            boolean located = t.startsWith("layout(location");
                            if (other == Lensing.Dialect.SEPARATE && decl && !located) {
                                ok[3] = false;
                                bad.add("unnumbered: " + e.getName() + ": " + t);
                            }
                            if (other == Lensing.Dialect.CLASSIC && (located || t.startsWith("#include") || t.startsWith("#extension GL_ARB_sep"))) {
                                ok[3] = false;
                                bad.add("still separate: " + e.getName() + ": " + t);
                            }
                        }
                    }
                }
            } catch (java.io.IOException e) {
                check("lensing corpus: readable jars (" + e + ")", false);
                return;
            }
        }
        bad.stream().limit(10).forEach(b -> System.out.println("  " + b));
        check("lensing corpus: " + n[0] + " classic shaders unchanged in their own dialect", ok[0] && n[0] > 50);
        check("lensing corpus: " + n[1] + " separate shaders unchanged in their own dialect", ok[1] && n[1] > 50);
        check("lensing corpus: classic -> separate -> classic gives back every file exactly", ok[2]);
        check("lensing corpus: every in/out numbered for separate, every trace gone for classic", ok[3]);
    }
}
