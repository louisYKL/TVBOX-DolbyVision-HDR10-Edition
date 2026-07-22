package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemDecoderRoutePolicyTest {
    @Test
    public void ordinaryTvVodKeepsRequestedSystemHardwareDecoder() {
        assertTrue(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false, true));
    }

    @Test
    public void java64AndDolbyVisionRemainOnTheirOwnRoutePolicies() {
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(true, false, true));
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, true, true));
    }

    @Test
    public void explicitCompatSelectionIsNotOverridden() {
        assertFalse(SystemDecoderRoutePolicy.shouldForceSystemDecoder(false, false, false));
    }
}
