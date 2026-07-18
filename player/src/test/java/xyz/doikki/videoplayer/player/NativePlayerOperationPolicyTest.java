package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativePlayerOperationPolicyTest {
    @Test
    public void seekTransitionBlocksQueriesButNeverBlocksStart() {
        assertFalse(NativePlayerOperationPolicy.canQuery(true));
        assertTrue(NativePlayerOperationPolicy.canStart(true));
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
    public void preparedResumeCanStartBeforeSeekCompletion() {
        SeekCoordinator seek = new SeekCoordinator();

        SeekCoordinator.Request request = seek.request(717_016, true);
        assertTrue(request.shouldDispatch);
        assertTrue(NativePlayerOperationPolicy.canStart(seek.isInFlight()));
        assertFalse(NativePlayerOperationPolicy.canQuery(seek.isInFlight()));
        SeekCoordinator.Completion completion = seek.complete();
        assertTrue(completion.accepted);
        assertTrue(completion.shouldResume);
        assertTrue(NativePlayerOperationPolicy.canStart(seek.isInFlight()));
    }

    @Test
    public void seekCompletionNeverRestartsAnAlreadyPlayingNativePlayer() {
        assertFalse(NativePlayerOperationPolicy.shouldStartAfterSeekCompletion(true, false));
        assertFalse(NativePlayerOperationPolicy.shouldStartAfterSeekCompletion(false, true));
        assertTrue(NativePlayerOperationPolicy.shouldStartAfterSeekCompletion(false, false));
    }
}
