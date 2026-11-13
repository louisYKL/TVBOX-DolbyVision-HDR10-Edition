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
    public void dolbyVisionBaseLayerClassificationUsesCompatibilityId() {
        assertTrue(SystemDecoderRoutePolicy.hasHdr10BaseLayer(7, -1));
        assertTrue(SystemDecoderRoutePolicy.hasHdr10BaseLayer(8, 1));
        assertTrue(SystemDecoderRoutePolicy.hasHlgBaseLayer(8, 4));
        assertTrue(SystemDecoderRoutePolicy.hasSdrBaseLayer(8, 2));
        assertEquals("base-sdr", SystemDecoderRoutePolicy.dolbyVisionBaseMode(9, -1, -1));
        assertFalse(SystemDecoderRoutePolicy.hasHdr10BaseLayer(5, -1));
        assertFalse(SystemDecoderRoutePolicy.hasHdr10BaseLayer(8, 4));
        assertEquals("base-hlg", SystemDecoderRoutePolicy.dolbyVisionBaseMode(8, 4, 6));
        assertEquals("base-sdr", SystemDecoderRoutePolicy.dolbyVisionBaseMode(8, 2, 6));
        assertEquals("dv-no-compatible-base", SystemDecoderRoutePolicy.dolbyVisionBaseMode(5, 0, 6));
        assertEquals("dv-no-compatible-base", SystemDecoderRoutePolicy.dolbyVisionBaseMode(8, -1, -1));
    }

    @Test
    public void nativeDolbyVisionRequiresHardwareDecodeAndDolbyVisionOutput() {
        assertTrue(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(true, true, true));
        assertFalse(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(true, false, true));
        assertFalse(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(true, true, false));
        assertFalse(SystemDecoderRoutePolicy.shouldUseNativeDolbyVision(false, true, true));
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
    public void hdrVendorAliasesWithMissingHardwareFlagRemainEligibleButSoftwareDoesNot() {
        assertTrue(SystemDecoderRoutePolicy.isEligibleHdrVideoCodec(
                33, "c2.qti.dv.decoder", false, false));
        assertTrue(SystemDecoderRoutePolicy.isEligibleHdrVideoCodec(
                33, "OMX.dolby.vision.decoder", false, false));
        assertFalse(SystemDecoderRoutePolicy.isEligibleHdrVideoCodec(
                33, "c2.qti.dv.decoder", false, true));
        assertFalse(SystemDecoderRoutePolicy.isEligibleHdrVideoCodec(
                33, "c2.android.hevc.decoder", false, false));
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
