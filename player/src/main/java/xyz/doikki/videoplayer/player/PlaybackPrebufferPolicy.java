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
