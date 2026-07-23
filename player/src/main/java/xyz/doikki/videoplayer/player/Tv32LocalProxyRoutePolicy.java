package xyz.doikki.videoplayer.player;

/** Keeps TV32 local VOD on the proxy-backed prebuffering path. */
final class Tv32LocalProxyRoutePolicy {
    private Tv32LocalProxyRoutePolicy() {
    }

    static boolean shouldUseDirectSystemUri(boolean tv32Device,
                                            boolean localProxyVod,
                                            boolean hlsStream) {
        // The proxy-backed FD/MediaDataSource path is the established VOD buffering
        // path. HDR/DV-specific direct URI decisions remain in AndroidMediaPlayer.
        return false;
    }

}
