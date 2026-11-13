package xyz.doikki.videoplayer.player;

/** Keeps the caller's pause intent independently of native buffering/prepare callbacks. */
final class PlaybackLifecycleState {
    private boolean pauseRequested;
    private boolean preparing;
    private boolean resumeAfterPrepare;

    void reset() {
        pauseRequested = false;
        preparing = false;
        resumeAfterPrepare = false;
    }

    void onPrepareStarted() {
        preparing = true;
    }

    void requestPause() {
        pauseRequested = true;
        resumeAfterPrepare = false;
    }

    /** Returns whether it is safe for the view to resume the core immediately. */
    boolean requestResume() {
        pauseRequested = false;
        if (preparing) {
            resumeAfterPrepare = true;
            return false;
        }
        return true;
    }

    /** Returns whether a resume deferred during prepare must now be sent to the core. */
    boolean onPrepared() {
        preparing = false;
        boolean resume = resumeAfterPrepare && !pauseRequested;
        resumeAfterPrepare = false;
        return resume;
    }

    boolean isPauseRequested() {
        return pauseRequested;
    }
}
