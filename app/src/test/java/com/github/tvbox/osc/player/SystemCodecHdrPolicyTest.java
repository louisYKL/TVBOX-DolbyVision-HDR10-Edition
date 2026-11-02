package com.github.tvbox.osc.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame;
import com.google.android.exoplayer2.video.ColorInfo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemCodecHdrPolicyTest {
    @Test
    public void main10CodecProfileAloneDoesNotPromoteSdr() {
        assertFalse(SystemCodecHdrPolicy.isHdr(
                "video/hevc", "hvc1.2.4.L153.B0", null, ""));
    }

    @Test
    public void bt2020GamutAloneDoesNotPromoteSdr() {
        ColorInfo colorInfo = new ColorInfo(
                C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_SDR, null);
        assertFalse(SystemCodecHdrPolicy.isHdr(
                "video/hevc", "hvc1.2.4.L153.B0", colorInfo, ""));
    }

    @Test
    public void pqAndHlgTransfersPromoteHdr() {
        ColorInfo pq = new ColorInfo(
                C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_ST2084, null);
        ColorInfo hlg = new ColorInfo(
                C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_HLG, null);
        assertTrue(SystemCodecHdrPolicy.isHdr("video/hevc", "hvc1.2.4.L153.B0", pq, ""));
        assertTrue(SystemCodecHdrPolicy.isHdr("video/hevc", "hvc1.2.4.L153.B0", hlg, ""));
    }

    @Test
    public void staticHdrInfoPromotesHdrWithoutCodecProfile() {
        ColorInfo colorInfo = new ColorInfo(
                C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_SDR,
                new byte[]{1});
        assertTrue(SystemCodecHdrPolicy.isHdr(
                "video/hevc", "hvc1.1.6.L120.B0", colorInfo, ""));
    }

    @Test
    public void hdr10PlusAndHlgMetadataMarkersPromoteHdr() {
        assertTrue(SystemCodecHdrPolicy.isHdr(
                "video/hevc", "hvc1.2.4.L153.B0", null, "SMPTE 2094-40 HDR10+"));
        assertTrue(SystemCodecHdrPolicy.isHdr(
                "video/hevc", "hvc1.1.6.L120.B0", null, "ARIB-STD-B67"));
    }

    @Test
    public void metadataEntriesAreInspectedForHdrMarkers() {
        Metadata metadata = new Metadata(new TextInformationFrame(
                "TXXX", "transfer", "SMPTE 2094-40 HDR10+"));
        String text = SystemCodecHdrPolicy.metadataText(metadata);
        assertTrue(SystemCodecHdrPolicy.isHdr("video/hevc", "hvc1.2.4.L153.B0", null, text));
    }

    @Test
    public void incidentalHlgWordDoesNotPromoteSdr() {
        assertFalse(SystemCodecHdrPolicy.isHdr(
                "video/hevc", "hvc1.1.6.L120.B0", null, "dialogue: hlg is a channel name"));
    }

    @Test
    public void dolbyVisionCodecIsBothDolbyVisionAndHdr() {
        assertTrue(SystemCodecHdrPolicy.isDolbyVision(
                "video/dolby-vision", "dvhe.08.06", ""));
        assertTrue(SystemCodecHdrPolicy.isHdr(
                "video/dolby-vision", "dvhe.08.06", null, ""));
    }
}
