package io.github.hronosin.miracle.toolchain;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Telepathy's wire format. Pure bytes, no game classes, so {@link SelfTest} can check it
 * without Minecraft.
 *
 * <pre>
 * batch    = 'M' 'T' version:u8(1) seq:varint count:varint message*count
 * message  = channel:i32 (FNV-1a of its name) length:varint scroll:bytes
 * </pre>
 *
 * One batch is everything one side said during one tick (several, if it didn't fit one packet).
 * The sequence number counts batches per connection and direction, from 1.
 */
final class Litany {

    static final int VERSION = 1;

    record Message(int hash, byte[] body) {
    }

    record Batch(long seq, List<Message> messages) {
    }

    private Litany() {
    }

    /** One message, framed: its channel's hash, its length, its bytes. */
    static byte[] frame(int hash, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 9);
        out.write(hash >>> 24);
        out.write(hash >>> 16);
        out.write(hash >>> 8);
        out.write(hash);
        writeVar(out, body.length);
        out.writeBytes(body);
        return out.toByteArray();
    }

    /**
     * Framed messages into as few batches as fit in {@code max} bytes each; a single message
     * bigger than that still gets a batch of its own. {@code seq[0]} is the last number used,
     * and is advanced.
     */
    static List<byte[]> batches(List<byte[]> messages, long[] seq, int max) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i < messages.size()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int count = 0;
            while (i < messages.size() && (count == 0 || body.size() + messages.get(i).length <= max - 16)) {
                body.writeBytes(messages.get(i++));
                count++;
            }
            ByteArrayOutputStream batch = new ByteArrayOutputStream(body.size() + 16);
            batch.write('M');
            batch.write('T');
            batch.write(VERSION);
            writeVar(batch, ++seq[0]);
            writeVar(batch, count);
            batch.writeBytes(body.toByteArray());
            out.add(batch.toByteArray());
        }
        return out;
    }

    /** Reads a batch. Throws, with a reason, on anything that isn't exactly one. */
    static Batch parse(byte[] d) {
        int[] pos = {0};
        if (d.length < 3 || d[0] != 'M' || d[1] != 'T') {
            throw new IllegalStateException("not a Telepathy batch");
        }
        if (d[2] != VERSION) {
            throw new IllegalStateException("format " + d[2] + ", this side speaks " + VERSION);
        }
        pos[0] = 3;
        long seq = readVar(d, pos);
        long count = readVar(d, pos);
        if (count < 0 || count > d.length) {
            throw new IllegalStateException("claims " + count + " messages");
        }
        List<Message> ms = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            if (d.length - pos[0] < 4) {
                throw new IllegalStateException("cut short");
            }
            int hash = ((d[pos[0]] & 0xFF) << 24) | ((d[pos[0] + 1] & 0xFF) << 16)
                    | ((d[pos[0] + 2] & 0xFF) << 8) | (d[pos[0] + 3] & 0xFF);
            pos[0] += 4;
            long len = readVar(d, pos);
            if (len < 0 || len > d.length - pos[0]) {
                throw new IllegalStateException("a message claims " + len + " bytes");
            }
            byte[] body = new byte[(int) len];
            System.arraycopy(d, pos[0], body, 0, body.length);
            pos[0] += body.length;
            ms.add(new Message(hash, body));
        }
        if (pos[0] != d.length) {
            throw new IllegalStateException((d.length - pos[0]) + " stray bytes at the end");
        }
        return new Batch(seq, ms);
    }

    private static void writeVar(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long readVar(byte[] d, int[] pos) {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= d.length) {
                throw new IllegalStateException("cut short");
            }
            byte b = d[pos[0]++];
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IllegalStateException("a number that never ends");
    }
}
