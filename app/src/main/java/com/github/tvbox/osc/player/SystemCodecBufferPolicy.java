package com.github.tvbox.osc.player;

final class SystemCodecBufferPolicy {
    // Trust ExoPlayer's proven defaults. On a memory-constrained TV (this device has ~1.5 GB
    // total) a very high-bitrate REMUX cannot be buffered for a large time window without OOM /
    // GC stalls, and an oversized forward buffer also competes for bandwidth with the data the
    // playhead actually needs next — which made playback worse, not smoother. These are the
    // stock DefaultLoadControl durations.
    static final int MIN_BUFFER_MS = 50_000;
    static final int MAX_BUFFER_MS = 50_000;
    // A high-bitrate network-disk REMUX needs meaningful runway before both startup and resume.
    // ExoPlayer may still start earlier when its track-sized byte target is full, which protects
    // the 192 MB app heap from an unbounded time-only allocation.
    static final int PLAYBACK_BUFFER_MS = 15_000;
    static final int REBUFFER_MS = 15_000;
    static final int BACK_BUFFER_MS = 0;
    private static final int MB = 1024 * 1024;

    private SystemCodecBufferPolicy() {
    }

    /**
     * Return {@link com.google.android.exoplayer2.C#LENGTH_UNSET} so DefaultLoadControl sizes the
     * byte buffer from the selected tracks instead of a fixed, memory-heavy ceiling. Forcing a
     * large fixed byte target on this device caused GC pressure and starved the playhead.
     */
    static int targetBufferBytes(int memoryClassMb) {
        return com.google.android.exoplayer2.C.LENGTH_UNSET;
    }

    static int bufferProgressTargetBytes(int memoryClassMb) {
        int safeMemoryClassMb = Math.max(64, memoryClassMb);
        int targetMb = Math.max(16, Math.min(64, safeMemoryClassMb / 3));
        return targetMb * MB;
    }

    static int bufferingProgressPercent(long bufferedDurationMs,
                                        long requiredBufferMs,
                                        long allocatedBytes,
                                        long targetBytes,
                                        boolean ready) {
        if (ready) {
            return 100;
        }
        long timePercent = requiredBufferMs <= 0L
                ? 0L : (Math.max(0L, bufferedDurationMs) * 100L) / requiredBufferMs;
        long bytePercent = targetBytes <= 0L
                ? 0L : (Math.max(0L, allocatedBytes) * 100L) / targetBytes;
        return (int) Math.max(0L, Math.min(99L, Math.max(timePercent, bytePercent)));
    }
}
