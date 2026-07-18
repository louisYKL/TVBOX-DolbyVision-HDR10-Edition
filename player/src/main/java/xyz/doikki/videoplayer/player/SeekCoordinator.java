package xyz.doikki.videoplayer.player;

/** Tracks one native MediaPlayer seek plus the latest target requested behind it. */
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
        final boolean shouldDispatchNext;
        final int nextTarget;
        final long nextDispatchId;

        private Completion(boolean accepted,
                           boolean shouldResume,
                           int completedTarget,
                           long completedDispatchId,
                           boolean shouldDispatchNext,
                           int nextTarget,
                           long nextDispatchId) {
            this.accepted = accepted;
            this.shouldResume = shouldResume;
            this.completedTarget = completedTarget;
            this.completedDispatchId = completedDispatchId;
            this.shouldDispatchNext = shouldDispatchNext;
            this.nextTarget = nextTarget;
            this.nextDispatchId = nextDispatchId;
        }
    }

    private boolean inFlight;
    private int activeTarget = NO_TARGET;
    private long activeDispatchId = NO_DISPATCH_ID;
    private int queuedTarget = NO_TARGET;
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
            boolean clearedQueuedTarget = queuedTarget != NO_TARGET;
            queuedTarget = NO_TARGET;
            return new Request(false, target, NO_DISPATCH_ID,
                    clearedQueuedTarget ? "latest-matches-active" : "duplicate-active");
        }
        if (target == queuedTarget) {
            return new Request(false, target, NO_DISPATCH_ID, "duplicate-queued");
        }
        // MediaPlayer's callback does not identify which seek completed. Never issue a second
        // native seek until the active callback arrives; only retain the newest requested target.
        queuedTarget = target;
        return new Request(false, target, NO_DISPATCH_ID, "queued-latest");
    }

    synchronized Completion complete() {
        if (!inFlight) {
            return new Completion(false, false, NO_TARGET, NO_DISPATCH_ID,
                    false, NO_TARGET, NO_DISPATCH_ID);
        }
        int completedTarget = activeTarget;
        long completedDispatchId = activeDispatchId;
        if (queuedTarget != NO_TARGET) {
            activeTarget = queuedTarget;
            activeDispatchId = allocateDispatchId();
            queuedTarget = NO_TARGET;
            return new Completion(true, false, completedTarget, completedDispatchId,
                    true, activeTarget, activeDispatchId);
        }
        boolean shouldResume = resumeAfterFinalSeek;
        clearActiveState();
        return new Completion(true, shouldResume, completedTarget, completedDispatchId,
                false, NO_TARGET, NO_DISPATCH_ID);
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
        queuedTarget = NO_TARGET;
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

    synchronized int getLatestTarget() {
        return queuedTarget != NO_TARGET ? queuedTarget : activeTarget;
    }

    synchronized int getQueuedTarget() {
        return queuedTarget;
    }

    synchronized long getActiveDispatchId() {
        return activeDispatchId;
    }
}
