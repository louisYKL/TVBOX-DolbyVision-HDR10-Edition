package com.github.tvbox.osc.player;

import org.junit.Test;

import xyz.doikki.videoplayer.player.VideoView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackBufferProgressPolicyTest {
    @Test
    public void onlyAnIncreasingPlayerPercentRefreshesTheWatchdog() {
        assertTrue(PlaybackBufferProgressPolicy.shouldRefreshTimeout(
                VideoView.STATE_BUFFERING, 20, 21, 5_000L, 5_000L));
        assertFalse(PlaybackBufferProgressPolicy.shouldRefreshTimeout(
                VideoView.STATE_BUFFERING, 20, 20, 50_000L, 5_000L));
        assertFalse(PlaybackBufferProgressPolicy.shouldRefreshTimeout(
                VideoView.STATE_BUFFERING, 20, 10, 50_000L, 5_000L));
    }

    @Test
    public void progressCannotRenewAWatchdogBeforeTheRefreshInterval() {
        assertFalse(PlaybackBufferProgressPolicy.shouldRefreshTimeout(
                VideoView.STATE_PREPARING, 20, 21, 4_999L, 5_000L));
        assertTrue(PlaybackBufferProgressPolicy.shouldRefreshTimeout(
                VideoView.STATE_PREPARING, 20, 21, 5_000L, 5_000L));
    }

    @Test
    public void nonLoadingStatesNeverRefreshTheWatchdog() {
        assertFalse(PlaybackBufferProgressPolicy.shouldRefreshTimeout(
                VideoView.STATE_PLAYING, 20, 21, 50_000L, 5_000L));
        assertFalse(PlaybackBufferProgressPolicy.shouldRefreshTimeout(
                VideoView.STATE_PAUSED, 20, 21, 50_000L, 5_000L));
    }

    @Test
    public void detailLoadingOverlayNeverReturnsAfterTheFirstFrame() {
        assertTrue(PlaybackBufferProgressPolicy.shouldShowDetailLoadingOverlay(
                VideoView.STATE_PREPARING, false, false));
        assertTrue(PlaybackBufferProgressPolicy.shouldShowDetailLoadingOverlay(
                VideoView.STATE_BUFFERING, false, false));
        assertFalse(PlaybackBufferProgressPolicy.shouldShowDetailLoadingOverlay(
                VideoView.STATE_BUFFERING, true, false));
        assertFalse(PlaybackBufferProgressPolicy.shouldShowDetailLoadingOverlay(
                VideoView.STATE_BUFFERING, false, true));
        assertFalse(PlaybackBufferProgressPolicy.shouldShowDetailLoadingOverlay(
                VideoView.STATE_PLAYING, false, false));
    }

    @Test
    public void highWaterMarkNeverMovesBackwardOrOutsidePercentBounds() {
        int highWater = -1;
        highWater = PlaybackBufferProgressPolicy.updateHighWater(highWater, -20);
        assertEquals(0, highWater);
        highWater = PlaybackBufferProgressPolicy.updateHighWater(highWater, 72);
        assertEquals(72, highWater);
        highWater = PlaybackBufferProgressPolicy.updateHighWater(highWater, 12);
        assertEquals(72, highWater);
        highWater = PlaybackBufferProgressPolicy.updateHighWater(highWater, 180);
        assertEquals(100, highWater);
    }
}
