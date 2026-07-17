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
    public void initialResumeCannotOverlapNativeStartAndSeek() {
        assertFalse(NativePlayerOperationPolicy.canStart(true));
        assertFalse(NativePlayerOperationPolicy.canQuery(true));
        assertFalse(NativePlayerOperationPolicy.canPause(true));
        assertFalse(NativePlayerOperationPolicy.canAccessPlaybackParams(true));
        assertFalse(NativePlayerOperationPolicy.canRunFirstFrameRecovery(true));
    }

    @Test
    public void initialResumeUsesTheOrdinarySeekTransitionBeforeStart() {
        SeekCoordinator seek = new SeekCoordinator();

        SeekCoordinator.Request request = seek.request(717_016, true);

        assertFalse(NativePlayerOperationPolicy.canQuery(seek.isInFlight()));
        assertFalse(NativePlayerOperationPolicy.canStart(seek.isInFlight()));
        assertTrue(seek.complete().accepted);
        assertTrue(NativePlayerOperationPolicy.canQuery(seek.isInFlight()));
        assertTrue(NativePlayerOperationPolicy.canStart(seek.isInFlight()));
        assertTrue(request.shouldDispatch);
    }
}
