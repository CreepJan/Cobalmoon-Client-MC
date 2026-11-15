/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.input;

/**
 * Clicks per second over a sliding one-second window. Fixed-size ring buffer: no allocation per
 * click, O(1) record, O(n) query with n <= capacity.
 */
public final class ClickTracker {
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private final long[] times;
    private int head;
    private int size;

    public ClickTracker(int capacity) {
        times = new long[capacity];
    }

    public synchronized void record(long nanos) {
        times[head] = nanos;
        head = (head + 1) % times.length;
        if (size < times.length) size++;
    }

    /** Clicks within the last second before {@code nowNanos}. */
    public synchronized int cps(long nowNanos) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            long t = times[Math.floorMod(head - 1 - i, times.length)];
            if (nowNanos - t <= WINDOW_NANOS && t <= nowNanos) count++;
            else if (nowNanos - t > WINDOW_NANOS) break; // older entries are older still
        }
        return count;
    }

    public int cps() {
        return cps(System.nanoTime());
    }

    public synchronized void clear() {
        size = 0;
        head = 0;
    }
}
