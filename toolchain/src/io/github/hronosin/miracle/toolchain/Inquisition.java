package io.github.hronosin.miracle.toolchain;

import java.util.ArrayList;
import java.util.List;

/**
 * What the server remembers about one client's Telepathy batches, and what it makes of the next
 * one. An honest client numbers its batches 1, 2, 3... over one connection (TCP keeps them in
 * order) and sends a few per tick at most. Anything else didn't come from our client code:
 * replayed, reordered, forged, or flooded.
 *
 * <p>Pure bookkeeping, no game classes: {@link SelfTest} checks it without Minecraft.
 */
final class Inquisition {

    private long lastSeq;
    private int tick = Integer.MIN_VALUE;
    private int batchesThisTick;

    /** The sins in this batch, if any. Updates the record either way. */
    List<String> examine(long seq, int serverTick, int maxPerTick) {
        List<String> sins = new ArrayList<>(2);
        if (seq != lastSeq + 1) {
            sins.add("sent batch #" + seq + " where #" + (lastSeq + 1) + " was due: replayed, reordered or forged");
        }
        if (seq == lastSeq + 1) {
            lastSeq = seq; // only an honest batch moves the count: a forged #99 mustn't make the real #4 look late
        }
        batchesThisTick = serverTick == tick ? batchesThisTick + 1 : 1;
        tick = serverTick;
        if (batchesThisTick > maxPerTick) {
            sins.add("sent " + batchesThisTick + " batches in one tick; an honest client sends " + maxPerTick + " at most");
        }
        return sins;
    }
}
