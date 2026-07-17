package xyz.doikki.videoplayer.player;

final class PlaybackPositionRecoveryPolicy {
    static final long NO_POSITION = -1L;
    private static final long MIN_POSITION_ADVANCE_MS = 40L;
    private static final int REQUIRED_CONSECUTIVE_ADVANCES = 2;

    private PlaybackPositionRecoveryPolicy() {
    }

    static boolean canSample(boolean currentSession,
                             boolean nativeBuffering,
                             boolean started,
                             boolean seekInFlight,
                             boolean preparedSeekInvocationPending,
                             boolean prebufferActive,
                             boolean displayStartPending) {
        return currentSession
                && nativeBuffering
                && started
                && !seekInFlight
                && !preparedSeekInvocationPending
                && !prebufferActive
                && !displayStartPending;
    }

    static int nextAdvanceCount(long previousPosition,
                                long currentPosition,
                                int previousAdvanceCount) {
        if (previousPosition < 0L
                || currentPosition < 0L
                || currentPosition - previousPosition < MIN_POSITION_ADVANCE_MS) {
            return 0;
        }
        return Math.max(0, previousAdvanceCount) + 1;
    }

    static boolean isPlaybackAdvancing(int advanceCount) {
        return advanceCount >= REQUIRED_CONSECUTIVE_ADVANCES;
    }
}
