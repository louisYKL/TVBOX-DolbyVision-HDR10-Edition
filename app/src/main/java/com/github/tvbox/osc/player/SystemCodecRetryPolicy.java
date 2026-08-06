package com.github.tvbox.osc.player;

/**
 * Bounds retries for transient source failures during one media-source session.
 *
 * <p>The retry budget deliberately belongs to the source session, not to a rendered frame.
 * A source can render a frame and still fail a later range request; resetting the budget from
 * {@code onRenderedFirstFrame} turns a persistent 404/IO failure into an infinite prepare loop.</p>
 */
final class SystemCodecRetryPolicy {
    static final int MAX_SOURCE_RETRIES = 3;

    private SystemCodecRetryPolicy() {
    }

    static boolean isTransientSourceError(int errorCode) {
        return errorCode >= 2000 && errorCode < 3000;
    }

    /**
     * Return the next retry attempt, or {@code -1} when this error must be surfaced to the UI.
     * The returned attempt is one-based and never exceeds {@link #MAX_SOURCE_RETRIES}.
     */
    static int nextRetryAttempt(int currentRetryCount, int errorCode) {
        if (!isTransientSourceError(errorCode)
                || currentRetryCount < 0
                || currentRetryCount >= MAX_SOURCE_RETRIES) {
            return -1;
        }
        return currentRetryCount + 1;
    }
}
