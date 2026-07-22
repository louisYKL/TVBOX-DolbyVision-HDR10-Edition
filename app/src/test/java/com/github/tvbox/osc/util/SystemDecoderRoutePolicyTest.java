package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemDecoderRoutePolicyTest {
    @Test
    public void ordinaryTvVodForcesSystemHardwareDecoder() {
        assertTrue(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false));
    }

    @Test
    public void java64AndDolbyVisionRemainOnTheirOwnRoutePolicies() {
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, true));
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
