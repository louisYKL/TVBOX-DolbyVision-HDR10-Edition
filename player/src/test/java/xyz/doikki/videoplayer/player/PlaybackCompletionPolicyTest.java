package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackCompletionPolicyTest {
    @Test
    public void renderedVideoNearDurationIsARealCompletion() {
        assertTrue(PlaybackCompletionPolicy.isExpectedCompletion(
                true, false, false, false, 3_590_000L, 3_600_000L));
    }

    @Test
    public void eofDuringSeekCannotAdvanceToNextEpisode() {
        assertFalse(PlaybackCompletionPolicy.isExpectedCompletion(
                true, false, true, false, 600_000L, 3_600_000L));
    }

    @Test
    public void earlyBlackScreenCompletionIsRejected() {
        assertFalse(PlaybackCompletionPolicy.isExpectedCompletion(
                false, false, false, false, 0L, 3_600_000L));
        assertFalse(PlaybackCompletionPolicy.isExpectedCompletion(
                true, false, false, false, 600_000L, 3_600_000L));
    }

    @Test
    public void audioOnlyContentDoesNotRequireVideoFrame() {
        assertTrue(PlaybackCompletionPolicy.isExpectedCompletion(
                false, true, false, false, 179_000L, 180_000L));
    }

    @Test
    public void earlyZeroPositionCompletionImmediatelyAfterSeekIsVendorFalseCompletion() {
        assertTrue(PlaybackCompletionPolicy.isLikelyPostSeekFalseCompletion(
                true, 717_016, 10_000L, 10_450L, 0L, 6_473_156L));
        assertFalse(PlaybackCompletionPolicy.isLikelyPostSeekFalseCompletion(
                true, 0, 10_000L, 10_450L, 0L, 6_473_156L));
        assertFalse(PlaybackCompletionPolicy.isLikelyPostSeekFalseCompletion(
                false, 717_016, 10_000L, 10_450L, 0L, 6_473_156L));
        assertFalse(PlaybackCompletionPolicy.isLikelyPostSeekFalseCompletion(
                true, 717_016, 10_000L, 10_450L, 600_000L, 6_473_156L));
    }

    @Test
    public void falseCompletionIgnoreWindowIsNarrow() {
        assertFalse(PlaybackCompletionPolicy.isLikelyPostSeekFalseCompletion(
                true, 717_016, 10_000L, 15_001L, 0L, 6_473_156L));
        assertFalse(PlaybackCompletionPolicy.isLikelyPostSeekFalseCompletion(
                true, 6_470_000, 10_000L, 10_450L, 0L, 6_473_156L));
    }

    @Test
    public void recoveryUsesNewestSeekTargetInsteadOfRestartingAtZero() {
        assertEquals(900_000, PlaybackCompletionPolicy.resolveRecoveryTarget(
                900_000, 497_223, 0L, 6_473_156L));
        assertEquals(600_000, PlaybackCompletionPolicy.resolveRecoveryTarget(
                600_000, 497_223, 0L, 6_473_156L));
        assertEquals(497_223, PlaybackCompletionPolicy.resolveRecoveryTarget(
                SeekCoordinator.NO_TARGET, 497_223, 0L, 6_473_156L));
    }
}
