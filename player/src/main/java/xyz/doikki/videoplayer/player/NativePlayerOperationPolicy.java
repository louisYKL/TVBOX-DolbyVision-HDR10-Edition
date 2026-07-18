package xyz.doikki.videoplayer.player;

/**
 * Native queries and pause operations are serialized around asynchronous seeks. Starting is
 * intentionally allowed: the released v0.2.1 sequence starts immediately after prepared even
 * when its resume seek is still completing.
 */
final class NativePlayerOperationPolicy {
    private NativePlayerOperationPolicy() {
    }

    static boolean canStart(boolean seekTransitionActive) {
        return true;
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

    static boolean shouldStartAfterSeekCompletion(boolean logicallyStarted,
                                                  boolean nativePlayerIsPlaying) {
        return !logicallyStarted && !nativePlayerIsPlaying;
    }
}
