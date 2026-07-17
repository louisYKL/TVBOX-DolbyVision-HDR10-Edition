package xyz.doikki.videoplayer.player;

/**
 * Holds the initial resume target until the native player has entered STARTED.
 *
 * This is deliberately separate from {@link SeekCoordinator}: it never invokes
 * MediaPlayer and therefore cannot create a second native seek transition.
 */
final class PreparedResumeSeekCoordinator {
    static final int NO_TARGET = -1;

    private int pendingTarget = NO_TARGET;
    private boolean startCompleted;
    private boolean invocationClaimed;

    synchronized boolean queue(int target,
                                boolean directUri,
                                boolean rangeBacked,
                                boolean alreadyStarted) {
        if (target <= 0 || !directUri || rangeBacked || alreadyStarted) {
            return false;
        }
        pendingTarget = target;
        startCompleted = false;
        invocationClaimed = false;
        return true;
    }

    synchronized boolean markStartCompleted() {
        if (pendingTarget == NO_TARGET) {
            return false;
        }
        startCompleted = true;
        return true;
    }

    synchronized int getPendingTarget() {
        return pendingTarget;
    }

    synchronized boolean isInvocationPending() {
        return pendingTarget != NO_TARGET && invocationClaimed;
    }

    synchronized int claimInvocation() {
        if (pendingTarget == NO_TARGET || !startCompleted || invocationClaimed) {
            return NO_TARGET;
        }
        invocationClaimed = true;
        return pendingTarget;
    }

    synchronized boolean completeInvocation(int target) {
        if (!invocationClaimed || pendingTarget != target) {
            return false;
        }
        reset();
        return true;
    }

    synchronized void reset() {
        pendingTarget = NO_TARGET;
        startCompleted = false;
        invocationClaimed = false;
    }
}
