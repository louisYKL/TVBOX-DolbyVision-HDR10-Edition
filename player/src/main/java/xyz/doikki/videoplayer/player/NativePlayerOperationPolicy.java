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
        // The vendor player can report a completed initial seek after its pre-seek start
        // was discarded internally. The logical state is then STARTED while the native
        // player is not running. Preserve the v0.2.1 behavior and re-arm playback from
        // the actual native state, otherwise the UI remains stuck at buffering forever.
        return !nativePlayerIsPlaying;
    }

    /**
     * A logical STARTED state is not proof that the vendor player is still running. A
     * rebuffer callback may leave the native player paused while the wrapper state remains
     * STARTED; calling start() again is safe and restores the output intent.
     */
    static boolean shouldRearmStartedPlayer(boolean logicallyStarted,
                                             boolean playbackHasStarted,
                                             boolean nativePlayerIsPlaying) {
        return !(logicallyStarted && playbackHasStarted && nativePlayerIsPlaying);
    }

    /** Prepared/Preparing are intent-only states; MediaPlayer.pause() is not legal there. */
    static boolean shouldInvokeNativePause(boolean preparing, boolean prepared,
                                            boolean playbackHasStarted) {
        // Repeated pause requests may already have changed the wrapper to PAUSED while
        // the underlying player is still Preparing/Prepared. It cannot be paused until
        // at least one native start has succeeded, regardless of that wrapper state.
        return playbackHasStarted && !preparing && !prepared;
    }
}
