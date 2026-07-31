package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemCodecRenderingPolicyTest {
    @Test
    public void renderedFrameCanStartWhileNativeStateIsBuffering() {
        // A native playing signal is sufficient even when the state callback lags.
        assertTrue(SystemCodecRenderingPolicy.shouldNotify(
                false, false, true, true, true, true, true, false));
    }

    @Test
    public void videoTrackStillWaitsForARealFrame() {
        assertFalse(SystemCodecRenderingPolicy.shouldNotify(
                false, false, true, true, true, false, true, false));
    }

    @Test
    public void staleOrPausedPlaybackCannotEmitRenderingStart() {
        assertFalse(SystemCodecRenderingPolicy.shouldNotify(
                true, false, true, true, true, true, true, false));
        assertFalse(SystemCodecRenderingPolicy.shouldNotify(
                false, true, true, true, true, true, true, false));
        assertFalse(SystemCodecRenderingPolicy.shouldNotify(
                false, false, false, true, true, true, true, false));
    }

    @Test
    public void audioOnlyTrackCanStartWithoutVideoFrame() {
        assertTrue(SystemCodecRenderingPolicy.shouldNotify(
                false, false, true, false, true, false, true, false));
    }

    @Test
    public void firstFrameAloneCannotStartPlayback() {
        assertFalse(SystemCodecRenderingPolicy.shouldNotify(
                false, false, true, true, true, true, false, false));
    }

    @Test
    public void advancingPositionCanStartWhenNativeFlagLags() {
        assertTrue(SystemCodecRenderingPolicy.shouldNotify(
                false, false, true, true, true, true, false, true));
    }
}
