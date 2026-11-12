package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SystemCodecBufferPolicyTest {
    private static final int MB = 1024 * 1024;

    @Test
    public void targetBufferIsCappedForLowMemoryDevices() {
        assertEquals(16 * MB, SystemCodecBufferPolicy.targetBufferBytes(128));
        assertEquals(32 * MB, SystemCodecBufferPolicy.targetBufferBytes(256));
        assertEquals(32 * MB, SystemCodecBufferPolicy.targetBufferBytes(1024));
    }

    @Test
    public void progressByteTargetIsBoundedForTheTvHeap() {
        assertEquals(16 * MB, SystemCodecBufferPolicy.bufferProgressTargetBytes(128));
        assertEquals(32 * MB, SystemCodecBufferPolicy.bufferProgressTargetBytes(1024));
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
        assertEquals(30_000, SystemCodecBufferPolicy.MAX_BUFFER_MS);
        assertTrue(!SystemCodecBufferPolicy.PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS);
    }

    @Test
    public void bufferingProgressUsesTimeOrBytesAndDoesNotClaimReadyEarly() {
        assertEquals(50, SystemCodecBufferPolicy.bufferingProgressPercent(
                7_500L, 15_000L, 0L, 64L * MB, false));
        assertEquals(75, SystemCodecBufferPolicy.bufferingProgressPercent(
                0L, 15_000L, 48L * MB, 64L * MB, false));
        assertEquals(99, SystemCodecBufferPolicy.bufferingProgressPercent(
                20_000L, 15_000L, 80L * MB, 64L * MB, false));
        assertEquals(100, SystemCodecBufferPolicy.bufferingProgressPercent(
                0L, 15_000L, 0L, 64L * MB, true));
    }
}
