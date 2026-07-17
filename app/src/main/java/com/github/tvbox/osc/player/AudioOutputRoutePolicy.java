package com.github.tvbox.osc.player;

final class AudioOutputRoutePolicy {
    private AudioOutputRoutePolicy() {
    }

    static boolean shouldForceTv32LocalProxyPcm(boolean tv32LocalProxy,
                                                 boolean passthroughAllowed) {
        return tv32LocalProxy && !passthroughAllowed;
    }
}
