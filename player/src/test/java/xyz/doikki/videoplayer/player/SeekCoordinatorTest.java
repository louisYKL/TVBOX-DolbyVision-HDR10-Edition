package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeekCoordinatorTest {
    @Test
    public void millionRequestsKeepOneNativeSeekAndOnlyTheNewestQueuedTarget() {
        SeekCoordinator coordinator = new SeekCoordinator();

        SeekCoordinator.Request first = coordinator.request(0, true);
        assertTrue(first.shouldDispatch);
        assertTrue(first.dispatchId != SeekCoordinator.NO_DISPATCH_ID);
        for (int target = 1; target < 1_000_000; target++) {
            SeekCoordinator.Request replacement = coordinator.request(target, true);
            assertFalse(replacement.shouldDispatch);
            assertEquals(0, coordinator.getActiveTarget());
            assertEquals(target, coordinator.getLatestTarget());
        }

        SeekCoordinator.Completion firstCompletion = coordinator.complete();
        assertTrue(firstCompletion.accepted);
        assertTrue(firstCompletion.shouldDispatchNext);
        assertEquals(0, firstCompletion.completedTarget);
        assertEquals(999_999, firstCompletion.nextTarget);
        assertFalse(firstCompletion.shouldResume);
        assertTrue(coordinator.isInFlight());

        SeekCoordinator.Completion finalCompletion = coordinator.complete();
        assertTrue(finalCompletion.accepted);
        assertFalse(finalCompletion.shouldDispatchNext);
        assertEquals(999_999, finalCompletion.completedTarget);
        assertTrue(finalCompletion.shouldResume);
        assertFalse(coordinator.isInFlight());
    }

    @Test
    public void alternatingForwardAndBackwardRequestsAlwaysUseLatestTarget() {
        SeekCoordinator coordinator = new SeekCoordinator();

        assertTrue(coordinator.request(1_000, true).shouldDispatch);
        assertFalse(coordinator.request(90_000, true).shouldDispatch);
        assertFalse(coordinator.request(500, true).shouldDispatch);
        SeekCoordinator.Completion firstDone = coordinator.complete();
        assertTrue(firstDone.shouldDispatchNext);
        assertEquals(500, firstDone.nextTarget);
        SeekCoordinator.Completion finalDone = coordinator.complete();
        assertEquals(500, finalDone.completedTarget);
        assertTrue(finalDone.shouldResume);
    }

    @Test
    public void pauseDuringSeekCancelsFinalResume() {
        SeekCoordinator coordinator = new SeekCoordinator();

        coordinator.request(1_000, true);
        coordinator.request(2_000, true);
        coordinator.cancelResume();

        SeekCoordinator.Completion firstDone = coordinator.complete();
        assertTrue(firstDone.shouldDispatchNext);
        SeekCoordinator.Completion done = coordinator.complete();
        assertFalse(done.shouldDispatchNext);
        assertFalse(done.shouldResume);
        assertFalse(coordinator.isInFlight());
    }

    @Test
    public void startDuringSeekRestoresFinalResumeIntent() {
        SeekCoordinator coordinator = new SeekCoordinator();

        coordinator.request(1_000, false);
        coordinator.requestResume();

        SeekCoordinator.Completion done = coordinator.complete();
        assertTrue(done.shouldResume);
        assertFalse(coordinator.isInFlight());
    }

    @Test
    public void duplicateNewestTargetDoesNotDispatchTwice() {
        SeekCoordinator coordinator = new SeekCoordinator();

        coordinator.request(10_000, true);
        SeekCoordinator.Request newest = coordinator.request(10_000, true);

        assertFalse(newest.shouldDispatch);
        SeekCoordinator.Completion done = coordinator.complete();
        assertEquals(10_000, done.completedTarget);
    }

    @Test
    public void returningToActiveTargetDropsAnObsoleteQueuedTarget() {
        SeekCoordinator coordinator = new SeekCoordinator();

        coordinator.request(10_000, true);
        coordinator.request(20_000, true);
        SeekCoordinator.Request latest = coordinator.request(10_000, true);

        assertFalse(latest.shouldDispatch);
        assertEquals(SeekCoordinator.NO_TARGET, coordinator.getQueuedTarget());
        SeekCoordinator.Completion done = coordinator.complete();
        assertFalse(done.shouldDispatchNext);
        assertEquals(10_000, done.completedTarget);
        assertTrue(done.shouldResume);
    }

    @Test
    public void anOldCallbackCannotCompleteTheQueuedTarget() {
        SeekCoordinator coordinator = new SeekCoordinator();

        SeekCoordinator.Request active = coordinator.request(10_000, true);
        coordinator.request(20_000, true);
        SeekCoordinator.Completion activeCompletion = coordinator.complete();

        assertEquals(active.dispatchId, activeCompletion.completedDispatchId);
        assertEquals(10_000, activeCompletion.completedTarget);
        assertTrue(activeCompletion.shouldDispatchNext);
        assertEquals(20_000, activeCompletion.nextTarget);
        assertTrue(coordinator.isActiveDispatch(
                activeCompletion.nextDispatchId, activeCompletion.nextTarget));

        SeekCoordinator.Completion queuedCompletion = coordinator.complete();
        assertEquals(20_000, queuedCompletion.completedTarget);
        assertFalse(queuedCompletion.shouldDispatchNext);
        assertTrue(queuedCompletion.shouldResume);
    }

    @Test
    public void hundredThousandSequentialSeeksLeaveNoStaleState() {
        SeekCoordinator coordinator = new SeekCoordinator();
        long previousDispatchId = SeekCoordinator.NO_DISPATCH_ID;

        for (int request = 0; request < 100_000; request++) {
            int target = (request % 2 == 0)
                    ? request * 1_000
                    : 200_000_000 - request * 500;
            SeekCoordinator.Request dispatch = coordinator.request(target, true);
            assertTrue(dispatch.shouldDispatch);
            assertTrue(dispatch.dispatchId != previousDispatchId);
            previousDispatchId = dispatch.dispatchId;
            SeekCoordinator.Completion completion = coordinator.complete();
            assertTrue(completion.accepted);
            assertEquals(target, completion.completedTarget);
            assertTrue(completion.shouldResume);
            assertFalse(coordinator.isInFlight());
            assertEquals(SeekCoordinator.NO_TARGET, coordinator.getActiveTarget());
        }
    }

    @Test
    public void resetInvalidatesQueuedNativeInvocationAndItsFailure() {
        SeekCoordinator coordinator = new SeekCoordinator();

        SeekCoordinator.Request stale = coordinator.request(10_000, true);
        coordinator.reset();
        SeekCoordinator.Request current = coordinator.request(20_000, true);

        assertFalse(coordinator.isActiveDispatch(stale.dispatchId, stale.target));
        assertFalse(coordinator.fail(stale.dispatchId, stale.target));
        assertTrue(coordinator.isInFlight());
        assertTrue(coordinator.isActiveDispatch(current.dispatchId, current.target));
        assertTrue(coordinator.fail(current.dispatchId, current.target));
        assertFalse(coordinator.isInFlight());
    }
}
