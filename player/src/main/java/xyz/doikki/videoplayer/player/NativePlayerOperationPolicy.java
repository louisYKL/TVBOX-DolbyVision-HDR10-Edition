package xyz.doikki.videoplayer.player;

/**
 * Native MediaPlayer calls are serialized around asynchronous seeks. Several TV firmwares
 * block getTrackInfo/isPlaying/position calls, or emit a false EOF, when queried before the
 * matching seek completion callback. Initial resume starts the decoder before it enters the
 * same serialized native seek transition.
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
