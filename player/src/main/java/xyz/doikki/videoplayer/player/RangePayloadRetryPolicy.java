package xyz.doikki.videoplayer.player;

final class RangePayloadRetryPolicy {
    private static final int FOREGROUND_MAX_ATTEMPTS = 8;
    private static final int PREFETCH_MAX_ATTEMPTS = 6;
    private static final long FOREGROUND_MAX_ELAPSED_MS = 20_000L;
    private static final long PREFETCH_MAX_ELAPSED_MS = 8_000L;
    private static final long BASE_DELAY_MS = 250L;
    private static final long MAX_DELAY_MS = 1_000L;

    private RangePayloadRetryPolicy() {
    }

    static boolean shouldRetry(int completedAttempts, long elapsedMs, boolean prefetch) {
        int maxAttempts = prefetch ? PREFETCH_MAX_ATTEMPTS : FOREGROUND_MAX_ATTEMPTS;
        long maxElapsedMs = prefetch ? PREFETCH_MAX_ELAPSED_MS : FOREGROUND_MAX_ELAPSED_MS;
        return completedAttempts < maxAttempts && Math.max(0L, elapsedMs) < maxElapsedMs;
    }

    static long retryDelayMs(int completedAttempts) {
        long safeAttempts = Math.max(1, completedAttempts);
        return Math.min(MAX_DELAY_MS, BASE_DELAY_MS * safeAttempts);
    }
}
