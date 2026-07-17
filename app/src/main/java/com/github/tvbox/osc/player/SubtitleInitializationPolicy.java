package com.github.tvbox.osc.player;

/** Keeps native track inspection event-driven and bounded. */
public final class SubtitleInitializationPolicy {
    private SubtitleInitializationPolicy() {
    }

    public static boolean shouldDeferUntilPlaybackReady(boolean playbackReady) {
        return !playbackReady;
    }

    public static boolean shouldRetryNativeTrackDiscovery(int subtitleCount, int attempt) {
        return subtitleCount <= 0 && Math.max(0, attempt) < 1;
    }

    public static int nextDiscoveryAttempt(int currentAttempt) {
        int safeAttempt = Math.max(0, currentAttempt);
        return safeAttempt == Integer.MAX_VALUE ? safeAttempt : safeAttempt + 1;
    }
}
