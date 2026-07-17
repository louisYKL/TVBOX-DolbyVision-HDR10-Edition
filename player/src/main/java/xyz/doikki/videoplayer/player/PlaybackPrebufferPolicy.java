package xyz.doikki.videoplayer.player;

final class PlaybackPrebufferPolicy {
    private PlaybackPrebufferPolicy() {
    }

    static long resolveRequiredBytes(long configuredBytes, long totalBytes, long anchorBytes) {
        if (configuredBytes <= 0L || totalBytes <= 0L) {
            return 0L;
        }
        long safeAnchor = Math.max(0L, Math.min(anchorBytes, totalBytes));
        return Math.min(configuredBytes, totalBytes - safeAnchor);
    }

    static boolean isReady(long bufferedAheadBytes, long requiredBytes) {
        return requiredBytes <= 0L || bufferedAheadBytes >= requiredBytes;
    }

    static boolean isInitialStartReady(long bufferedAheadBytes,
                                       long requiredBytes,
                                       long elapsedMs,
                                       long minimumFallbackBytes,
                                       long fallbackAfterMs) {
        if (isReady(bufferedAheadBytes, requiredBytes)) {
            return true;
        }
        long fallbackBytes = Math.min(Math.max(0L, requiredBytes),
                Math.max(0L, minimumFallbackBytes));
        return elapsedMs >= Math.max(0L, fallbackAfterMs)
                && fallbackBytes > 0L
                && bufferedAheadBytes >= fallbackBytes;
    }

    static boolean isHardStalled(long bufferedAheadBytes,
                                 long requiredBytes,
                                 long elapsedMs,
                                 long minimumFallbackBytes,
                                 long hardTimeoutMs) {
        if (isInitialStartReady(bufferedAheadBytes, requiredBytes, elapsedMs,
                minimumFallbackBytes, hardTimeoutMs)) {
            return false;
        }
        return requiredBytes > 0L && elapsedMs >= Math.max(0L, hardTimeoutMs);
    }

    static boolean hasForwardProgress(long previousBytes, long currentBytes) {
        return previousBytes >= 0L && currentBytes > previousBytes;
    }

    static boolean shouldPlayWhenReady(boolean started,
                                       boolean prepared,
                                       boolean prebufferGateActive,
                                       boolean pendingStartAfterDisplayReady,
                                       boolean seekResumePending) {
        return started
                || prepared
                || prebufferGateActive
                || pendingStartAfterDisplayReady
                || seekResumePending;
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
}
