package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioOutputRoutePolicyTest {
    @Test
    public void unsupportedTv32LocalProxyAudioIsDecoded() {
        assertTrue(AudioOutputRoutePolicy.shouldForceTv32LocalProxyPcm(true, false));
    }

    @Test
    public void supportedTv32LocalProxyAudioCanPassthrough() {
        assertFalse(AudioOutputRoutePolicy.shouldForceTv32LocalProxyPcm(true, true));
    }

    @Test
    public void otherRoutesKeepTheirExistingAudioPolicy() {
        assertFalse(AudioOutputRoutePolicy.shouldForceTv32LocalProxyPcm(false, false));
    }
}
