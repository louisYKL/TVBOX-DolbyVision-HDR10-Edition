package xyz.doikki.videoplayer.player;

final class PlaybackCompletionPolicy {
    static final int MAX_POST_SEEK_RECOVERY_ATTEMPTS = 2;
    private static final long POST_SEEK_FALSE_COMPLETION_WINDOW_MS = 5_000L;
    private static final long ZERO_POSITION_TOLERANCE_MS = 1_000L;
    private static final long END_POSITION_GUARD_MS = 5_000L;

    private PlaybackCompletionPolicy() {
    }

    static boolean isExpectedCompletion(boolean rendered,
                                        boolean audioOnly,
                                        boolean seekInFlight,
                                        boolean buffering,
                                        long positionMs,
                                        long durationMs) {
        if ((!rendered && !audioOnly) || seekInFlight || buffering
                || positionMs < 0L || durationMs <= 0L) {
            return false;
        }
        long toleranceMs = Math.max(5_000L, Math.min(30_000L, durationMs / 100L));
        return positionMs >= Math.max(0L, durationMs - toleranceMs);
    }

    static boolean isLikelyPostSeekFalseCompletion(boolean playbackRequested,
                                                    int completedSeekTargetMs,
                                                    long completedSeekAtMs,
                                                    long callbackAtMs,
                                                    long positionMs,
                                                    long durationMs) {
        if (!playbackRequested
                || completedSeekTargetMs <= 0
                || completedSeekAtMs <= 0L
                || callbackAtMs < completedSeekAtMs
                || callbackAtMs - completedSeekAtMs > POST_SEEK_FALSE_COMPLETION_WINDOW_MS
                || positionMs < 0L
                || durationMs <= 0L) {
            return false;
        }
        return positionMs <= ZERO_POSITION_TOLERANCE_MS
                && completedSeekTargetMs < Math.max(0L, durationMs - END_POSITION_GUARD_MS);
    }

    static boolean canAttemptPostSeekRecovery(int completedAttempts) {
        return completedAttempts >= 0
                && completedAttempts < MAX_POST_SEEK_RECOVERY_ATTEMPTS;
    }

    static int resolvePostSeekRecoveryTarget(int requestedTargetMs, int completedAttempts) {
        long rewindMs = completedAttempts <= 0 ? 2_000L : 5_000L;
        long target = Math.max(1L, (long) requestedTargetMs - rewindMs);
        return (int) Math.min(Integer.MAX_VALUE, target);
    }

    static int resolveRecoveryTarget(int queuedTarget,
                                     int activeTarget,
                                     int lastRequestedTarget,
                                     long positionMs,
                                     long durationMs) {
        long target = queuedTarget >= 0 ? queuedTarget
                : activeTarget >= 0 ? activeTarget
                : lastRequestedTarget >= 0 ? lastRequestedTarget
                : Math.max(0L, positionMs);
        if (durationMs > 0L) {
            target = Math.min(target, Math.max(0L, durationMs - 1_000L));
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, target));
    }
}
