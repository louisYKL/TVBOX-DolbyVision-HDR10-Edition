package xyz.doikki.videoplayer.player;

final class PlaybackProgressPolicy {
    static final long SKIP = -1L;

    private PlaybackProgressPolicy() {
    }

    static long resolve(boolean playbackCompleted,
                        boolean stateAllowsPersistence,
                        boolean seekInFlight,
                        long pendingSeekPosition,
                        boolean positionQueryUnstable,
                        long confirmedSeekPosition,
                        long cachedPosition) {
        if (playbackCompleted) {
            return 0L;
        }
        if (!stateAllowsPersistence) {
            return SKIP;
        }
        if (seekInFlight) {
            return pendingSeekPosition >= 0L ? pendingSeekPosition : SKIP;
        }
        // A completed seek to 00:00 is an intentional resume point and must clear any
        // previously saved position. Ordinary startup at zero still falls through to SKIP.
        if (confirmedSeekPosition >= 0L) {
            return confirmedSeekPosition;
        }
        long cached = Math.max(0L, cachedPosition);
        if (positionQueryUnstable) {
            return cached > 0L ? cached : SKIP;
        }
        return cached > 0L ? cached : SKIP;
    }
}
