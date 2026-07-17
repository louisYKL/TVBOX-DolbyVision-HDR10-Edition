package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativePlayerOperationPolicyTest {
    @Test
    public void seekTransitionBlocksNativeQueriesAndStart() {
        assertFalse(NativePlayerOperationPolicy.canQuery(true));
        assertFalse(NativePlayerOperationPolicy.canStart(true));
        assertFalse(NativePlayerOperationPolicy.canPause(true));
        assertFalse(NativePlayerOperationPolicy.canAccessPlaybackParams(true));
        assertFalse(NativePlayerOperationPolicy.canRunFirstFrameRecovery(true));
    }

    @Test
    public void realSeekCompletionReopensNativeOperations() {
        assertTrue(NativePlayerOperationPolicy.canQuery(false));
        assertTrue(NativePlayerOperationPolicy.canStart(false));
        assertTrue(NativePlayerOperationPolicy.canPause(false));
        assertTrue(NativePlayerOperationPolicy.canAccessPlaybackParams(false));
        assertTrue(NativePlayerOperationPolicy.canRunFirstFrameRecovery(false));
    }

    @Test
    public void preparedResumeUsesTheSameSerializedNativeSeekTransition() {
        PreparedResumeSeekCoordinator prepared = new PreparedResumeSeekCoordinator();
        SeekCoordinator seek = new SeekCoordinator();

        assertTrue(prepared.queue(717_016, true, false, false));
        assertTrue(prepared.markStartCompleted());
        int target = prepared.claimInvocation();
        assertTrue(NativePlayerOperationPolicy.canStart(seek.isInFlight()));

        SeekCoordinator.Request request = seek.request(target, true);
        assertTrue(request.shouldDispatch);
        assertFalse(NativePlayerOperationPolicy.canStart(seek.isInFlight()));
        assertFalse(NativePlayerOperationPolicy.canQuery(seek.isInFlight()));
        assertTrue(seek.complete().accepted);
        assertTrue(NativePlayerOperationPolicy.canStart(seek.isInFlight()));
    }
}
