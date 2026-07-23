package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoStreamProbeAudioMarkerTest {
    @Test
    public void matroskaAc3CodecIdIsRecognizedWithoutMatchingEac3() {
        assertTrue(VideoStreamProbe.containsAc3AudioMarker("A_AC3"));
        assertFalse(VideoStreamProbe.containsAc3AudioMarker("A_EAC3"));
        assertTrue(VideoStreamProbe.containsEac3AudioMarker("A_EAC3"));
    }

    @Test
    public void matroskaDtsAndTrueHdCodecIdsAreRecognized() {
        assertTrue(VideoStreamProbe.containsDtsAudioMarker("A_DTS"));
        assertTrue(VideoStreamProbe.containsDtsAudioMarker("A_DTS DTS-HD MA"));
        assertTrue(VideoStreamProbe.containsTrueHdAudioMarker("A_TRUEHD"));
    }

    @Test
    public void atmosMarkerRequiresExplicitAtmosOrJocMetadata() {
        assertTrue(VideoStreamProbe.containsAtmosAudioMarker("Dolby Atmos"));
        assertTrue(VideoStreamProbe.containsAtmosAudioMarker("E-AC-3 JOC"));
        assertFalse(VideoStreamProbe.containsAtmosAudioMarker("A_EAC3"));
    }

    @Test
    public void tv32StartupProbeRequiresUsableAudioRouteMetadata() {
        assertFalse(VideoStreamProbe.hasReliableTv32StartupAudioMetadata(null, 0, false));
        assertFalse(VideoStreamProbe.hasReliableTv32StartupAudioMetadata("", 0, false));
        assertTrue(VideoStreamProbe.hasReliableTv32StartupAudioMetadata("audio/mp4a-latm", 0, false));
        assertTrue(VideoStreamProbe.hasReliableTv32StartupAudioMetadata(null, 1, false));
        assertTrue(VideoStreamProbe.hasReliableTv32StartupAudioMetadata(null, 0, true));
    }
}
