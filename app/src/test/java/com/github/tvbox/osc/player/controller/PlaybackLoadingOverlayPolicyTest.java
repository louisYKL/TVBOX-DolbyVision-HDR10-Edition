package com.github.tvbox.osc.player.controller;

import org.junit.Test;

import xyz.doikki.videoplayer.player.VideoView;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackLoadingOverlayPolicyTest {
    @Test
    public void loadingSpeedOnlyAppearsWhilePreparingOrBuffering() {
        assertTrue(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_PREPARING, false, false));
        assertTrue(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_BUFFERING, false, false));

        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_PLAYING, false, false));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_PREPARED, false, false));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_BUFFERED, false, false));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_PAUSED, false, false));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_ERROR, false, false));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_IDLE, false, false));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_PLAYBACK_COMPLETED, false, false));
    }

    @Test
    public void seekOverlaySuppressesLoadingSpeed() {
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_BUFFERING, true, false));
    }

    @Test
    public void embeddedPreviewUsesOnlyTheFragmentLoadingOverlay() {
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_BUFFERING, false, true));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowControllerLoading(
                VideoView.STATE_BUFFERING, true));
        assertTrue(PlaybackLoadingOverlayPolicy.shouldShowControllerLoading(
                VideoView.STATE_BUFFERING, false));
    }

    @Test
    public void bufferingStatusContainsBoundedPercentAndSpeed() {
        org.junit.Assert.assertEquals(
                "正在加载视频 12% · 3.1MB/s",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_PREPARING, 12, "3.1MB/s"));
        org.junit.Assert.assertEquals(
                "正在缓冲视频 37% · 5.2MB/s",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_BUFFERING, 37, "5.2MB/s"));
        org.junit.Assert.assertEquals(
                "正在缓冲视频 100% · 1MB/s",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_BUFFERING, 130, "1MB/s"));
    }
}
