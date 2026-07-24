package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemCodecSurfacePolicyTest {
    @Test
    public void sameHolderIsNotRebound() {
        Object holder = new Object();

        assertTrue(SystemCodecSurfacePolicy.isSameHolder(holder, holder));
        assertFalse(SystemCodecSurfacePolicy.isSameHolder(holder, new Object()));
        assertFalse(SystemCodecSurfacePolicy.isSameHolder(holder, null));
    }

    @Test
    public void directSurfaceUsesIdentity() {
        Object surface = new Object();

        assertTrue(SystemCodecSurfacePolicy.isSameDirectSurface(surface, surface));
        assertFalse(SystemCodecSurfacePolicy.isSameDirectSurface(surface, new Object()));
        assertFalse(SystemCodecSurfacePolicy.isSameDirectSurface(surface, null));
    }

    @Test
    public void nvtDecodersAlwaysUseSafeSurfaceReplacement() {
        assertTrue(SystemCodecSurfacePolicy.requiresSetOutputSurfaceWorkaround(
                "OMX.NVT.Video.Decoder.avc"));
        assertTrue(SystemCodecSurfacePolicy.requiresSetOutputSurfaceWorkaround(
                "c2.nvt.video.decoder.hevc"));
        assertFalse(SystemCodecSurfacePolicy.requiresSetOutputSurfaceWorkaround(
                "OMX.google.h264.decoder"));
        assertFalse(SystemCodecSurfacePolicy.requiresSetOutputSurfaceWorkaround(null));
    }
}
