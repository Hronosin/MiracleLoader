package io.github.hronosin.miracle.toolchain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One message for {@link Telepathy}: a few values, written in order, read back in the same
 * order on the other side. Every value carries its type, so reading the wrong thing fails with a
 * message that says what was there instead, and {@link #toString()} shows the whole scroll.
 *
 * <pre>{@code
 * Scroll out = new Scroll().writeString("amen").writeInt(3);
 * // on the other side:
 * String word = in.readString();
 * int times = in.readInt();
 * }</pre>
 *
 * Values: int, long, double, boolean, string, bytes. Strings are UTF-8. A scroll is written by
 * one side and read by the other; don't share one between threads.
 */
public final class Scroll {

    private static final byte INT = 'I';
    private static final byte LONG = 'L';
    private static final byte DOUBLE = 'D';
    private static final byte BOOL = 'B';
    private static final byte STRING = 'S';
    private static final byte BYTES = 'Y';

    private final ByteArrayOutputStream out;
    private final byte[] in;
    private int pos;

    /** A blank scroll, to write on. */
    public Scroll() {
        this.out = new ByteArrayOutputStream();
        this.in = null;
    }

    private Scroll(byte[] data) {
        this.out = null;
        this.in = data;
    }

    static Scroll read(byte[] data) {
        return new Scroll(data);
    }

    // --- writing ------------------------------------------------------------------------------

    public Scroll writeInt(int v) {
        tag(INT);
        varLong(zigzag(v));
        return this;
    }

    public Scroll writeLong(long v) {
        tag(LONG);
        varLong(zigzag(v));
        return this;
    }

    public Scroll writeDouble(double v) {
        tag(DOUBLE);
        long bits = Double.doubleToRawLongBits(v);
        for (int i = 7; i >= 0; i--) {
            out.write((int) (bits >>> (i * 8)));
        }
        return this;
    }

    public Scroll writeBoolean(boolean v) {
        tag(BOOL);
        out.write(v ? 1 : 0);
        return this;
    }

    public Scroll writeString(String v) {
        tag(STRING);
        byte[] b = v.getBytes(StandardCharsets.UTF_8);
        varLong(b.length);
        out.writeBytes(b);
        return this;
    }

    public Scroll writeBytes(byte[] v) {
        tag(BYTES);
        varLong(v.length);
        out.writeBytes(v);
        return this;
    }

    // --- reading ------------------------------------------------------------------------------

    public int readInt() {
        expect(INT, "an int");
        long v = unzigzag(readVarLong());
        return (int) v;
    }

    public long readLong() {
        expect(LONG, "a long");
        return unzigzag(readVarLong());
    }

    public double readDouble() {
        expect(DOUBLE, "a double");
        need(8);
        long bits = ByteBuffer.wrap(in, pos, 8).getLong();
        pos += 8;
        return Double.longBitsToDouble(bits);
    }

    public boolean readBoolean() {
        expect(BOOL, "a boolean");
        need(1);
        return in[pos++] != 0;
    }

    public String readString() {
        expect(STRING, "a string");
        return new String(chunk(), StandardCharsets.UTF_8);
    }

    public byte[] readBytes() {
        expect(BYTES, "bytes");
        return chunk();
    }

    /** True while there's something left to read. */
    public boolean hasMore() {
        requireReading();
        return pos < in.length;
    }

    /** What's on the scroll, for logs and debugging: {@code [string "amen", int 3]}. */
    @Override
    public String toString() {
        Scroll copy = new Scroll(bytes());
        List<String> parts = new ArrayList<>();
        try {
            while (copy.hasMore()) {
                parts.add(switch (copy.in[copy.pos]) {
                    case INT -> "int " + copy.readInt();
                    case LONG -> "long " + copy.readLong();
                    case DOUBLE -> "double " + copy.readDouble();
                    case BOOL -> "boolean " + copy.readBoolean();
                    case STRING -> "string \"" + copy.readString() + "\"";
                    case BYTES -> copy.readBytes().length + " bytes";
                    default -> throw new IllegalStateException("unknown");
                });
            }
        } catch (RuntimeException e) {
            parts.add("<unreadable from here>");
        }
        return "[" + String.join(", ", parts) + "]";
    }

    // --- internals ----------------------------------------------------------------------------

    byte[] bytes() {
        return out != null ? out.toByteArray() : in.clone();
    }

    int size() {
        return out != null ? out.size() : in.length;
    }

    private void tag(byte t) {
        if (out == null) {
            throw new IllegalStateException("This scroll came from the other side; it's for reading. Write a new Scroll().");
        }
        out.write(t);
    }

    private void expect(byte t, String what) {
        requireReading();
        if (pos >= in.length) {
            throw new IllegalStateException("Expected " + what + ", but the scroll ends here: " + this);
        }
        if (in[pos] != t) {
            throw new IllegalStateException("Expected " + what + " at this point of the scroll, found "
                    + describe(in[pos]) + ". Read in the order it was written: " + this);
        }
        pos++;
    }

    private void requireReading() {
        if (in == null) {
            throw new IllegalStateException("This scroll is still being written. It can be read on the other side.");
        }
    }

    private static String describe(byte t) {
        return switch (t) {
            case INT -> "an int";
            case LONG -> "a long";
            case DOUBLE -> "a double";
            case BOOL -> "a boolean";
            case STRING -> "a string";
            case BYTES -> "bytes";
            default -> "garbage (" + t + ")";
        };
    }

    private byte[] chunk() {
        long n = readVarLong();
        if (n < 0 || n > in.length - pos) {
            throw new IllegalStateException("A value claims " + n + " bytes, but only " + (in.length - pos) + " are left");
        }
        byte[] b = new byte[(int) n];
        System.arraycopy(in, pos, b, 0, b.length);
        pos += b.length;
        return b;
    }

    private void need(int n) {
        if (in.length - pos < n) {
            throw new IllegalStateException("The scroll ends in the middle of a value");
        }
    }

    private void varLong(long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private long readVarLong() {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            need(1);
            byte b = in[pos++];
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IllegalStateException("A number on the scroll never ends");
    }

    private static long zigzag(long v) {
        return (v << 1) ^ (v >> 63);
    }

    private static long unzigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }
}
