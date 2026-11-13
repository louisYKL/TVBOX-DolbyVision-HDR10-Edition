package com.github.tvbox.osc.player.controller;

import org.junit.Test;

import xyz.doikki.videoplayer.player.VideoView;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackLoadingOverlayPolicyTest {
    @Test
    public void controllerDoesNotRenderDuplicateLoadingSpeed() {
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_PREPARING, false, false));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
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
    public void seekOverlaySuppressesDuplicateNetworkStatus() {
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_BUFFERING, true, false));
    }

    @Test
    public void embeddedPreviewUsesOnlyTheFragmentLoadingOverlay() {
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowNetworkSpeed(
                VideoView.STATE_BUFFERING, false, true));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowControllerLoading(
                VideoView.STATE_BUFFERING, true));
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowControllerLoading(
                VideoView.STATE_BUFFERING, false));
    }

    @Test
    public void seekOverlayCombinesPositionBufferingAndSpeed() {
        assertFalse(PlaybackLoadingOverlayPolicy.shouldShowControllerLoading(
                VideoView.STATE_BUFFERING, false, true));
        org.junit.Assert.assertEquals(
                "00:12 / 01:20\n缓冲 37%\n5.2MB/s",
                PlaybackLoadingOverlayPolicy.formatSeekStatus(
                        "00:12", "01:20", VideoView.STATE_BUFFERING, 37, "5.2MB/s"));
        org.junit.Assert.assertEquals(
                "00:12 / 01:20\n缓冲 中",
                PlaybackLoadingOverlayPolicy.formatSeekStatus(
                        "00:12", "01:20", VideoView.STATE_BUFFERING, -1, ""));
    }

    @Test
    public void controllerStatusContainsPhaseProgressAndCurrentSpeed() {
        org.junit.Assert.assertEquals(
                "正在加载视频 12%  3.1MB/s",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_PREPARING, 12, "3.1MB/s"));
        org.junit.Assert.assertEquals(
                "正在缓冲视频 37%  5.2MB/s",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_BUFFERING, 37, "5.2MB/s"));
        org.junit.Assert.assertEquals(
                "正在缓冲视频 100%  1MB/s",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_BUFFERING, 130, "1MB/s"));
        org.junit.Assert.assertEquals(
                "正在缓冲视频 0%",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_BUFFERING, -10, ""));
        org.junit.Assert.assertEquals(
                "正在加载视频  1.2MB/s",
                PlaybackLoadingOverlayPolicy.formatLoadingStatus(
                        VideoView.STATE_PREPARING, -1, "1.2MB/s"));
    }
}
