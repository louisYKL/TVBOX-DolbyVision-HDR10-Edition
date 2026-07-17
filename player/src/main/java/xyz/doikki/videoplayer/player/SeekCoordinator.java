package xyz.doikki.videoplayer.player;

/**
 * Tracks the latest native MediaPlayer seek.
 *
 * <p>Android's MediaPlayer already collapses an in-flight seek when a newer seekTo call
 * arrives. Serially replaying the old target and then the new target discards the network
 * buffer twice on several TV firmwares, so a newer request replaces the active request
 * immediately.</p>
 */
final class SeekCoordinator {
    static final int NO_TARGET = -1;
    static final long NO_DISPATCH_ID = 0L;

    static final class Request {
        final boolean shouldDispatch;
        final int target;
        final long dispatchId;
        final String reason;

        private Request(boolean shouldDispatch, int target, long dispatchId, String reason) {
            this.shouldDispatch = shouldDispatch;
            this.target = target;
            this.dispatchId = dispatchId;
            this.reason = reason;
        }
    }

    static final class Completion {
        final boolean accepted;
        final boolean shouldResume;
        final int completedTarget;
        final long completedDispatchId;

        private Completion(boolean accepted,
                           boolean shouldResume,
                           int completedTarget,
                           long completedDispatchId) {
            this.accepted = accepted;
            this.shouldResume = shouldResume;
            this.completedTarget = completedTarget;
            this.completedDispatchId = completedDispatchId;
        }
    }

    private boolean inFlight;
    private int activeTarget = NO_TARGET;
    private long activeDispatchId = NO_DISPATCH_ID;
    private boolean resumeAfterFinalSeek;
    private long nextDispatchId = 1L;

    synchronized Request request(int target, boolean shouldResume) {
        if (!inFlight) {
            inFlight = true;
            activeTarget = target;
            activeDispatchId = allocateDispatchId();
            resumeAfterFinalSeek = shouldResume;
            return new Request(true, target, activeDispatchId, "dispatch");
        }

        resumeAfterFinalSeek = resumeAfterFinalSeek || shouldResume;
        if (target == activeTarget) {
            return new Request(false, target, NO_DISPATCH_ID, "duplicate-active");
        }
        activeTarget = target;
        activeDispatchId = allocateDispatchId();
        return new Request(true, target, activeDispatchId, "supersede-active");
    }

    synchronized Completion complete() {
        if (!inFlight) {
            return new Completion(false, false, NO_TARGET, NO_DISPATCH_ID);
        }
        int completedTarget = activeTarget;
        long completedDispatchId = activeDispatchId;
        boolean shouldResume = resumeAfterFinalSeek;
        clearActiveState();
        return new Completion(true, shouldResume, completedTarget, completedDispatchId);
    }

    synchronized boolean isActiveDispatch(long dispatchId, int target) {
        return inFlight
                && dispatchId != NO_DISPATCH_ID
                && dispatchId == activeDispatchId
                && target == activeTarget;
    }

    synchronized boolean fail(long dispatchId, int target) {
        if (!isActiveDispatch(dispatchId, target)) {
            return false;
        }
        clearActiveState();
        return true;
    }

    synchronized void cancelResume() {
        resumeAfterFinalSeek = false;
    }

    synchronized void requestResume() {
        if (inFlight) {
            resumeAfterFinalSeek = true;
        }
    }

    synchronized void reset() {
        clearActiveState();
    }

    private void clearActiveState() {
        inFlight = false;
        activeTarget = NO_TARGET;
        activeDispatchId = NO_DISPATCH_ID;
        resumeAfterFinalSeek = false;
    }

    private long allocateDispatchId() {
        long allocated = nextDispatchId++;
        if (nextDispatchId == NO_DISPATCH_ID) {
            nextDispatchId = 1L;
        }
        return allocated;
    }

    synchronized boolean isInFlight() {
        return inFlight;
    }

    synchronized boolean shouldResumeAfterFinalSeek() {
        return resumeAfterFinalSeek;
    }

    synchronized int getActiveTarget() {
        return activeTarget;
    }

    synchronized long getActiveDispatchId() {
        return activeDispatchId;
    }
}
