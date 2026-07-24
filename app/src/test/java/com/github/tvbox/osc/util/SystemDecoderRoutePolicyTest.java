package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemDecoderRoutePolicyTest {
    @Test
    public void everyBuiltInRouteResolvesToTheSystemCodecPlayer() {
        int[] requestedTypes = {0, 6, 10, 13};
        int[] routedTypes = {0, 6};
        for (int requestedType : requestedTypes) {
            for (int routedType : routedTypes) {
                assertEquals(PlayerHelper.PLAYER_TYPE_SYSTEM,
                        SystemDecoderRoutePolicy.resolvePlayerType(
                                requestedType, routedType, false));
                assertEquals(PlayerHelper.PLAYER_TYPE_SYSTEM,
                        SystemDecoderRoutePolicy.resolvePlayerType(
                                requestedType, routedType, true));
            }
        }
    }

    @Test
    public void nativeDolbyVisionRequiresHardwareDecodeAndDolbyVisionOutput() {
        assertTrue(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(true, true, true));
        assertFalse(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(true, false, true));
        assertFalse(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(true, true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(false, true, true));
    }

    @Test
    public void unsupportedDolbyVisionUsesTheHdr10BaseLayer() {
        assertFalse(SystemDecoderRoutePolicy.shouldUseHdr10BaseLayer(true, true, true));
        assertTrue(SystemDecoderRoutePolicy.shouldUseHdr10BaseLayer(true, false, true));
        assertTrue(SystemDecoderRoutePolicy.shouldUseHdr10BaseLayer(true, true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldUseHdr10BaseLayer(false, false, false));
    }

    @Test
    public void videoRouteRejectsSoftwareDecoders() {
        assertTrue(SystemDecoderRoutePolicy.isEligibleHardwareVideoDecoder(false));
        assertFalse(SystemDecoderRoutePolicy.isEligibleHardwareVideoDecoder(true));
    }

    @Test
    public void legacyCodecNamesSeparateVendorHardwareFromSoftware() {
        assertTrue(SystemDecoderRoutePolicy.isLikelyHardwareCodec(
                28, "OMX.NVT.video.decoder.hevc", false, false));
        assertTrue(SystemDecoderRoutePolicy.isLikelyHardwareCodec(
                28, "OMX.hisi.audio.decoder.dts", false, true));
        assertFalse(SystemDecoderRoutePolicy.isLikelyHardwareCodec(
                28, "OMX.google.h264.decoder", false, false));
        assertFalse(SystemDecoderRoutePolicy.isLikelyHardwareCodec(
                28, "c2.android.hevc.decoder", false, false));
    }

    @Test
    public void api29UsesReportedHardwareFlags() {
        assertTrue(SystemDecoderRoutePolicy.isLikelyHardwareCodec(
                29, "c2.vendor.decoder", true, false));
        assertFalse(SystemDecoderRoutePolicy.isLikelyHardwareCodec(
                29, "c2.vendor.decoder", false, false));
        assertFalse(SystemDecoderRoutePolicy.isLikelyHardwareCodec(
                29, "c2.vendor.decoder", true, true));
    }

    @Test
    public void platformHardwareAudioStaysAheadOfSoftwareAndFfmpeg() {
        assertEquals(0, SystemDecoderRoutePolicy.platformAudioDecoderPriority(false));
        assertEquals(1, SystemDecoderRoutePolicy.platformAudioDecoderPriority(true));
        assertEquals(0, SystemDecoderRoutePolicy.platformAudioDecoderPriority(
                28, "OMX.hisi.audio.decoder.dts", false, true));
        assertEquals(1, SystemDecoderRoutePolicy.platformAudioDecoderPriority(
                28, "OMX.google.aac.decoder", false, true));
        assertFalse(SystemDecoderRoutePolicy.shouldUseFfmpegAudioFallback(true, true));
        assertTrue(SystemDecoderRoutePolicy.shouldUseFfmpegAudioFallback(false, true));
        assertFalse(SystemDecoderRoutePolicy.shouldUseFfmpegAudioFallback(false, false));
    }
}
