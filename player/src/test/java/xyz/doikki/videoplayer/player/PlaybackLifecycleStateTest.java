package xyz.doikki.videoplayer.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackLifecycleStateTest {
    @Test
    public void pauseDuringPrepareSurvivesPreparedCallback() {
        PlaybackLifecycleState state = new PlaybackLifecycleState();
        state.onPrepareStarted();
        state.requestPause();

        assertFalse(state.onPrepared());
        assertTrue(state.isPauseRequested());
    }

    @Test
    public void returningDuringPrepareDefersResumeUntilPreparedExactlyOnce() {
        PlaybackLifecycleState state = new PlaybackLifecycleState();
        state.onPrepareStarted();
        state.requestPause();

        assertTrue(state.isPauseRequested());
        assertFalse(state.requestResume());
        assertFalse(state.isPauseRequested());
        assertTrue(state.onPrepared());
        assertFalse(state.onPrepared());
    }

    @Test
    public void leavingAgainCancelsDeferredResume() {
        PlaybackLifecycleState state = new PlaybackLifecycleState();
        state.onPrepareStarted();
        state.requestPause();
        assertFalse(state.requestResume());

        state.requestPause();

        assertFalse(state.onPrepared());
        assertTrue(state.isPauseRequested());
    }

    @Test
    public void pauseAfterPrepareDoesNotScheduleResume() {
        PlaybackLifecycleState state = new PlaybackLifecycleState();
        state.onPrepareStarted();
        assertFalse(state.onPrepared());
        state.requestPause();

        assertTrue(state.isPauseRequested());
        assertTrue(state.requestResume());
        assertFalse(state.isPauseRequested());
        assertFalse(state.onPrepared());
    }

    @Test
    public void resetDropsPendingLifecycleIntent() {
        PlaybackLifecycleState state = new PlaybackLifecycleState();
        state.onPrepareStarted();
        state.requestPause();
        assertFalse(state.requestResume());
        state.reset();

        assertFalse(state.isPauseRequested());
        assertTrue(state.requestResume());
        assertFalse(state.onPrepared());
    }
}
