package com.github.tvbox.osc.util;

/** Keeps live URL normalization aligned with the decoder that actually owns playback. */
public final class LivePlaybackRoutePolicy {
    private LivePlaybackRoutePolicy() {
    }

    public static boolean shouldUseCompatUrl(int playerType) {
        return PlayerHelper.isBuiltInCompatPlayerType(playerType);
    }
}
