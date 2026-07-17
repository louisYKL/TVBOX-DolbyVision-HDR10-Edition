package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackPositionRecoveryPolicyTest {
    @Test
    public void twoConsecutivePositionAdvancesConfirmRealPlayback() {
        int advances = PlaybackPositionRecoveryPolicy.nextAdvanceCount(-1L, 590_818L, 0);
        assertFalse(PlaybackPositionRecoveryPolicy.isPlaybackAdvancing(advances));

        advances = PlaybackPositionRecoveryPolicy.nextAdvanceCount(590_818L, 591_168L, advances);
        assertFalse(PlaybackPositionRecoveryPolicy.isPlaybackAdvancing(advances));

        advances = PlaybackPositionRecoveryPolicy.nextAdvanceCount(591_168L, 591_518L, advances);
        assertTrue(PlaybackPositionRecoveryPolicy.isPlaybackAdvancing(advances));
    }

    @Test
    public void stalledOrBackwardPositionCannotDismissBuffering() {
        assertEquals(0, PlaybackPositionRecoveryPolicy.nextAdvanceCount(10_000L, 10_000L, 1));
        assertEquals(0, PlaybackPositionRecoveryPolicy.nextAdvanceCount(10_000L, 9_000L, 1));
        assertEquals(0, PlaybackPositionRecoveryPolicy.nextAdvanceCount(10_000L, 10_020L, 1));
    }

    @Test
    public void oldSessionPauseAndSeekCannotRunAProbe() {
        assertFalse(PlaybackPositionRecoveryPolicy.canSample(
                false, true, true, false, false, false, false));
        assertFalse(PlaybackPositionRecoveryPolicy.canSample(
                true, true, false, false, false, false, false));
        assertFalse(PlaybackPositionRecoveryPolicy.canSample(
                true, true, true, true, false, false, false));
        assertFalse(PlaybackPositionRecoveryPolicy.canSample(
                true, true, true, false, true, false, false));
        assertTrue(PlaybackPositionRecoveryPolicy.canSample(
                true, true, true, false, false, false, false));
    }
}
