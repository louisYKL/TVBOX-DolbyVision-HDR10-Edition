package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemDecoderRoutePolicyTest {
    @Test
    public void ordinaryTvVodForcesSystemHardwareDecoder() {
        assertTrue(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false, false, true));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, true, false, true));
    }

    @Test
    public void java64RemainsOnItsOwnRoutePolicy() {
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(true, false, false, true));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(true, true, false, true));
    }

    @Test
    public void tvDtsAndTrueHdUseHardwareVideoPcmAudioCompatibilityPath() {
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmAudioDecoder(false, true, false, false, false, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmAudioDecoder(false, false, true, false, false, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmAudioDecoder(false, false, false, true, false, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmAudioDecoder(false, false, false, false, true, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmAudioDecoder(false, false, false, false, false, true));
        assertFalse(SystemDecoderRoutePolicy.requiresCompatPcmAudioDecoder(false, false, false, false, false, false));
        assertFalse(SystemDecoderRoutePolicy.requiresCompatPcmAudioDecoder(true, true, true, true, true, true));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false, true, true));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false, false, false));
    }

    @Test
    public void unsupportedAudioRetriesOnceOnlyAfterSystemFailure() {
        assertTrue(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, true, false, false, false));
        assertTrue(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, false, true, false, false));
        assertTrue(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, false, false, true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, false, false, false, false));
        assertFalse(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, true, false, true, true));
        assertTrue(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                true, true, false, false, true, false));
    }

    @Test
    public void forcedRouteReplacesStaleCompatibilitySelection() {
        assertTrue(SystemDecoderRoutePolicy.resolvePlayerType(
                PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT,
                PlayerHelper.PLAYER_TYPE_SYSTEM,
                true) == PlayerHelper.PLAYER_TYPE_SYSTEM);
    }

    @Test
    public void nonForcedRoutePreservesRequestedPlayer() {
        assertTrue(SystemDecoderRoutePolicy.resolvePlayerType(
                PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT,
                PlayerHelper.PLAYER_TYPE_SYSTEM,
                false) == PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT);
    }
}
