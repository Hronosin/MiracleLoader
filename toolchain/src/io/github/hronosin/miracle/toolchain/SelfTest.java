package io.github.hronosin.miracle.toolchain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The parts of the library that need no game: scrolls, the wire format, the Inquisition, key
 * names. Run by test.sh:
 * {@code java -cp miracle-loader.jar:miracle-toolchain.jar io.github.hronosin.miracle.toolchain.SelfTest}.
 * One line per check; exits 1 if any failed.
 */
final class SelfTest {

    private static int passed;
    private static int failed;

    private SelfTest() {
    }

    public static void main(String[] args) throws java.io.IOException {
        if (args.length == 2 && args[0].equals("--geometry")) {
            // test.sh: does a geometry file read, and what's in it?
            Clay.Model m = Clay.read(java.nio.file.Files.readString(java.nio.file.Path.of(args[1])));
            List<String> names = new ArrayList<>();
            java.util.ArrayDeque<Clay.Part> todo = new java.util.ArrayDeque<>(m.parts());
            while (!todo.isEmpty()) {
                Clay.Part p = todo.poll();
                names.add(p.name());
                todo.addAll(p.children());
            }
            System.out.println("geometry " + m.textureWidth() + "x" + m.textureHeight() + ": " + String.join(", ", names));
            return;
        }
        Scroll s = new Scroll().writeString("amen").writeInt(-3).writeLong(Long.MAX_VALUE).writeDouble(1.5)
                .writeBoolean(true).writeBytes(new byte[] {1, 2, 3});
        Scroll r = Scroll.read(s.bytes());
        check("scroll round trip", r.readString().equals("amen") && r.readInt() == -3 && r.readLong() == Long.MAX_VALUE
                && r.readDouble() == 1.5 && r.readBoolean() && Arrays.equals(r.readBytes(), new byte[] {1, 2, 3})
                && !r.hasMore());
        check("scroll shows itself", s.toString().equals("[string \"amen\", int -3, long 9223372036854775807, double 1.5,"
                + " boolean true, 3 bytes]"));
        Scroll wrong = Scroll.read(new Scroll().writeString("x").bytes());
        check("scroll refuses reading out of order", throwsWith(wrong::readInt, "Expected an int at this point of the scroll, found a string"));
        check("scroll refuses reading past the end", throwsWith(() -> Scroll.read(new byte[0]).readInt(), "the scroll ends here"));
        check("scroll refuses lying lengths", throwsWith(() -> Scroll.read(new byte[] {'S', 100, 'a'}).readString(),
                "claims 100 bytes"));
        check("a written scroll can't be read", throwsWith(() -> new Scroll().readInt(), "still being written"));

        check("channel hash is FNV-1a", Telepathy.hash("") == 0x811C9DC5 && Telepathy.hash("a") == 0xE40C292C);

        long[] seq = {0};
        List<byte[]> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(Litany.frame(Telepathy.hash("test:ch" + i), new Scroll().writeInt(i).bytes()));
        }
        List<byte[]> one = Litany.batches(messages, seq, 32_000);
        Litany.Batch b = Litany.parse(one.getFirst());
        check("a tick's messages travel as one batch", one.size() == 1 && b.seq() == 1 && b.messages().size() == 5
                && Scroll.read(b.messages().get(3).body()).readInt() == 3);
        check("names never cross the wire", !new String(one.getFirst(), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("test:ch"));
        List<byte[]> split = Litany.batches(messages, seq, 16 + 2 * messages.getFirst().length);
        check("big ticks split, numbering on", split.size() == 3 && Litany.parse(split.get(2)).seq() == 4);
        byte[] batch = one.getFirst();
        check("garbage is refused", throwsWith(() -> Litany.parse("hello".getBytes()), "not a Telepathy batch"));
        check("truncated batches are refused", throwsWith(() -> Litany.parse(Arrays.copyOf(batch, batch.length - 1)), "claims"));
        byte[] longer = Arrays.copyOf(batch, batch.length + 2);
        check("stray bytes are refused", throwsWith(() -> Litany.parse(longer), "stray bytes"));

        Inquisition inq = new Inquisition();
        check("an honest rhythm is fine", inq.examine(1, 10, 4).isEmpty() && inq.examine(2, 11, 4).isEmpty()
                && inq.examine(3, 11, 4).isEmpty());
        check("a replayed batch is heresy", inq.examine(3, 12, 4).getFirst().contains("where #4 was due"));
        check("a skipped number is heresy", inq.examine(9, 13, 4).getFirst().contains("batch #9 where #4 was due"));
        check("a forged number doesn't derail the honest count", inq.examine(4, 14, 4).isEmpty());
        Inquisition flood = new Inquisition();
        List<String> last = List.of();
        for (int i = 1; i <= 5; i++) {
            last = flood.examine(i, 7, 4);
        }
        check("a flood in one tick is heresy", last.size() == 1 && last.getFirst().contains("5 batches in one tick"));

        check("key names", Gestures.keyName("g").equals("key.keyboard.g") && Gestures.keyName("F6").equals("key.keyboard.f6")
                && Gestures.keyName("KP_5").equals("key.keyboard.keypad.5") && Gestures.keyName("left alt").equals("key.keyboard.left.alt")
                && Gestures.keyName("NONE").equals("key.keyboard.unknown") && Gestures.keyName("GRAVE").equals("key.keyboard.grave.accent")
                && Gestures.keyName("key.mouse.middle").equals("key.mouse.middle"));
        check("unknown keys are refused", throwsWith(() -> Gestures.keyName("ANY"), "isn't a key this library knows"));

        // Communion: the manifest and the judgement.
        Communion.Manifest server = manifest(7, 3, "miracle-toolchain 0.3.0 bound", "hallelujah 0.3.0 bound",
                "smite-only 1.0", "shared 2.0 bound");
        Communion.Manifest back = Communion.Manifest.read(server.bytes());
        check("communion manifest round trip", back.equals(server));
        check("communion refuses garbage", throwsWith(() -> Communion.Manifest.read(new Scroll().writeInt(1).bytes()),
                "not a communion manifest"));
        check("same bound mods, same creations: welcome", Communion.judge(server,
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "hallelujah 0.3.0 bound", "shared 2.0 bound",
                        "minimap 4.2")).isEmpty());
        List<String> sins = Communion.judge(server,
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 1.9 bound", "chaos 0.1 bound"));
        check("a missing mod is named", sins.contains("Missing: hallelujah 0.3.0"));
        check("a different version is named", sins.contains("Different version: shared (yours 1.9, the server's 2.0)"));
        check("a mod the server lacks is named", sins.contains("The server doesn't have: chaos 0.1 (remove it to join)"));
        check("one-sided mods are nobody's business", sins.size() == 3);
        check("same mods, different creations", Communion.judge(server,
                manifest(8, 4, "miracle-toolchain 0.3.0 bound", "hallelujah 0.3.0 bound", "shared 2.0 bound"))
                .getFirst().startsWith("Same mods, different creations: 4 new things in your game, 3 on the server"));
        check("the refusal says it all", Communion.refusal(sins).startsWith("Communion refused.")
                && Communion.refusal(sins).contains("\n  Missing: hallelujah 0.3.0"));
        check("strangers get the list to install", Communion.strangerRefusal(server).contains("hallelujah 0.3.0")
                && !Communion.strangerRefusal(server).contains("smite-only"));
        // communes_since: the newer side says which older versions it still plays with.
        Communion.Manifest ranged = manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 2.0 bound since=1.5");
        check("a range survives the wire", Communion.Manifest.read(ranged.bytes()).equals(ranged));
        byte[] old = new Scroll().writeInt(Communion.MAGIC).writeString("0.3.0").writeInt(1)
                .writeString("shared").writeString("1.9").writeBoolean(true).writeLong(7).writeInt(3).bytes();
        check("a 1.5 manifest (no ranges) still reads", Communion.Manifest.read(old).mods().getFirst().since() == null);
        check("an older client in the server's range: welcome", Communion.judge(ranged,
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 1.5 bound")).isEmpty());
        check("too old for the server's range: named, with the range", Communion.judge(ranged,
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 1.4 bound")).equals(List.of(
                "Different version: shared (yours 1.4, the server's 2.0, which plays with 1.5 and newer)")));
        check("a newer client's range covers an older server", Communion.judge(
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 2.0 bound"),
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 2.1 bound since=2.0")).isEmpty());
        check("an older side's range says nothing about newer versions", Communion.judge(
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 2.0 bound since=1.0"),
                manifest(7, 3, "miracle-toolchain 0.3.0 bound", "shared 2.1 bound")).size() == 1);
        check("creation fingerprints follow order", Communion.fingerprint(List.of("item a:b", "item a:c"))
                != Communion.fingerprint(List.of("item a:c", "item a:b")));

        // Bedrock geometry -> Java model parts: a zombie's head and right arm, in both editions' numbers.
        Clay.Model zombie = Clay.read("""
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.test", "texture_width": 64, "texture_height": 32},
                  "bones": [
                    {"name": "body", "pivot": [0, 24, 0], "cubes": [{"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16]}]},
                    {"name": "head", "parent": "body", "pivot": [0, 24, 0],
                     "cubes": [{"origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [0, 0]}]},
                    {"name": "rightArm", "parent": "body", "pivot": [-5, 22, 0], "rotation": [-90, 0, 0],
                     "cubes": [{"origin": [-8, 12, -2], "size": [4, 12, 4], "uv": [40, 16], "mirror": true}]},
                    {"name": "horn", "parent": "head", "pivot": [0, 32, 0],
                     "cubes": [{"origin": [-1, 32, -1], "size": [2, 4, 2], "uv": [56, 0], "pivot": [0, 32, 0], "rotation": [0, 0, 30]}]}
                  ]}]}
                """);
        Clay.Part body = zombie.parts().getFirst();
        Clay.Part zHead = body.children().get(0);
        Clay.Part zArm = body.children().get(1);
        Clay.Box headBox = zHead.boxes().getFirst();
        Clay.Box armBox = zArm.boxes().getFirst();
        check("geometry: texture size and one top-level bone", zombie.textureWidth() == 64 && zombie.textureHeight() == 32
                && zombie.parts().size() == 1 && body.name().equals("body") && body.y() == 0);
        check("geometry: the head box is vanilla's (-4, -8, -4)", zHead.name().equals("head") && headBox.x() == -4
                && headBox.y() == -8 && headBox.z() == -4 && headBox.w() == 8 && zHead.x() == 0 && zHead.y() == 0);
        check("geometry: the arm hangs at vanilla's (-5, 2, 0), box (-3, -2, -2)", zArm.x() == -5 && zArm.y() == 2
                && armBox.x() == -3 && armBox.y() == -2 && armBox.z() == -2 && armBox.u() == 40 && armBox.v() == 16
                && armBox.mirror());
        check("geometry: rotations carry over as they are", Math.abs(zArm.xRot() - (float) Math.toRadians(-90)) < 1e-5);
        Clay.Part horn = zHead.children().getFirst();
        check("geometry: a rotated cube becomes a part of its own", horn.boxes().isEmpty() && horn.children().size() == 1
                && horn.children().getFirst().name().equals("horn_r1")
                && Math.abs(horn.children().getFirst().zRot() - (float) Math.toRadians(30)) < 1e-5
                && horn.children().getFirst().boxes().getFirst().y() == -4);
        check("geometry: not a geometry file", throwsWith(() -> Clay.read("{\"hello\": 1}"), "no \"minecraft:geometry\""));
        check("geometry: broken JSON names the line", throwsWith(() -> Clay.read("{\n\"a\": [1, 2,\n}"), "line 3"));

        // Bedrock animations: keyframes, jumps, curves and Molang.
        List<String> animNotes = new ArrayList<>();
        java.util.Map<String, Liturgy.Rite> rites = Liturgy.read("""
                {"format_version": "1.8.0", "animations": {
                  "animation.test.walk": {"loop": true, "animation_length": 1.0, "bones": {
                    "leg": {"rotation": {"0.0": [30, 0, 0], "0.5": [-30, 0, 0], "1.0": [30, 0, 0]}},
                    "arm": {"rotation": {"0.0": [0, 0, 0], "0.5": {"pre": [10, 0, 0], "post": [90, 0, 0]}, "1.0": [0, 0, 0]}},
                    "tail": {"rotation": ["math.sin(query.anim_time * 360) * 10", 0, "q.anim_time > 0.5 ? 5 : -5"],
                             "scale": 2},
                    "body": {"position": {"0.0": [0, 0, 0], "0.5": {"post": [0, 4, 0], "lerp_mode": "catmullrom"},
                                          "1.0": [0, 0, 0]}}
                  }},
                  "animation.test.death": {"loop": "hold_on_last_frame", "bones": {
                    "head": {"position": {"0.0": [0, 0, 0], "2.0": [0, -8, 0]}}}},
                  "animation.test.odd": {"bones": {"x": {"rotation": ["variable.foo + math.nope(1)", "v.bar", 0]}}}
                }}
                """, animNotes);
        Liturgy.Scene scene = new Liturgy.Scene(0.125, 0, 0, 0, 0, 0);
        Liturgy.Rite walkRite = rites.get("animation.test.walk");
        double[] leg = Liturgy.sample(walkRite.bones().get("leg").rotation(), 0.25, scene);
        check("animation: linear keyframes", walkRite.kind().equals("walk") && Math.abs(leg[0]) < 1e-9
                && Math.abs(Liturgy.sample(walkRite.bones().get("leg").rotation(), 0.75, scene)[0]) < 1e-9);
        check("animation: pre and post jump at a keyframe",
                Math.abs(Liturgy.sample(walkRite.bones().get("arm").rotation(), 0.4999, scene)[0] - 10) < 0.01
                && Math.abs(Liturgy.sample(walkRite.bones().get("arm").rotation(), 0.5, scene)[0] - 90) < 1e-9);
        double[] tail = Liturgy.sample(walkRite.bones().get("tail").rotation(), 0.125, scene);
        check("animation: Molang, in degrees", Math.abs(tail[0] - Math.sin(Math.toRadians(45)) * 10) < 1e-9 && tail[2] == -5);
        check("animation: one number scales all three",
                java.util.Arrays.equals(Liturgy.sample(walkRite.bones().get("tail").scale(), 0, scene), new double[]{2, 2, 2}));
        double curve = Liturgy.sample(walkRite.bones().get("body").position(), 0.25, scene)[1];
        check("animation: catmullrom curves (not the straight line's 2.0)", curve > 2.0 && curve < 4.0);
        Liturgy.Rite deathRite = rites.get("animation.test.death");
        check("animation: length from the last keyframe, held at the end", deathRite.length() == 2.0
                && deathRite.at(5) == 2.0 && walkRite.at(1.25) == 0.25);
        check("animation: unknown Molang is 0, and said", animNotes.size() == 1 && animNotes.getFirst().contains("math.nope"));

        // Molang with variables and statements, custom queries.
        Liturgy.Lexicon lex = new Liturgy.Lexicon(List.of("is_angry"));
        List<String> mNotes = new ArrayList<>();
        Liturgy.Molang counter = Liturgy.Molang.of("v.count = v.count + 1; t.twice = v.count * 2; return t.twice + q.is_angry;",
                "test", mNotes, lex);
        Liturgy.Molang readCount = Liturgy.Molang.of("variable.count", "test", mNotes, lex);
        Liturgy.Scene ms = new Liturgy.Scene();
        ms.custom = new double[]{10};
        double first = counter.eval(ms);
        double second = counter.eval(ms);
        check("molang: statements, variables kept, return", first == 12 && second == 14 && readCount.eval(ms) == 2 && mNotes.isEmpty());
        check("molang: a lone assignment is worth its value", Liturgy.Molang.of("v.x = 3", "test", mNotes, lex).eval(ms) == 3
                && Liturgy.Molang.of("v.x == 3 ? 7 : 0", "test", mNotes, lex).eval(ms) == 7);
        check("molang: queries a model asks about", Liturgy.Molang.of("q.is_on_ground && !query.is_in_water", "test", mNotes, lex)
                .eval(ms) == 0 && mNotes.isEmpty());

        // Animation controllers: states, transitions, blending, on_entry, rites from code.
        List<String> cNotes = new ArrayList<>();
        Liturgy.Lexicon clex = new Liturgy.Lexicon(List.of("is_angry"));
        java.util.Map<String, Liturgy.Rite> crites = Liturgy.read("""
                {"animations": {
                  "animation.t.idle": {"loop": true, "animation_length": 2, "bones": {"a": {"rotation": [1, 0, 0]}}},
                  "animation.t.walk": {"loop": true, "animation_length": 1, "bones": {"a": {"rotation": [2, 0, 0]}}},
                  "animation.t.rage": {"loop": true, "animation_length": 1, "bones": {"a": {"rotation": [3, 0, 0]}}},
                  "animation.t.roar": {"animation_length": 0.5, "bones": {"a": {"rotation": [4, 0, 0]}}}
                }}""", cNotes, clex);
        List<Choir.Controller> choir = Choir.read("""
                {"format_version": "1.10.0", "animation_controllers": {
                  "controller.animation.t.move": {"initial_state": "default", "states": {
                    "default": {"animations": ["idle"], "transitions": [{"walking": "q.is_moving"}], "blend_transition": 0.2},
                    "walking": {"animations": [{"walk": "q.ground_speed"}], "transitions": [{"default": "!q.is_moving"}],
                                "blend_transition": 0.2, "on_entry": ["v.steps = v.steps + 1;"]}}},
                  "controller.animation.t.mood": {"states": {
                    "default": {"transitions": [{"angry": "q.is_angry"}]},
                    "angry": {"animations": ["animation.t.rage"], "transitions": [{"default": "!q.is_angry"}],
                              "sound_effects": [{"effect": "grr"}]}}}
                }}""", cNotes, clex);
        Choir.Soul soul = new Choir.Soul(choir.size());
        soul.scene.custom = new double[1];
        List<Choir.Voice> v0 = Choir.step(choir, crites, soul, 1.0, cNotes);
        check("controller: starts in its initial state", soul.state(0).equals("default") && soul.state(1).equals("default")
                && v0.size() == 1 && v0.getFirst().rite().kind().equals("idle") && v0.getFirst().weight() == 1);
        soul.scene.moving = 1;
        soul.scene.groundSpeed = 0.5;
        List<Choir.Voice> v1 = Choir.step(choir, crites, soul, 1.1, cNotes);
        check("controller: moves on a condition, runs on_entry", soul.state(0).equals("walking")
                && Liturgy.Molang.of("v.steps", "t", cNotes, clex).eval(soul.scene) == 1);
        check("controller: crossfades at the move (new at 0, old at 1) with weights",
                v1.stream().anyMatch(v -> v.rite().kind().equals("walk") && v.weight() == 0) == false
                && v1.stream().anyMatch(v -> v.rite().kind().equals("idle") && v.weight() == 1));
        List<Choir.Voice> v2 = Choir.step(choir, crites, soul, 1.2, cNotes);
        double walkW = v2.stream().filter(v -> v.rite().kind().equals("walk")).mapToDouble(Choir.Voice::weight).sum();
        double idleW = v2.stream().filter(v -> v.rite().kind().equals("idle")).mapToDouble(Choir.Voice::weight).sum();
        check("controller: halfway through the blend", Math.abs(walkW - 0.25) < 1e-9 && Math.abs(idleW - 0.5) < 1e-9);
        List<Choir.Voice> v3 = Choir.step(choir, crites, soul, 2.0, cNotes);
        check("controller: blend done, walk only, its time from entry", v3.size() == 1
                && Math.abs(v3.getFirst().seconds() - 0.9) < 1e-9 && v3.getFirst().weight() == 0.5);
        soul.scene.custom[0] = 1;
        Choir.step(choir, crites, soul, 2.1, cNotes);
        check("controller: a mod's own query moves another controller", soul.state(1).equals("angry"));
        soul.play(Choir.find(crites, "roar"), 2.1);
        List<Choir.Voice> v4 = Choir.step(choir, crites, soul, 2.3, cNotes);
        check("rites: played on top, from when they were asked", v4.stream().anyMatch(v -> v.rite().kind().equals("roar")
                && Math.abs(v.seconds() - 0.2) < 1e-9) && v4.stream().anyMatch(v -> v.rite().kind().equals("rage")));
        Choir.step(choir, crites, soul, 2.7, cNotes);
        check("rites: a one-shot ends by itself", !soul.playing(Choir.find(crites, "roar")));
        soul.play(Choir.find(crites, "rage"), 3);
        soul.stop(Choir.find(crites, "rage"));
        check("rites: stop", !soul.playing(Choir.find(crites, "rage")));
        check("controller: what it can't do is said", cNotes.size() == 1 && cNotes.getFirst().contains("sound_effects"));
        check("controller: names by last part or in full", Choir.find(crites, "animation.t.walk") == Choir.find(crites, "WALK"));

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed != 0) {
            // an uncaught throw ends the JVM with 1; no System.exit, so the jar's label stays honest
            throw new AssertionError(failed + " check(s) failed");
        }
    }

    /** "id version [bound]" per mod. */
    private static Communion.Manifest manifest(long hash, int creations, String... mods) {
        List<Communion.Manifest.Entry> entries = new ArrayList<>();
        for (String m : mods) {
            String[] p = m.split(" ");
            String since = p.length > 3 && p[3].startsWith("since=") ? p[3].substring("since=".length()) : null;
            entries.add(new Communion.Manifest.Entry(p[0], p[1], p.length > 2 && p[2].equals("bound"), since));
        }
        return new Communion.Manifest("0.3.0", entries, hash, creations);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private static boolean throwsWith(Runnable r, String needle) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(needle);
        }
    }
}
