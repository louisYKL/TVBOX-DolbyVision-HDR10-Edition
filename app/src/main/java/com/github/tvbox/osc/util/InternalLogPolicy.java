package com.github.tvbox.osc.util;

/** Bounds optional diagnostic storage without affecting normal playback. */
public final class InternalLogPolicy {
    public static final long MAX_TOTAL_BYTES = 1024L * 1024L;
    public static final long MAX_FILE_BYTES = MAX_TOTAL_BYTES / 2L;

    private InternalLogPolicy() {
    }

    public static boolean shouldRotate(long currentBytes, long incomingBytes) {
        return currentBytes > 0L && incomingBytes > 0L
                && currentBytes + incomingBytes > MAX_FILE_BYTES;
    }

    public static boolean fitsInGeneration(long currentBytes, long incomingBytes) {
        return currentBytes >= 0L && incomingBytes >= 0L
                && currentBytes <= MAX_FILE_BYTES
                && incomingBytes <= MAX_FILE_BYTES - currentBytes;
    }
}
