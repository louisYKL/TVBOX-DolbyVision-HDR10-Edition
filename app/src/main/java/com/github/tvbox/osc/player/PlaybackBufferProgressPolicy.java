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
        // Show the "正在加载/缓冲 X%" overlay whenever the player is actually preparing or
        // buffering, regardless of whether a first frame was already rendered or whether we are
        // fullscreen. Previously this returned false once the first frame had rendered (and in
        // fullscreen), so a mid-playback rebuffer that freezes on the first decoded frame looked
        // like an unexplained long black screen with no indication that it was still buffering.
        return isLoadingState(playState);
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

    /**
     * The player only stays in STATE_BUFFERING while the native player still reports active
     * buffering (see AndroidMediaPlayer.shouldHoldBufferingEnd). For a direct-URI source the
     * displayed buffered percentage is driven by received bytes, so once the native player has
     * filled its internal buffer and switched to decoding (a large HDR/4K first frame, or a
     * mid-playback rebuffer) the percentage freezes even though the player is alive and working.
     *
     * <p>Treat that active-buffering window as liveness so the outer buffer-stall safety-net does
     * not destructively release a healthy player whose percent has merely plateaued. Before the
     * first frame the native render watchdog is the authoritative deadline; after it, the stream
     * has already proven it plays, so patience is justified — a genuinely dead source surfaces a
     * native STATE_ERROR (which cancels the timer) instead. This is bounded by an absolute
     * per-buffering-episode ceiling so a fully wedged pipeline still fails, and it intentionally
     * does NOT apply to STATE_PREPARING, where a real prepare hang must still time out.
     */
    public static boolean shouldRefreshTimeoutForActiveBuffering(int playState,
                                                                 long elapsedSinceRefreshMs,
                                                                 long minimumRefreshIntervalMs,
                                                                 long elapsedSinceBufferingStartMs,
                                                                 long maxBufferingEpisodeMs) {
        return playState == VideoView.STATE_BUFFERING
                && elapsedSinceBufferingStartMs >= 0L
                && elapsedSinceBufferingStartMs < Math.max(0L, maxBufferingEpisodeMs)
                && elapsedSinceRefreshMs >= Math.max(0L, minimumRefreshIntervalMs);
    }

    public static boolean shouldDeferTimeoutForActiveBuffering(long elapsedSinceBufferingStartMs,
                                                               long maxBufferingEpisodeMs) {
        return elapsedSinceBufferingStartMs >= 0L
                && elapsedSinceBufferingStartMs < Math.max(0L, maxBufferingEpisodeMs);
    }
}
