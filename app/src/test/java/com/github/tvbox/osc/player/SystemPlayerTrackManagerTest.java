package com.github.tvbox.osc.player;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemPlayerTrackManagerTest {
    @Test
    public void runtimeSubtitleTrackRemainsAuthoritativeOverProbeMetadata() {
        TrackInfo runtime = new TrackInfo();
        TrackInfoBean nativeTrack = new TrackInfoBean();
        nativeTrack.trackId = 4;
        nativeTrack.rawLanguage = "chi";
        nativeTrack.rawTitle = "runtime";
        nativeTrack.unreliableMetadata = true;
        nativeTrack.autoSelectBlocked = true;
        runtime.addSubtitle(nativeTrack);

        TrackInfoBean metadata = new TrackInfoBean();
        metadata.trackId = 2;
        metadata.extractorTrackIndex = 2;
        metadata.rawLanguage = "zho";
        metadata.rawTitle = "Chinese subtitle";
        metadata.rawCodec = "subrip";
        metadata.metadataOnly = true;

        TrackInfo merged = SystemPlayerTrackManager.mergeSubtitleMetadata(
                runtime, Collections.singletonList(metadata));

        assertEquals(1, merged.getSubtitle().size());
        assertEquals(4, merged.getSubtitle().get(0).trackId);
        assertFalse(merged.getSubtitle().get(0).metadataOnly);
        assertFalse(merged.getSubtitle().get(0).autoSelectBlocked);
    }

    @Test
    public void probeMetadataIsStillVisibleWhenRuntimeHasNoSubtitleTrack() {
        TrackInfoBean metadata = new TrackInfoBean();
        metadata.trackId = 2;
        metadata.extractorTrackIndex = 2;
        metadata.rawLanguage = "zho";
        metadata.rawCodec = "subrip";
        metadata.metadataOnly = true;

        TrackInfo merged = SystemPlayerTrackManager.mergeSubtitleMetadata(
                new TrackInfo(), Collections.singletonList(metadata));

        assertEquals(1, merged.getSubtitle().size());
        assertTrue(merged.getSubtitle().get(0).metadataOnly);
    }
}
