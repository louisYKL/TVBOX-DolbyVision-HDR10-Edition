package xyz.doikki.videoplayer.player;

/** Bounds full source rebuilds so an invalid native extractor is replaced without a loop. */
final class SourceRebuildPolicy {
    static final int MAX_REBUILD_ATTEMPTS = 1;
    private static final long STABLE_ADVANCE_MS = 1_000L;

    private SourceRebuildPolicy() {
    }

    static boolean shouldRebuild(boolean sourceAvailable,
                                 boolean currentPlayerAvailable,
                                 int rebuildAttempts,
                                 long resumePosition) {
        return sourceAvailable
                && currentPlayerAvailable
                && rebuildAttempts < MAX_REBUILD_ATTEMPTS
                && resumePosition >= 0L;
    }

    static boolean hasAdvancedBeyondResume(long resumePosition, long currentPosition) {
        return currentPosition > resumePosition
                && currentPosition - resumePosition > STABLE_ADVANCE_MS;
    }
}
