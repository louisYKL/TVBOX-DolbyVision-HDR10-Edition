package com.github.tvbox.osc.player;

import xyz.doikki.videoplayer.player.PlaybackPrebufferPolicy;

final class SystemCodecBufferPolicy {
    // Use an actual byte runway for VOD startup and seeks; never derive it from bitrate or time.
    static final int MIN_BUFFER_MS = 15_000;
    // VOD startup is byte-gated; a duration ceiling can stop low-bitrate sources before they
    // reach the required byte runway and leave playback waiting forever.
    static final int MAX_BUFFER_MS = Integer.MAX_VALUE;
    static final int PLAYBACK_BUFFER_MS = 4_000;
    static final int REBUFFER_MS = 5_000;
    static final int BACK_BUFFER_MS = 0;
    // Four verified 24 MiB windows from the actual playback/seek position. The Range source
    // handles its narrowly verified loopback-header compatibility; ordinary HTTP is exact.
    static final int MIN_BUFFER_BYTES = (int) PlaybackPrebufferPolicy.resolveStartBufferBytes(0L);
    // Sample queues have their own cap; the shared Range policy reserves heap headroom
    // before allowing its independently bounded 192 MiB cache.
    static final int MAX_BUFFER_BYTES = 72 * 1024 * 1024;
    static final boolean PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS = false;

    private SystemCodecBufferPolicy() {
    }

    static int targetBufferBytes(int memoryClassMb) {
        // Keep the sample queues bounded independently from the raw Range cache, including
        // on devices whose large application heap permits the full 192 MiB cache.
        if (memoryClassMb <= 256) {
            return 48 * 1024 * 1024;
        }
        if (memoryClassMb <= 512) {
            return 64 * 1024 * 1024;
        }
        return MAX_BUFFER_BYTES;
    }

    static int bufferProgressTargetBytes(int memoryClassMb) {
        return MIN_BUFFER_BYTES;
    }

    static boolean hasRequiredBuffer(long newlyAllocatedBytes,
                                     long transferredBytesSinceBufferingStarted,
                                     long requiredBytes) {
        return Math.max(Math.max(0L, newlyAllocatedBytes),
                Math.max(0L, transferredBytesSinceBufferingStarted))
                >= Math.max(0L, requiredBytes);
    }

    static int bufferingProgressPercent(long bufferedDurationMs,
                                        long requiredBufferMs,
                                        long allocatedBytes,
                                        long targetBytes,
                                        boolean ready) {
        if (ready) {
            return 100;
        }
        if (targetBytes > 0L) {
            // A byte-backed source has an exact, contiguous payload measurement. Let the
            // display reach 100% as soon as that payload target is committed; Exo's sample
            // queue may still be decoding it, so `ready` remains a separate output gate.
            long bytePercent = (Math.max(0L, allocatedBytes) * 100L) / targetBytes;
            return (int) Math.max(0L, Math.min(100L, bytePercent));
        }
        // Live/time-only sources do not have a verified byte target. Preserve their historical
        // convention: 99% means the time threshold is reached but Exo has not reported READY.
        if (requiredBufferMs <= 0L) {
            return 0;
        }
        long timePercent = (Math.max(0L, bufferedDurationMs) * 100L) / requiredBufferMs;
        return (int) Math.max(0L, Math.min(99L, timePercent));
    }

    /**
     * Returns whether a seek can reuse the current sample/Range episode.
     *
     * <p>A seek is retained only when ExoPlayer is already READY, the source's verified byte
     * gate is latched, and the target is strictly inside the current forward buffered interval.
     * A boundary target is deliberately excluded: it can require a new Range window even when
     * the reported buffered position is exact.</p>
     */
    static boolean shouldRetainForwardSeek(boolean sourceGateSatisfied,
                                           boolean playerReady,
                                           long currentPositionMs,
                                           long targetPositionMs,
                                           long bufferedPositionMs) {
        return sourceGateSatisfied
                && playerReady
                && currentPositionMs >= 0L
                && targetPositionMs > currentPositionMs
                && bufferedPositionMs > targetPositionMs;
    }
}
