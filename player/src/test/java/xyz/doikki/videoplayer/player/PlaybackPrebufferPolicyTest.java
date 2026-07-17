package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackPrebufferPolicyTest {
    private static final long MB = 1024L * 1024L;

    @Test
    public void normalPlaybackRequiresTheConfiguredLargeBuffer() {
        assertEquals(24L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                24L * MB, 20_327_632_348L, 10L * MB));
        assertFalse(PlaybackPrebufferPolicy.isReady(23L * MB, 24L * MB));
        assertTrue(PlaybackPrebufferPolicy.isReady(24L * MB, 24L * MB));
    }

    @Test
    public void healthyFastSourceWaitsForFullInitialBuffer() {
        assertFalse(PlaybackPrebufferPolicy.isInitialStartReady(
                16L * MB, 24L * MB, 5_000L, 8L * MB, 20_000L));
        assertTrue(PlaybackPrebufferPolicy.isInitialStartReady(
                24L * MB, 24L * MB, 5_000L, 8L * MB, 20_000L));
    }

    @Test
    public void slowSourceCanStartWithSubstantialFallbackBufferAfterBoundedWait() {
        assertFalse(PlaybackPrebufferPolicy.isInitialStartReady(
                7L * MB, 24L * MB, 20_000L, 8L * MB, 20_000L));
        assertTrue(PlaybackPrebufferPolicy.isInitialStartReady(
                8L * MB, 24L * MB, 20_000L, 8L * MB, 20_000L));
    }

    @Test
    public void sourceWithNoUsableBufferEventuallyReportsHardStall() {
        assertFalse(PlaybackPrebufferPolicy.isHardStalled(
                0L, 24L * MB, 44_999L, 8L * MB, 45_000L));
        assertTrue(PlaybackPrebufferPolicy.isHardStalled(
                0L, 24L * MB, 45_000L, 8L * MB, 45_000L));
        assertFalse(PlaybackPrebufferPolicy.isHardStalled(
                8L * MB, 24L * MB, 45_000L, 8L * MB, 45_000L));
    }

    @Test
    public void stallWatchdogOnlyRefreshesWhenBufferedBytesAdvance() {
        assertFalse(PlaybackPrebufferPolicy.hasForwardProgress(-1L, 8L * MB));
        assertFalse(PlaybackPrebufferPolicy.hasForwardProgress(8L * MB, 8L * MB));
        assertFalse(PlaybackPrebufferPolicy.hasForwardProgress(8L * MB, 4L * MB));
        assertTrue(PlaybackPrebufferPolicy.hasForwardProgress(8L * MB, 16L * MB));
    }

    @Test
    public void seekDuringTargetPrebufferKeepsPlaybackIntent() {
        assertTrue(PlaybackPrebufferPolicy.shouldPlayWhenReady(
                false, false, true, false, false));
    }

    @Test
    public void explicitPauseDoesNotInventPlaybackIntent() {
        assertFalse(PlaybackPrebufferPolicy.shouldPlayWhenReady(
                false, false, false, false, false));
    }

    @Test
    public void detachedDisplayKeepsPendingPlaybackIntent() {
        assertTrue(PlaybackPrebufferPolicy.shouldPlayWhenReady(
                false, false, false, true, false));
    }

    @Test
    public void endOfMediaOnlyRequiresTheRemainingBytes() {
        assertEquals(3L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                24L * MB, 100L * MB, 97L * MB));
    }

    @Test
    public void hundredThousandSeekTargetsNeverOverflowOrExceedRemainingMedia() {
        long total = 20_327_632_348L;
        long configured = 24L * MB;
        for (int seek = 0; seek < 100_000; seek++) {
            long anchor = ((long) seek * 13_771_337L) % total;
            long required = PlaybackPrebufferPolicy.resolveRequiredBytes(configured, total, anchor);
            assertTrue(required >= 0L);
            assertTrue(required <= configured);
            assertTrue(required <= total - anchor);
        }
    }

    @Test
    public void activePrebufferKeepsItsAnchorWhileDecoderReadHeadAdvances() {
        long fixedAnchor = 1_797_259_264L;
        for (int read = 0; read < 100_000; read++) {
            long liveReadHead = fixedAnchor + (long) read * 128L * 1024L;
            assertEquals(fixedAnchor, PlaybackPrebufferPolicy.resolveAnchor(
                    true, fixedAnchor, liveReadHead, 0L));
        }
    }

    @Test
    public void completedPrebufferFollowsTheLiveReadHeadAgain() {
        long liveReadHead = 1_830_813_696L;
        assertEquals(liveReadHead, PlaybackPrebufferPolicy.resolveAnchor(
                false, 1_797_259_264L, liveReadHead, 0L));
    }
}
