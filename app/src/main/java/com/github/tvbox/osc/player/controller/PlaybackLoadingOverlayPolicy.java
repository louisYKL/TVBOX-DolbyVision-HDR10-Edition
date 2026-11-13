package com.github.tvbox.osc.player.controller;

import xyz.doikki.videoplayer.player.VideoView;

public final class PlaybackLoadingOverlayPolicy {
    private PlaybackLoadingOverlayPolicy() {
    }

    public static boolean shouldShowNetworkSpeed(int playState,
                                                 boolean seekOverlayVisible,
                                                 boolean embeddedPreviewMode) {
        // The Activity/Fragment loading surface is the single owner of phase, percentage and
        // speed. A controller label here created the observed overlapping small/large text.
        return false;
    }

    public static boolean shouldShowControllerLoading(int playState, boolean embeddedPreviewMode) {
        return shouldShowControllerLoading(playState, embeddedPreviewMode, false);
    }

    public static boolean shouldShowControllerLoading(int playState,
                                                      boolean embeddedPreviewMode,
                                                      boolean seekOverlayVisible) {
        // Keep one centered loading surface instead of a second controller spinner.
        return false;
    }

    public static String formatSeekStatus(String position,
                                          String duration,
                                          int playState,
                                          int bufferedPercent,
                                          String speed) {
        StringBuilder status = new StringBuilder()
                .append(position == null ? "--:--" : position)
                .append(" / ")
                .append(duration == null ? "--:--" : duration);
        boolean loading = playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_BUFFERING;
        if (loading) {
            status.append('\n')
                    .append(playState == VideoView.STATE_PREPARING ? "加载 " : "缓冲 ");
            if (bufferedPercent < 0) {
                status.append("中");
            } else {
                status.append(Math.max(0, Math.min(100, bufferedPercent))).append('%');
            }
        }
        String safeSpeed = speed == null ? "" : speed.trim();
        if (!safeSpeed.isEmpty()) {
            status.append('\n').append(safeSpeed);
        }
        return status.toString();
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
