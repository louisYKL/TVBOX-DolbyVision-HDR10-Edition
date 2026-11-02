package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoHdrMetadataTest {
    @Test
    public void hdr10AndHlgTransferMarkersAreRecognized() {
        assertTrue(VideoStreamProbe.containsHdr10TextMarker("SMPTE 2084"));
        assertTrue(VideoStreamProbe.containsHdr10TextMarker("ARIB-STD-B67"));
        assertTrue(VideoStreamProbe.containsHdr10TextMarker("color-transfer=7"));
        assertTrue(VideoStreamProbe.containsHdr10TextMarker("HDR10 static metadata"));
    }

    @Test
    public void wideGamutAloneDoesNotActivateHdrMode() {
        assertFalse(VideoStreamProbe.containsHdr10TextMarker("BT.2020 color standard"));
        assertFalse(VideoStreamProbe.containsHdr10TextMarker("color-standard=6"));
        assertFalse(VideoStreamProbe.containsHdr10TextMarker("dialogue: hlg is a channel name"));
    }

    @Test
    public void hdr10PlusAndDolbyVisionVariantsAreRecognized() {
        assertTrue(VideoStreamProbe.containsHdr10PlusTextMarker("SMPTE 2094-40"));
        assertTrue(VideoStreamProbe.containsDolbyVisionTextMarker("video/dolby-vision"));
        assertTrue(VideoStreamProbe.containsDolbyVisionTextMarker("dvhe.08.06"));
        assertTrue(VideoStreamProbe.containsDolbyVisionTextMarker("Dolby Vision profile 7"));
    }

    @Test
    public void transferMarkerMustBeExplicit() {
        assertFalse(VideoStreamProbe.containsHdr10TextMarker("color-transfer=3 BT.2020 Main10"));
        assertTrue(VideoStreamProbe.containsHdr10TextMarker("transfer=hlg"));
    }

    @Test
    public void hlsDynamicRangeMarkersAreRecognized() {
        assertTrue(VideoStreamProbe.containsHlsDynamicRangeMarker("VIDEO-RANGE=PQ"));
        assertTrue(VideoStreamProbe.containsHlsDynamicRangeMarker("VIDEO-RANGE=HLG"));
        assertTrue(VideoStreamProbe.containsHlsDynamicRangeMarker("transfer=smpte-st-2084"));
        assertFalse(VideoStreamProbe.containsHlsDynamicRangeMarker("VIDEO-RANGE=SDR"));
    }
}
