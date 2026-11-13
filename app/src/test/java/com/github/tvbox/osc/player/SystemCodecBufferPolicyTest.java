package com.github.tvbox.osc.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SystemCodecBufferPolicyTest {
    private static final int MB = 1024 * 1024;

    @Test
    public void targetBufferIsCappedForLowMemoryDevices() {
        assertEquals(48 * MB, SystemCodecBufferPolicy.targetBufferBytes(128));
        assertEquals(48 * MB, SystemCodecBufferPolicy.targetBufferBytes(256));
        assertEquals(72 * MB, SystemCodecBufferPolicy.targetBufferBytes(1024));
        assertEquals(96 * MB, SystemCodecBufferPolicy.MIN_BUFFER_BYTES);
        assertEquals(72 * MB, SystemCodecBufferPolicy.MAX_BUFFER_BYTES);
        assertTrue(SystemCodecBufferPolicy.MAX_BUFFER_BYTES <= 192 * MB - 120 * MB);
    }

    @Test
    public void progressByteTargetIsBoundedForTheTvHeap() {
        assertEquals(96 * MB, SystemCodecBufferPolicy.bufferProgressTargetBytes(128));
        assertEquals(96 * MB, SystemCodecBufferPolicy.bufferProgressTargetBytes(1024));
    }

    @Test
    public void playbackThresholdsKeepAValidLargePrebuffer() {
        assertEquals(4_000, SystemCodecBufferPolicy.PLAYBACK_BUFFER_MS);
        assertEquals(5_000, SystemCodecBufferPolicy.REBUFFER_MS);
        assertTrue(SystemCodecBufferPolicy.MIN_BUFFER_MS
                >= SystemCodecBufferPolicy.PLAYBACK_BUFFER_MS);
        assertTrue(SystemCodecBufferPolicy.MIN_BUFFER_MS
                >= SystemCodecBufferPolicy.REBUFFER_MS);
        assertTrue(SystemCodecBufferPolicy.MAX_BUFFER_MS
                >= SystemCodecBufferPolicy.MIN_BUFFER_MS);
        assertEquals(Integer.MAX_VALUE, SystemCodecBufferPolicy.MAX_BUFFER_MS);
        assertTrue(!SystemCodecBufferPolicy.PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS);
    }

    @Test
    public void loadControlConstructsWithPlaybackThresholdBelowMinimumBuffer() {
        SystemCodecLoadControl loadControl = new SystemCodecLoadControl(
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                SystemCodecBufferPolicy.MIN_BUFFER_MS,
                SystemCodecBufferPolicy.MAX_BUFFER_MS,
                SystemCodecBufferPolicy.MIN_BUFFER_BYTES,
                SystemCodecBufferPolicy.MAX_BUFFER_BYTES,
                true,
                requiredBytes -> false,
                () -> false);

        assertNotNull(loadControl);
    }

    @Test
    public void sourcePolicyCanDisableByteGateForLiveAndReenableItForVod() {
        SystemCodecLoadControl loadControl = new SystemCodecLoadControl(
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                SystemCodecBufferPolicy.MIN_BUFFER_MS,
                SystemCodecBufferPolicy.MAX_BUFFER_MS,
                SystemCodecBufferPolicy.MIN_BUFFER_BYTES,
                SystemCodecBufferPolicy.MAX_BUFFER_BYTES,
                true,
                requiredBytes -> false,
                () -> false);
        loadControl.setSourcePolicy(false, true);
        assertTrue(!loadControl.shouldWaitForByteBuffer());
        loadControl.setSourcePolicy(true, false);
        assertTrue(loadControl.shouldWaitForByteBuffer());
    }

    @Test
    public void dynamicTimelineDoesNotOverrideRangeVodByteGate() {
        assertTrue(!SystemCodecLoadControl.shouldUseTimePolicyForTimeline(true, true));
        assertTrue(SystemCodecLoadControl.shouldUseTimePolicyForTimeline(false, true));
        assertTrue(!SystemCodecLoadControl.shouldUseTimePolicyForTimeline(true, false));
    }

    @Test
    public void rangeGateCannotBypassExoSampleQueueReadiness() {
        // Exercise the real ExoPlayer policy: a raw Range runway is necessary but does not
        // replace the decoded sample-queue runway. This prevents 1-3 second READY/BUFFERING
        // oscillation after the byte cache reaches its startup target.
        AtomicBoolean rangeReady = new AtomicBoolean(true);
        SystemCodecLoadControl loadControl = new SystemCodecLoadControl(
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                SystemCodecBufferPolicy.MIN_BUFFER_MS,
                SystemCodecBufferPolicy.MAX_BUFFER_MS,
                SystemCodecBufferPolicy.MIN_BUFFER_BYTES,
                SystemCodecBufferPolicy.MAX_BUFFER_BYTES,
                true,
                requiredBytes -> rangeReady.get(),
                () -> false);

        assertTrue(!loadControl.shouldStartPlayback(Timeline.EMPTY, null,
                3_000_000L, 1f, false, C.TIME_UNSET));
        assertTrue(loadControl.shouldStartPlayback(Timeline.EMPTY, null,
                4_000_000L, 1f, false, C.TIME_UNSET));
        assertTrue(!loadControl.shouldStartPlayback(Timeline.EMPTY, null,
                4_000_000L, 1f, true, C.TIME_UNSET));
        assertTrue(loadControl.shouldStartPlayback(Timeline.EMPTY, null,
                5_000_000L, 1f, true, C.TIME_UNSET));

        rangeReady.set(false);
        assertTrue(!loadControl.shouldStartPlayback(Timeline.EMPTY, null,
                30_000_000L, 1f, false, C.TIME_UNSET));

        // Reaching the allocator target must not deadlock startup. Exo's size threshold is a
        // bounded fallback for sources whose decoded queue is still short.
        int allocatorTargetBytes = C.DEFAULT_BUFFER_SEGMENT_SIZE;
        DefaultAllocator fullAllocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        SystemCodecLoadControl fullLoadControl = new SystemCodecLoadControl(
                fullAllocator,
                SystemCodecBufferPolicy.MIN_BUFFER_MS,
                SystemCodecBufferPolicy.MAX_BUFFER_MS,
                SystemCodecBufferPolicy.MIN_BUFFER_BYTES,
                allocatorTargetBytes,
                true,
                requiredBytes -> true,
                () -> false);
        com.google.android.exoplayer2.upstream.Allocation allocation = fullAllocator.allocate();
        assertTrue(fullLoadControl.shouldStartPlayback(Timeline.EMPTY, null,
                1_000_000L, 1f, false, C.TIME_UNSET));
        fullAllocator.release(allocation);
    }

    @Test
    public void normalVodNeedsFreshBufferBytesButShortFilesCanUseEofEvidence() {
        assertTrue(!SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 95L * MB, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(!SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 96L * MB - 1L, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(SystemCodecBufferPolicy.hasRequiredBuffer(
                96L * MB, 0L, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 96L * MB, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        // Generic policy cannot forgive missing bytes; verified malformed loopback handling
        // belongs to the Range source instead of weakening all HTTP/WebDAV startup gates.
        assertTrue(!SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 12L * (8L * MB - 8L), SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
    }

    @Test
    public void cacheAndSampleQueueEvidenceAreNeverAddedTogether() {
        assertTrue(!SystemCodecBufferPolicy.hasRequiredBuffer(
                60L * MB, 60L * MB, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(SystemCodecBufferPolicy.hasRequiredBuffer(
                96L * MB, 0L, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 96L * MB, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
    }

    @Test
    public void staleSampleQueueEofCannotBypassFreshVodRunwayAfterSeek() {
        AtomicBoolean bufferedToEnd = new AtomicBoolean(true);
        AtomicBoolean verifiedRangeTail = new AtomicBoolean(false);
        SystemCodecLoadControl loadControl = new SystemCodecLoadControl(
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                SystemCodecBufferPolicy.MIN_BUFFER_MS,
                SystemCodecBufferPolicy.MAX_BUFFER_MS,
                SystemCodecBufferPolicy.MIN_BUFFER_BYTES,
                SystemCodecBufferPolicy.MAX_BUFFER_BYTES,
                true,
                requiredBytes -> verifiedRangeTail.get(),
                bufferedToEnd::get);

        assertTrue(!loadControl.hasRequiredBufferNow());
        // The Range source independently validates the actual remaining bytes at a short
        // file/tail. That proof is sufficient even though it is smaller than 96 MiB.
        verifiedRangeTail.set(true);
        assertTrue(loadControl.hasRequiredBufferNow());
    }

    @Test
    public void bufferingProgressUsesTimeOrBytesAndDoesNotClaimReadyEarly() {
        assertEquals(0, SystemCodecBufferPolicy.bufferingProgressPercent(
                7_500L, 15_000L, 0L, 64L * MB, false));
        assertEquals(75, SystemCodecBufferPolicy.bufferingProgressPercent(
                0L, 15_000L, 54L * MB, 72L * MB, false));
        assertEquals(100, SystemCodecBufferPolicy.bufferingProgressPercent(
                20_000L, 15_000L, 80L * MB, 64L * MB, false));
        assertEquals(100, SystemCodecBufferPolicy.bufferingProgressPercent(
                0L, 15_000L, 0L, 64L * MB, true));
        assertEquals(50, SystemCodecBufferPolicy.bufferingProgressPercent(
                7_500L, 15_000L, 0L, 0L, false));
    }

    @Test
    public void forwardSeekRetainsEpisodeOnlyInsideReadyBufferedInterval() {
        assertTrue(SystemCodecBufferPolicy.shouldRetainForwardSeek(
                true, true, 10_000L, 20_000L, 30_000L));
        assertTrue(!SystemCodecBufferPolicy.shouldRetainForwardSeek(
                true, true, 10_000L, 30_000L, 30_000L));
        assertTrue(!SystemCodecBufferPolicy.shouldRetainForwardSeek(
                true, true, 20_000L, 10_000L, 30_000L));
        assertTrue(!SystemCodecBufferPolicy.shouldRetainForwardSeek(
                false, true, 10_000L, 20_000L, 30_000L));
        assertTrue(!SystemCodecBufferPolicy.shouldRetainForwardSeek(
                true, false, 10_000L, 20_000L, 30_000L));
    }

    @Test
    public void seekBufferGateRequiresFresh96MiBAndAcceptsBufferedEofSeparately() {
        assertTrue(!SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 95L * MB, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(!SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 96L * MB - 1L, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(SystemCodecBufferPolicy.hasRequiredBuffer(
                0L, 96L * MB, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
        assertTrue(SystemCodecBufferPolicy.hasRequiredBuffer(
                96L * MB, 0L, SystemCodecBufferPolicy.MIN_BUFFER_BYTES));
    }
}
