package xyz.doikki.videoplayer.player;

public final class PlaybackPrebufferPolicy {
    private static final long MB = 1024L * 1024L;
    static final long MAX_RANGE_CACHE_BYTES = 192L * MB;

    private PlaybackPrebufferPolicy() {
    }

    static long resolveRequiredBytes(long configuredBytes, long totalBytes, long anchorBytes) {
        if (configuredBytes <= 0L) {
            return 0L;
        }
        if (totalBytes <= 0L) {
            return configuredBytes;
        }
        long safeAnchor = Math.max(0L, Math.min(anchorBytes, totalBytes));
        return Math.min(configuredBytes, totalBytes - safeAnchor);
    }

    static long resolveRangeAwareRequiredBytes(long configuredBytes,
                                               long totalBytes,
                                               long anchorBytes,
                                               long rangeWindowBytes,
                                               long maxShortBytesPerWindow) {
        long required = resolveRequiredBytes(configuredBytes, totalBytes, anchorBytes);
        if (required <= 0L || rangeWindowBytes <= 0L || maxShortBytesPerWindow <= 0L) {
            return required;
        }
        long windows = 1L + (required - 1L) / rangeWindowBytes;
        long tolerance = windows > Long.MAX_VALUE / maxShortBytesPerWindow
                ? required : Math.min(required, windows * maxShortBytesPerWindow);
        return required - tolerance;
    }

    static long resolveAnchor(boolean prebufferActive,
                              long prebufferAnchorBytes,
                              long playbackAnchorBytes,
                              long fallbackBytes) {
        if (prebufferActive && prebufferAnchorBytes >= 0L) {
            return prebufferAnchorBytes;
        }
        if (playbackAnchorBytes >= 0L) {
            return playbackAnchorBytes;
        }
        return Math.max(0L, fallbackBytes);
    }

    static long resolveSeekAnchor(boolean seekAnchorPending,
                                  long firstReadPosition,
                                  long previousAnchorBytes) {
        return seekAnchorPending && firstReadPosition >= 0L
                ? firstReadPosition : previousAnchorBytes;
    }

    static boolean shouldAnchorInitialNonZeroRead(boolean seekAnchorPending,
                                                   boolean playbackAnchorSet,
                                                   long initialDataSourcePosition,
                                                   long readPosition) {
        return !seekAnchorPending
                && !playbackAnchorSet
                && initialDataSourcePosition > 0L
                && readPosition == initialDataSourcePosition;
    }

    static long resolveStartupBufferAnchor(boolean gateSatisfied,
                                           long startupAnchorBytes,
                                           long liveReadPositionBytes) {
        return !gateSatisfied && startupAnchorBytes >= 0L
                ? startupAnchorBytes : Math.max(0L, liveReadPositionBytes);
    }

    static boolean isStartupBufferGateSatisfied(boolean alreadySatisfied,
                                                long bufferedAheadBytes,
                                                long requiredBytes) {
        return alreadySatisfied || (requiredBytes > 0L
                && Math.max(0L, bufferedAheadBytes) >= requiredBytes);
    }

    public static long resolveCacheLimitBytes(long maxHeapBytes, boolean is32BitProcess) {
        // All APKs share a 192 MiB configured ceiling. Keep at least 128 MiB outside
        // the raw cache for sample queues, parser state, UI and allocation headroom.
        // An unavailable heap measurement keeps the conservative five-window limit.
        if (maxHeapBytes <= 0L) {
            return 120L * MB;
        }
        long windowBytes = 24L * MB;
        long availableBytes = maxHeapBytes - 128L * MB;
        long completeWindowsBytes = availableBytes / windowBytes * windowBytes;
        return Math.max(120L * MB, Math.min(MAX_RANGE_CACHE_BYTES, completeWindowsBytes));
    }

    public static long resolveStartBufferBytes(long maxHeapBytes) {
        return 96L * MB;
    }

    static boolean shouldScheduleFillAhead(boolean enabled,
                                           long cachedBytes,
                                           long cacheLimitBytes,
                                           long bufferedAheadBytes,
                                           long targetAheadBytes) {
        return enabled
                && cachedBytes < cacheLimitBytes
                && bufferedAheadBytes < targetAheadBytes;
    }

    static boolean shouldScheduleFillAhead(boolean enabled,
                                           long cachedBytes,
                                           long cacheLimitBytes,
                                           long bufferedAheadBytes,
                                           long targetAheadBytes,
                                           boolean canEvictBehindAnchor) {
        return enabled
                && (cachedBytes < cacheLimitBytes || canEvictBehindAnchor)
                && bufferedAheadBytes < targetAheadBytes;
    }

    static boolean canLoadNextWindow(long cachedBytes,
                                     long cacheLimitBytes,
                                     long nextWindowBytes,
                                     boolean canEvictBehindAnchor) {
        if (cachedBytes < 0L || cacheLimitBytes <= 0L || nextWindowBytes <= 0L) {
            return false;
        }
        if (cachedBytes > cacheLimitBytes - Math.min(cacheLimitBytes, nextWindowBytes)) {
            return canEvictBehindAnchor;
        }
        return true;
    }
}
