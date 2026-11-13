package com.github.tvbox.osc.player;

import java.io.IOException;

/** Coalesces the EBML parser's one-byte reads without keeping another video window alive. */
final class RangeReadBuffer {
    interface Reader {
        int read(long position, byte[] buffer, int offset, int length) throws IOException;
    }

    // Stay within the small metadata-read classification as well as a tiny memory budget.
    static final int CAPACITY = 16 * 1024;
    private final byte[] data = new byte[CAPACITY];
    private long start = -1L;
    private int size;

    void reset() {
        start = -1L;
        size = 0;
    }

    int read(Reader reader, long position, byte[] target, int offset, int length,
             long remaining) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remaining == 0L) {
            return -1;
        }
        if (start >= 0L && position >= start && position - start < size) {
            int index = (int) (position - start);
            int count = Math.min(length, size - index);
            System.arraycopy(data, index, target, offset, count);
            return count;
        }
        reset();
        if (length >= CAPACITY) {
            return reader.read(position, target, offset, length);
        }
        int requested = remaining < 0L ? CAPACITY
                : (int) Math.min((long) CAPACITY, remaining);
        int count = reader.read(position, data, 0, requested);
        if (count <= 0) {
            return count;
        }
        start = position;
        size = count;
        int copied = Math.min(length, count);
        System.arraycopy(data, 0, target, offset, copied);
        return copied;
    }
}
