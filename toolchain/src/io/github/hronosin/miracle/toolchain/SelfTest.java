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
        check("geometry: rotations flip x and y, keep z", Math.abs(zArm.xRot() - (float) Math.toRadians(90)) < 1e-5);
        Clay.Part horn = zHead.children().getFirst();
        check("geometry: a rotated cube becomes a part of its own", horn.boxes().isEmpty() && horn.children().size() == 1
                && horn.children().getFirst().name().equals("horn_r1")
                && Math.abs(horn.children().getFirst().zRot() - (float) Math.toRadians(30)) < 1e-5
                && horn.children().getFirst().boxes().getFirst().y() == -4);
        check("geometry: not a geometry file", throwsWith(() -> Clay.read("{\"hello\": 1}"), "no \"minecraft:geometry\""));
        check("geometry: broken JSON names the line", throwsWith(() -> Clay.read("{\n\"a\": [1, 2,\n}"), "line 3"));

        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** "id version [bound]" per mod. */
    private static Communion.Manifest manifest(long hash, int creations, String... mods) {
        List<Communion.Manifest.Entry> entries = new ArrayList<>();
        for (String m : mods) {
            String[] p = m.split(" ");
            entries.add(new Communion.Manifest.Entry(p[0], p[1], p.length > 2));
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
