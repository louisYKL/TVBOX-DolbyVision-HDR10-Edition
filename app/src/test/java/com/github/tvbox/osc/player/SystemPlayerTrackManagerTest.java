package com.github.tvbox.osc.player;

import org.junit.Test;

import java.util.Collections;
import java.util.Arrays;

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

    @Test
    public void probeMetadataAddsDistinctLanguageWhenVendorOnlyReportsOneTrack() {
        TrackInfo runtime = new TrackInfo();
        TrackInfoBean chinese = new TrackInfoBean();
        chinese.trackId = 4;
        chinese.rawLanguage = "chi";
        chinese.language = "中文字幕";
        chinese.rawTitle = "Chinese";
        runtime.addSubtitle(chinese);

        TrackInfoBean duplicateChinese = new TrackInfoBean();
        duplicateChinese.trackId = 2;
        duplicateChinese.extractorTrackIndex = 2;
        duplicateChinese.rawLanguage = "zho";
        duplicateChinese.rawTitle = "Chinese duplicate";
        duplicateChinese.rawCodec = "subrip";
        duplicateChinese.metadataOnly = true;

        TrackInfoBean english = new TrackInfoBean();
        english.trackId = 3;
        english.extractorTrackIndex = 3;
        english.rawLanguage = "eng";
        english.rawTitle = "English";
        english.rawCodec = "subrip";
        english.metadataOnly = true;

        TrackInfo merged = SystemPlayerTrackManager.mergeSubtitleMetadata(
                runtime, Arrays.asList(duplicateChinese, english));

        assertEquals(2, merged.getSubtitle().size());
        assertEquals(4, merged.getSubtitle().get(0).trackId);
        assertEquals(3, merged.getSubtitle().get(1).trackId);
        assertTrue(merged.getSubtitle().get(1).metadataOnly);
    }
}
