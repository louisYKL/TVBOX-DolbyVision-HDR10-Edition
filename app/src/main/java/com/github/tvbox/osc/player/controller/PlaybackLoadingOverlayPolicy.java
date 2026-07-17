package com.github.tvbox.osc.player.controller;

import xyz.doikki.videoplayer.player.VideoView;

final class PlaybackLoadingOverlayPolicy {
    private PlaybackLoadingOverlayPolicy() {
    }

    static boolean shouldShowNetworkSpeed(int playState,
                                          boolean seekOverlayVisible,
                                          boolean embeddedPreviewMode) {
        if (seekOverlayVisible || embeddedPreviewMode) {
            return false;
        }
        return playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_BUFFERING;
    }

    static boolean shouldShowControllerLoading(int playState, boolean embeddedPreviewMode) {
        return !embeddedPreviewMode
                && (playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_BUFFERING);
    }

    static String formatLoadingStatus(int playState, int bufferedPercent, String speed) {
        String safeSpeed = speed == null ? "" : speed;
        if (playState != VideoView.STATE_PREPARING
                && playState != VideoView.STATE_BUFFERING) {
            return safeSpeed;
        }
        int percent = Math.max(0, Math.min(100, bufferedPercent));
        String label = playState == VideoView.STATE_PREPARING
                ? "正在加载视频 "
                : "正在缓冲视频 ";
        return label + percent + "% · " + safeSpeed;
    }
}
