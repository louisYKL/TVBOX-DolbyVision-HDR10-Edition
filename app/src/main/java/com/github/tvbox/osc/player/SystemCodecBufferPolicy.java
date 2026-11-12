package com.github.tvbox.osc.player;

final class SystemCodecBufferPolicy {
    // Bound sample-queue memory on low-memory TVs. Large media stays range-streamed; its file
    // size must not turn into an equally large in-memory buffer.
    static final int MIN_BUFFER_MS = 15_000;
    static final int MAX_BUFFER_MS = 30_000;
    static final int PLAYBACK_BUFFER_MS = 4_000;
    static final int REBUFFER_MS = 5_000;
    static final int BACK_BUFFER_MS = 0;
    // A byte ceiling takes precedence over time for high-bitrate files on a 2 GB device.
    static final boolean PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS = false;
    private static final int MB = 1024 * 1024;

    private SystemCodecBufferPolicy() {
    }

    static int targetBufferBytes(int memoryClassMb) {
        int safeMemoryClassMb = Math.max(96, memoryClassMb);
        int targetMb = Math.max(16, Math.min(32, safeMemoryClassMb / 8));
        return targetMb * MB;
    }

    static int bufferProgressTargetBytes(int memoryClassMb) {
        return targetBufferBytes(memoryClassMb);
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
