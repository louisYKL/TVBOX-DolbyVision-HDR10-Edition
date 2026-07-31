package com.github.tvbox.osc.player.controller;

import xyz.doikki.videoplayer.player.VideoView;

public final class PlaybackLoadingOverlayPolicy {
    private PlaybackLoadingOverlayPolicy() {
    }

    public static boolean shouldShowNetworkSpeed(int playState,
                                                 boolean seekOverlayVisible,
                                                 boolean embeddedPreviewMode) {
        if (embeddedPreviewMode) {
            return false;
        }
        return playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_BUFFERING;
    }

    public static boolean shouldShowControllerLoading(int playState, boolean embeddedPreviewMode) {
        return !embeddedPreviewMode
                && (playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_BUFFERING);
    }

    public static String formatLoadingStatus(int playState, int bufferedPercent, String speed) {
        String safeSpeed = speed == null ? "" : speed.trim();
        if (playState != VideoView.STATE_PREPARING && playState != VideoView.STATE_BUFFERING) {
            return safeSpeed;
        }
        String label = playState == VideoView.STATE_PREPARING
                ? "正在加载视频 " : "正在缓冲视频 ";
        if (bufferedPercent == -1) {
            String unknownStatus = label.trim();
            return safeSpeed.isEmpty() ? unknownStatus : unknownStatus + "  " + safeSpeed;
        }
        String status = label + Math.max(0, Math.min(100, bufferedPercent)) + "%";
        return safeSpeed.isEmpty() ? status : status + "  " + safeSpeed;
    }
}
