package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreparedResumeSeekCoordinatorTest {
    @Test
    public void onlyDirectNonRangeResumeIsHeldUntilStart() {
        PreparedResumeSeekCoordinator coordinator = new PreparedResumeSeekCoordinator();

        assertFalse(coordinator.queue(10_000, false, false, false));
        assertFalse(coordinator.queue(10_000, true, true, false));
        assertFalse(coordinator.queue(10_000, true, false, true));
        assertEquals(PreparedResumeSeekCoordinator.NO_TARGET,
                coordinator.getPendingTarget());

        assertTrue(coordinator.queue(10_000, true, false, false));
        assertEquals(10_000, coordinator.getPendingTarget());
        assertEquals(PreparedResumeSeekCoordinator.NO_TARGET,
                coordinator.claimInvocation());
    }

    @Test
    public void startAndInvocationAreEachClaimedOnce() {
        PreparedResumeSeekCoordinator coordinator = new PreparedResumeSeekCoordinator();
        assertTrue(coordinator.queue(10_000, true, false, false));
        assertTrue(coordinator.markStartCompleted());
        assertTrue(coordinator.markStartCompleted());
        assertEquals(10_000, coordinator.claimInvocation());
        assertTrue(coordinator.isInvocationPending());
        assertEquals(PreparedResumeSeekCoordinator.NO_TARGET,
                coordinator.claimInvocation());
        assertFalse(coordinator.completeInvocation(9_000));
        assertTrue(coordinator.completeInvocation(10_000));
        assertFalse(coordinator.isInvocationPending());
        assertEquals(PreparedResumeSeekCoordinator.NO_TARGET,
                coordinator.getPendingTarget());
    }

    @Test
    public void latestQueuedResumeReplacesOlderTargetAndResetCancelsIt() {
        PreparedResumeSeekCoordinator coordinator = new PreparedResumeSeekCoordinator();
        assertTrue(coordinator.queue(1_000, true, false, false));
        assertTrue(coordinator.queue(2_000, true, false, false));
        assertTrue(coordinator.markStartCompleted());
        assertEquals(2_000, coordinator.claimInvocation());
        coordinator.reset();
        assertEquals(PreparedResumeSeekCoordinator.NO_TARGET,
                coordinator.getPendingTarget());
        assertFalse(coordinator.completeInvocation(2_000));
    }

    @Test
    public void resetInvalidatesClaimedInvocationBeforeNativeDispatch() {
        PreparedResumeSeekCoordinator coordinator = new PreparedResumeSeekCoordinator();
        assertTrue(coordinator.queue(10_000, true, false, false));
        assertTrue(coordinator.markStartCompleted());
        assertEquals(10_000, coordinator.claimInvocation());
        assertTrue(coordinator.isInvocationPending());

        coordinator.reset();

        assertFalse(coordinator.isInvocationPending());
        assertFalse(coordinator.completeInvocation(10_000));
    }
}
