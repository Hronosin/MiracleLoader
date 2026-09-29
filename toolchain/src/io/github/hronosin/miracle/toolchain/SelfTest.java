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

    public static void main(String[] args) {
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

        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
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
