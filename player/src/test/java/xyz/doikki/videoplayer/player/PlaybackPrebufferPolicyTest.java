package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackPrebufferPolicyTest {
    private static final long MB = 1024L * 1024L;

    @Test
    public void fillAheadUsesTheConfiguredLargeBuffer() {
        assertEquals(96L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                96L * MB, 20_327_632_348L, 10L * MB));
    }

    @Test
    public void endOfMediaOnlyRequiresTheRemainingBytes() {
        assertEquals(3L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                96L * MB, 100L * MB, 97L * MB));
    }

    @Test
    public void unknownMediaSizeStillRequiresTheConfiguredPrebuffer() {
        assertEquals(96L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                96L * MB, -1L, 0L));
    }

    @Test
    public void knownLoopbackShortRangeDefectDoesNotPinStartupAtNinetyNinePercent() {
        long required = PlaybackPrebufferPolicy.resolveRangeAwareRequiredBytes(
                96L * MB, -1L, 0L, 24L * MB, 8L);
        long observedContiguousBytes = 96L * MB - 20L;

        assertEquals(96L * MB - 32L, required);
        assertTrue(PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                false, observedContiguousBytes, required));
        assertTrue(!PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                false, required - 1L, required));
        assertTrue(!PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                false, 64L * MB, required));
    }

    @Test
    public void rangeShortfallToleranceScalesByWindowsAndNeverExceedsRequiredBytes() {
        assertEquals(48L * MB - 16L,
                PlaybackPrebufferPolicy.resolveRangeAwareRequiredBytes(
                        96L * MB, 100L * MB, 52L * MB, 24L * MB, 8L));
        assertEquals(0L, PlaybackPrebufferPolicy.resolveRangeAwareRequiredBytes(
                96L * MB, 1L, 0L, 24L * MB, 8L));
        assertEquals(0L,
                PlaybackPrebufferPolicy.resolveRangeAwareRequiredBytes(
                        Long.MAX_VALUE, -1L, 0L, 1L, 2L));
    }

    @Test
    public void hundredThousandSeekTargetsNeverOverflowOrExceedRemainingMedia() {
        long total = 20_327_632_348L;
        long configured = 96L * MB;
        for (int seek = 0; seek < 100_000; seek++) {
            long anchor = ((long) seek * 13_771_337L) % total;
            long required = PlaybackPrebufferPolicy.resolveRequiredBytes(configured, total, anchor);
            assertTrue(required >= 0L);
            assertTrue(required <= configured);
            assertTrue(required <= total - anchor);
        }
    }

    @Test
    public void twoHundredTwentyGiBMediaOffsetsRemainLongAndBounded() {
        long total = 220L * 1024L * 1024L * 1024L;
        long anchor = total - 48L * MB;

        assertEquals(48L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                96L * MB, total, anchor));
        assertEquals(96L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                96L * MB, total, total / 2L));
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

    @Test
    public void forwardAndBackwardSeeksBothReplaceTheOldBufferAnchor() {
        long oldPosition = 900L * MB;
        assertEquals(1_200L * MB, PlaybackPrebufferPolicy.resolveSeekAnchor(
                true, 1_200L * MB, oldPosition));
        assertEquals(600L * MB, PlaybackPrebufferPolicy.resolveSeekAnchor(
                true, 600L * MB, oldPosition));
        assertEquals(oldPosition, PlaybackPrebufferPolicy.resolveSeekAnchor(
                false, 600L * MB, oldPosition));
    }

    @Test
    public void nonZeroInitialOpenAnchorsTheFirstMatchingReadEvenWithoutSeekFlag() {
        assertTrue(PlaybackPrebufferPolicy.shouldAnchorInitialNonZeroRead(
                false, false, 88_000L, 88_000L));
        assertTrue(!PlaybackPrebufferPolicy.shouldAnchorInitialNonZeroRead(
                false, false, 88_000L, 88_001L));
        assertTrue(!PlaybackPrebufferPolicy.shouldAnchorInitialNonZeroRead(
                false, true, 88_000L, 88_000L));
        assertTrue(!PlaybackPrebufferPolicy.shouldAnchorInitialNonZeroRead(
                true, false, 88_000L, 88_000L));
    }

    @Test
    public void startupAnchorStaysAtMidFileUntilRunwayIsVerified() {
        long anchor = 40L * MB;
        assertEquals(anchor, PlaybackPrebufferPolicy.resolveStartupBufferAnchor(
                false, anchor, anchor + 3L * MB));
        long sourceStartupTarget = 48L * MB - 128L;
        assertTrue(!PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                false, sourceStartupTarget - 1L, sourceStartupTarget));
        assertTrue(PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                false, 2L * (24L * MB - 8L), sourceStartupTarget));
        assertTrue(PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                false, 48L * MB - 72L, sourceStartupTarget));
        assertTrue(PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                false, sourceStartupTarget, sourceStartupTarget));
        assertTrue(PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                true, 0L, 96L * MB));
    }

    @Test
    public void cacheBudgetIsBoundedByProcessMemoryAndArchitecture() {
        for (boolean is32Bit : new boolean[]{true, false}) {
            assertEquals(120L * MB, PlaybackPrebufferPolicy.resolveCacheLimitBytes(0L, is32Bit));
            assertEquals(120L * MB, PlaybackPrebufferPolicy.resolveCacheLimitBytes(-1L, is32Bit));
            assertEquals(120L * MB, PlaybackPrebufferPolicy.resolveCacheLimitBytes(192L * MB, is32Bit));
            assertEquals(120L * MB, PlaybackPrebufferPolicy.resolveCacheLimitBytes(256L * MB, is32Bit));
            assertEquals(192L * MB, PlaybackPrebufferPolicy.resolveCacheLimitBytes(384L * MB, is32Bit));
            assertEquals(192L * MB, PlaybackPrebufferPolicy.resolveCacheLimitBytes(512L * MB, is32Bit));
            assertEquals(192L * MB, PlaybackPrebufferPolicy.resolveCacheLimitBytes(Long.MAX_VALUE, is32Bit));
        }
    }

    @Test
    public void everyHeapAndAbiUsesTheSameNinetySixMiBStartupRunway() {
        for (long maxHeap : new long[]{-1L, 0L, 192L * MB, 256L * MB,
                384L * MB, 512L * MB, Long.MAX_VALUE}) {
            assertEquals(96L * MB, PlaybackPrebufferPolicy.resolveStartBufferBytes(maxHeap));
        }
        long required = PlaybackPrebufferPolicy.resolveStartBufferBytes(512L * MB);
        assertTrue(!PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(false, required - 1L, required));
        assertTrue(PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(false, required, required));
    }

    @Test
    public void eightTwentyFourMiBWindowsFitTheGlobalLimitButANinthDoesNot() {
        long window = 24L * MB;
        long limit = PlaybackPrebufferPolicy.resolveCacheLimitBytes(512L * MB, true);
        assertEquals(192L * MB, limit);
        assertTrue(PlaybackPrebufferPolicy.canLoadNextWindow(7L * window, limit, window, false));
        assertTrue(!PlaybackPrebufferPolicy.canLoadNextWindow(8L * window, limit, window, false));
    }

    @Test
    public void fullCacheStopsResubmittingEmptyPrefetchTasks() {
        assertTrue(!PlaybackPrebufferPolicy.shouldScheduleFillAhead(
                true, 120L * MB, 120L * MB, 48L * MB, 120L * MB));
        assertTrue(PlaybackPrebufferPolicy.shouldScheduleFillAhead(
                true, 72L * MB, 120L * MB, 72L * MB, 120L * MB));
        assertTrue(!PlaybackPrebufferPolicy.shouldScheduleFillAhead(
                true, 72L * MB, 120L * MB, 120L * MB, 120L * MB));
        assertTrue(!PlaybackPrebufferPolicy.shouldScheduleFillAhead(
                false, 0L, 120L * MB, 0L, 120L * MB));
        assertTrue(!PlaybackPrebufferPolicy.shouldScheduleFillAhead(
                true, 72L * MB, 72L * MB, 48L * MB, 72L * MB, false));
        assertTrue(PlaybackPrebufferPolicy.shouldScheduleFillAhead(
                true, 72L * MB, 72L * MB, 48L * MB, 72L * MB, true));
    }

    @Test
    public void nextWindowMayCompleteTheBudgetButCannotGrowUnbounded() {
        long providerWindow = 24L * MB - 8L;
        long afterTwoProviderWindows = providerWindow * 2L;
        long afterThreeProviderWindows = providerWindow * 3L;
        assertTrue(PlaybackPrebufferPolicy.canLoadNextWindow(
                afterTwoProviderWindows, 72L * MB, 24L * MB, false));
        assertTrue(afterThreeProviderWindows >= 72L * MB - 64L);
        assertTrue(!PlaybackPrebufferPolicy.canLoadNextWindow(
                afterThreeProviderWindows, 72L * MB, 24L * MB, false));
        assertTrue(!PlaybackPrebufferPolicy.canLoadNextWindow(
                72L * MB, 72L * MB, 24L * MB, false));
        assertTrue(PlaybackPrebufferPolicy.canLoadNextWindow(
                72L * MB, 72L * MB, 24L * MB, true));
    }
}
