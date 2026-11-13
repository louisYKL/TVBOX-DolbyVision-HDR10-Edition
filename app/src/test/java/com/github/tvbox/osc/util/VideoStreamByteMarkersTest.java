package com.github.tvbox.osc.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VideoStreamByteMarkersTest {
    @Test
    public void identifiesAllDynamicRangeMarkersInMixedCaseBinaryMetadata() {
        byte[] data = bytes("\u0000\u00ffMatroska\u0000V_MPEGH/ISO/HEVC\u0000DOVI Configuration DV_PROFILE=8 "
                + "COLOR-TRANSFER=6 SMPTE 2094 HDR10_PLUS\u0000");
        VideoStreamByteMarkers.Scan scan = VideoStreamByteMarkers.scan(data, data.length);
        assertTrue(scan.has(VideoStreamByteMarkers.MATROSKA));
        assertTrue(scan.has(VideoStreamByteMarkers.HEVC));
        assertTrue(scan.has(VideoStreamByteMarkers.DOLBY_VISION));
        assertTrue(scan.has(VideoStreamByteMarkers.HDR10));
        assertTrue(scan.has(VideoStreamByteMarkers.HDR10_PLUS));
        assertEquals(8, scan.dolbyVisionProfile);
    }

    @Test
    public void recognizesMarkerAtLargeBufferEndAndHonorsValidSampleLength() {
        byte[] data = new byte[8 * 1024 * 1024];
        Arrays.fill(data, (byte) 0xff);
        byte[] marker = bytes("hVc1 SMPTE-ST-2084 HDR10+ dvh1.08.06");
        System.arraycopy(marker, 0, data, data.length - marker.length, marker.length);
        VideoStreamByteMarkers.Scan scan = VideoStreamByteMarkers.scan(data, data.length);
        assertTrue(scan.has(VideoStreamByteMarkers.HEVC));
        assertTrue(scan.has(VideoStreamByteMarkers.HDR10));
        assertTrue(scan.has(VideoStreamByteMarkers.HDR10_PLUS));
        assertTrue(scan.has(VideoStreamByteMarkers.DOLBY_VISION));
        assertEquals(8, scan.dolbyVisionProfile);
        assertFalse(VideoStreamByteMarkers.contains(data, data.length - marker.length,
                VideoStreamByteMarkers.DOLBY_VISION));
        assertFalse(VideoStreamByteMarkers.scan(data, data.length - marker.length)
                .has(VideoStreamByteMarkers.HDR10));
    }

    @Test
    public void classifiesEbmlHeaderAndPreservesSdrWhenThereIsNoHdrEvidence() {
        byte[] data = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 0, 0};
        assertTrue(VideoStreamByteMarkers.scan(data, data.length).has(VideoStreamByteMarkers.MATROSKA));
        assertTrue(VideoStreamByteMarkers.contains(data, data.length, VideoStreamByteMarkers.MATROSKA));
        VideoStreamByteMarkers.Scan sdr = VideoStreamByteMarkers.scan(bytes("avc1 SDR BT.709"), 15);
        assertFalse(sdr.has(VideoStreamByteMarkers.HDR10));
        assertFalse(sdr.has(VideoStreamByteMarkers.HDR10_PLUS));
        assertFalse(sdr.has(VideoStreamByteMarkers.DOLBY_VISION));
        assertEquals(-1, sdr.dolbyVisionProfile);
    }

    @Test
    public void keepsDolbyVisionProfilePriorityAndRejectsUnrelatedProfileEquals() {
        byte[] data = bytes("profile=5 unrelated DV PROFILE: 7 DV_PROFILE=8");
        assertEquals(8, VideoStreamByteMarkers.scan(data, data.length).dolbyVisionProfile);
        data = bytes("profile=5 unrelated hvc1");
        assertEquals(-1, VideoStreamByteMarkers.scan(data, data.length).dolbyVisionProfile);
    }

    @Test
    public void cancelledProbeStopsWithoutClearingInterruptForOuterFallbackChecks() {
        Thread.currentThread().interrupt();
        try {
            VideoStreamByteMarkers.scan(new byte[8 * 1024 * 1024], 8 * 1024 * 1024);
            fail("cancelled scan continued");
        } catch (CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
