package com.github.tvbox.osc.player;

import xyz.doikki.videoplayer.player.VideoView;

/** Keeps loading watchdog refreshes tied to real player buffer progress. */
public final class PlaybackBufferProgressPolicy {
    private PlaybackBufferProgressPolicy() {
    }

    public static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    public static boolean isLoadingState(int playState) {
        return playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_BUFFERING;
    }

    public static boolean shouldShowDetailLoadingOverlay(int playState,
                                                         boolean renderedFirstFrame,
                                                         boolean fullScreen) {
        return !renderedFirstFrame && !fullScreen && isLoadingState(playState);
    }

    public static boolean hasForwardProgress(int previousHighWaterPercent,
                                             int currentPercent) {
        return previousHighWaterPercent < 0
                || clampPercent(currentPercent) > previousHighWaterPercent;
    }

    public static int updateHighWater(int previousHighWaterPercent, int currentPercent) {
        return Math.max(previousHighWaterPercent, clampPercent(currentPercent));
    }

    public static boolean shouldRefreshTimeout(int playState,
                                               int previousHighWaterPercent,
                                               int currentPercent,
                                               long elapsedSinceRefreshMs,
                                               long minimumRefreshIntervalMs) {
        return isLoadingState(playState)
                && hasForwardProgress(previousHighWaterPercent, currentPercent)
                && elapsedSinceRefreshMs >= Math.max(0L, minimumRefreshIntervalMs);
    }
}
