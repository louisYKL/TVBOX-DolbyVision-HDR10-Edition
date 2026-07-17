package xyz.doikki.videoplayer.player;

/**
 * Native MediaPlayer calls are serialized around asynchronous seeks. Several TV firmwares
 * block getTrackInfo/isPlaying/position calls, or emit a false EOF, when queried before the
 * matching seek completion callback. Initial resume uses the same seek transition and starts
 * the decoder only after the native completion callback.
 */
final class NativePlayerOperationPolicy {
    private NativePlayerOperationPolicy() {
    }

    static boolean canStart(boolean seekTransitionActive) {
        return !seekTransitionActive;
    }

    static boolean canQuery(boolean seekTransitionActive) {
        return !seekTransitionActive;
    }

    static boolean canPause(boolean seekTransitionActive) {
        return !seekTransitionActive;
    }

    static boolean canAccessPlaybackParams(boolean seekTransitionActive) {
        return !seekTransitionActive;
    }

    static boolean canRunFirstFrameRecovery(boolean seekTransitionActive) {
        return !seekTransitionActive;
    }
}
