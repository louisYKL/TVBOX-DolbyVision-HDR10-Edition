package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackProgressPolicyTest {
    @Test
    public void activeSeekPersistsTheNewestRequestedPosition() {
        assertEquals(90_000L,
                PlaybackProgressPolicy.resolve(false, true, true,
                        90_000L, true, -1L, 12_000L));
    }

    @Test
    public void completedSeekPersistsItsTargetDuringQueryGuard() {
        assertEquals(90_000L,
                PlaybackProgressPolicy.resolve(false, true, false,
                        -1L, true, 90_000L, 12_000L));
    }

    @Test
    public void completedSeekToStartClearsPreviouslySavedProgress() {
        assertEquals(0L,
                PlaybackProgressPolicy.resolve(false, true, false,
                        -1L, true, 0L, 45_000L));
        assertEquals(0L,
                PlaybackProgressPolicy.resolve(false, true, false,
                        -1L, false, 0L, 0L));
    }

    @Test
    public void ordinaryBufferingPersistsLastConfirmedPlaybackPosition() {
        assertEquals(45_000L,
                PlaybackProgressPolicy.resolve(false, true, false,
                        -1L, true, -1L, 45_000L));
    }

    @Test
    public void preparingStateDoesNotPersist() {
        assertEquals(PlaybackProgressPolicy.SKIP,
                PlaybackProgressPolicy.resolve(false, false, false,
                        -1L, false, -1L, 45_000L));
    }

    @Test
    public void playbackCompletionClearsSavedProgress() {
        assertEquals(0L,
                PlaybackProgressPolicy.resolve(true, true, false,
                        -1L, false, -1L, 45_000L));
    }
}
