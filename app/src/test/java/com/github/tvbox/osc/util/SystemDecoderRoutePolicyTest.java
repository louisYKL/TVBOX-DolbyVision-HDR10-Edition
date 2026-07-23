package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemDecoderRoutePolicyTest {
    @Test
    public void ordinaryTvVodForcesSystemHardwareDecoder() {
        assertTrue(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false, false));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, true, false));
    }

    @Test
    public void java64RemainsOnItsOwnRoutePolicy() {
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(true, false, false));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(true, true, false));
    }

    @Test
    public void encodedAudioUsesPcmCompatibilityBeforeStart() {
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false, true));
    }

    @Test
    public void everyKnownEncodedAudioFormatRequiresPcmOutput() {
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmOutput(true, false, false, false, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmOutput(false, true, false, false, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmOutput(false, false, true, false, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmOutput(false, false, false, true, false));
        assertTrue(SystemDecoderRoutePolicy.requiresCompatPcmOutput(false, false, false, false, true));
        assertFalse(SystemDecoderRoutePolicy.requiresCompatPcmOutput(false, false, false, false, false));
    }

    @Test
    public void onlyConfirmedSingleLayerDolbyVisionMayLeaveTheTvSystemRoute() {
        assertTrue(SystemDecoderRoutePolicy.isConfirmedSingleLayerDolbyVision(true, 5, false));
        assertTrue(SystemDecoderRoutePolicy.isConfirmedSingleLayerDolbyVision(true, -1, false));
        assertFalse(SystemDecoderRoutePolicy.isConfirmedSingleLayerDolbyVision(true, 7, true));
        assertFalse(SystemDecoderRoutePolicy.isConfirmedSingleLayerDolbyVision(true, 8, false));
        assertFalse(SystemDecoderRoutePolicy.isConfirmedSingleLayerDolbyVision(false, -1, false));
    }

    @Test
    public void nativeAudioDecoderFailureRetriesCompatExactlyOnce() {
        assertTrue(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, false, true, false));
        assertTrue(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, true, true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, true, true, true));
        assertTrue(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                true, true, false, true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, false, false, true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldRetryWithCompatPcmAfterSystemFailure(
                false, true, false, false, false));
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
