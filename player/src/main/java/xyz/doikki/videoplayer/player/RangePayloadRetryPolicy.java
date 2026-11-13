package xyz.doikki.videoplayer.player;

final class RangePayloadRetryPolicy {
    private static final long BASE_DELAY_MS = 250L;
    private static final long MAX_DELAY_MS = 1_000L;

    private RangePayloadRetryPolicy() {
    }

    static boolean shouldRetry(int completedAttempts, long elapsedMs, boolean prefetch) {
        // A short Range body is not EOF when the source advertised more bytes. Keep reopening
        // that exact range until it completes or the owning read is cancelled.
        return completedAttempts > 0;
    }

    static long retryDelayMs(int completedAttempts) {
        long safeAttempts = Math.max(1, completedAttempts);
        return Math.min(MAX_DELAY_MS, BASE_DELAY_MS * safeAttempts);
    }
}
