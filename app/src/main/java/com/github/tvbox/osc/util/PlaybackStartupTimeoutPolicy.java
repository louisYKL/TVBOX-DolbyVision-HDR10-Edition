package com.github.tvbox.osc.util;

/** Startup timeout policy kept separate from post-start buffer-stall recovery. */
public final class PlaybackStartupTimeoutPolicy {
    static final long RESUMED_PLAYBACK_STARTUP_TIMEOUT_MS = 90_000L;

    private PlaybackStartupTimeoutPolicy() {
    }

    public static long resolveInitialTimeout(long defaultTimeoutMs, boolean hasSavedProgress) {
        if (!hasSavedProgress) {
            return defaultTimeoutMs;
        }
        // Never shorten the 120s system-proxy allowance, but give a resume seek enough
        // time to establish its range/cache window before watchdog recovery intervenes.
        return Math.max(defaultTimeoutMs, RESUMED_PLAYBACK_STARTUP_TIMEOUT_MS);
    }
}
