package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BufferingProgressPolicyTest {
    @Test
    public void percentIsAlwaysBounded() {
        assertEquals(0, BufferingProgressPolicy.clampPercent(-1));
        assertEquals(37, BufferingProgressPolicy.clampPercent(37));
        assertEquals(100, BufferingProgressPolicy.clampPercent(140));
    }

    @Test
    public void directUriProgressUsesRealReceivedBytesAndStaysBelowComplete() {
        long mb = 1024L * 1024L;
        assertEquals(0, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                true, 100L, 100L, 24L * mb, 0, 0));
        assertEquals(50, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                true, 100L, 100L + 12L * mb, 24L * mb, 0, 0));
        assertEquals(99, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                true, 100L, 100L + 30L * mb, 24L * mb, 100, 50));
    }

    @Test
    public void directUriProgressIsMonotonicAndFallsBackToNativeWhenUnavailable() {
        assertEquals(42, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                true, 1_000L, 900L, 24L * 1024L * 1024L, 42, 80));
        assertEquals(80, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                true, 1_000L, 1_100L, 24L * 1024L * 1024L, 20, 80));
        assertEquals(-1, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                true, -1L, -1L, 24L * 1024L * 1024L, 0, 0));
        assertEquals(27, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                true, -1L, -1L, 24L * 1024L * 1024L, 0, 27));
        assertEquals(37, BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                false, -1L, -1L, 0L, 37, 80));
    }

    @Test
    public void rangeStartupProgressUsesTheActualPrebufferAndNeverClaimsCompletionEarly() {
        long mb = 1024L * 1024L;
        assertEquals(0, BufferingProgressPolicy.resolveRangePrebufferDisplayedPercent(
                true, 0L, 24L * mb, 0));
        assertEquals(50, BufferingProgressPolicy.resolveRangePrebufferDisplayedPercent(
                true, 12L * mb, 24L * mb, 0));
        assertEquals(99, BufferingProgressPolicy.resolveRangePrebufferDisplayedPercent(
                true, 25L * mb, 24L * mb, 50));
        assertEquals(0, BufferingProgressPolicy.resolveRangePrebufferDisplayedPercent(
                true, 25L * mb, 0L, 50));
        assertFalse(BufferingProgressPolicy.isRangePrebufferReady(23L * mb, 24L * mb));
        assertTrue(BufferingProgressPolicy.isRangePrebufferReady(24L * mb, 24L * mb));
    }

    @Test
    public void nativeBufferIsTheOnlyReasonToHoldBufferingEnd() {
        assertTrue(BufferingProgressPolicy.shouldHoldBufferingEnd(true));
        assertFalse(BufferingProgressPolicy.shouldHoldBufferingEnd(false));
    }

    @Test
    public void firstFrameWatchdogOnlyDefersBeforeAbsoluteDeadline() {
        assertTrue(BufferingProgressPolicy.shouldDeferFirstFrameFailure(
                true, 10, 10, 999L, 1_000L));
        assertTrue(BufferingProgressPolicy.shouldDeferFirstFrameFailure(
                false, 10, 11, 999L, 1_000L));
        assertFalse(BufferingProgressPolicy.shouldDeferFirstFrameFailure(
                false, 10, 10, 999L, 1_000L));
        assertFalse(BufferingProgressPolicy.shouldDeferFirstFrameFailure(
                true, 10, 11, 1_000L, 1_000L));
        assertFalse(BufferingProgressPolicy.shouldDeferFirstFrameFailure(
                true, 10, 11, 1_001L, 1_000L));
    }
}
