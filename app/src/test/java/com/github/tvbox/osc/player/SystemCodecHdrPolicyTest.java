package com.github.tvbox.osc.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame;
import com.google.android.exoplayer2.video.ColorInfo;
import com.github.tvbox.osc.util.VideoColorMetadata;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
    public void explicitSdrRequiresAnExplicitNonAmbiguousSdrTransfer() {
        Format sdr = new Format.Builder()
                .setSampleMimeType("video/avc")
                .setColorInfo(new ColorInfo(
                        C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_SDR, null))
                .build();
        Format ambiguousMain10 = new Format.Builder()
                .setSampleMimeType("video/hevc")
                .setCodecs("hvc1.2.4.L153.B0")
                .setColorInfo(new ColorInfo(
                        C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_SDR, null))
                .build();
        Format unknown = new Format.Builder()
                .setSampleMimeType("video/hevc")
                .setCodecs("hvc1.2.4.L153.B0")
                .build();

        assertTrue(SystemCodecHdrPolicy.isExplicitSdr(sdr));
        assertFalse(SystemCodecHdrPolicy.isExplicitSdr(ambiguousMain10));
        assertFalse(SystemCodecHdrPolicy.isExplicitSdr(unknown));
    }

    @Test
    public void main10Bt2020WithUnknownTransferDoesNotGuessDynamicRange() {
        ColorInfo colorInfo = new ColorInfo(
                C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, -1, null);
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
        assertTrue(SystemCodecHdrPolicy.isHdr(
                "video/hevc", "", null, "CUVA HDR Vivid"));
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

    @Test
    public void formatIdVideoRangePromotesHdrWithoutColorInfo() {
        Format format = new Format.Builder()
                .setId("video-range=pq")
                .setSampleMimeType("video/hevc")
                .setCodecs("hvc1.1.6.L120.B0")
                .build();
        assertTrue(SystemCodecHdrPolicy.isHdr(format));
    }

    @Test
    public void missingExtractorColorFieldsReceiveActualHlgAndMasteringData() {
        byte[] actualMastering = new byte[25];
        actualMastering[17] = 0x64;
        Format input = new Format.Builder().setSampleMimeType("video/hevc").build();
        VideoColorMetadata source = new VideoColorMetadata(C.COLOR_TRANSFER_HLG,
                C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, actualMastering, -1, -1);

        Format restored = SystemCodecHdrPolicy.withSourceColorMetadata(input, source);

        assertEquals(C.COLOR_TRANSFER_HLG, restored.colorInfo.colorTransfer);
        assertEquals(C.COLOR_SPACE_BT2020, restored.colorInfo.colorSpace);
        assertArrayEquals(actualMastering, restored.colorInfo.hdrStaticInfo);
        assertNull(input.colorInfo);
    }

    @Test
    public void missingMasteringInformationStaysMissingInsteadOfInventingA1000NitMaster() {
        Format input = new Format.Builder().setSampleMimeType("video/hevc").build();
        Format restored = SystemCodecHdrPolicy.withSourceColorMetadata(input,
                new VideoColorMetadata(C.COLOR_TRANSFER_ST2084, C.COLOR_SPACE_BT2020,
                        C.COLOR_RANGE_LIMITED, null, -1, -1));
        assertEquals(C.COLOR_TRANSFER_ST2084, restored.colorInfo.colorTransfer);
        assertNull(restored.colorInfo.hdrStaticInfo);
    }

    @Test
    public void explicitBt709SdrRejectsStaleHdrMetadataIncludingMasteringBlob() {
        Format input = new Format.Builder().setSampleMimeType("video/avc")
                .setColorInfo(new ColorInfo(C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED,
                        C.COLOR_TRANSFER_SDR, null)).build();
        Format restored = SystemCodecHdrPolicy.withSourceColorMetadata(input,
                new VideoColorMetadata(C.COLOR_TRANSFER_ST2084, C.COLOR_SPACE_BT2020,
                        C.COLOR_RANGE_LIMITED, new byte[25], -1, -1));
        assertSame(input, restored);
        assertFalse(SystemCodecHdrPolicy.isHdr(restored));
    }

    @Test
    public void actualPqRepairsAmbiguousMain10Bt2020DefaultSdr() {
        Format input = new Format.Builder().setSampleMimeType("video/hevc")
                .setCodecs("hvc1.2.4.L153.B0")
                .setColorInfo(new ColorInfo(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED,
                        C.COLOR_TRANSFER_SDR, null)).build();
        Format restored = SystemCodecHdrPolicy.withSourceColorMetadata(input,
                new VideoColorMetadata(C.COLOR_TRANSFER_ST2084, C.COLOR_SPACE_BT2020,
                        C.COLOR_RANGE_LIMITED, null, -1, -1));
        assertEquals(C.COLOR_TRANSFER_ST2084, restored.colorInfo.colorTransfer);
    }

    @Test
    public void actualPqAndHlgRepairBt2020DefaultSdrWhenCodecStringIsMissing() {
        for (String mime : new String[]{"video/hevc", "video/dolby-vision"}) {
            Format input = new Format.Builder().setSampleMimeType(mime)
                    .setCodecs(null)
                    .setColorInfo(new ColorInfo(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED,
                            C.COLOR_TRANSFER_SDR, null)).build();
            assertFalse(SystemCodecHdrPolicy.isExplicitSdr(input));
            for (int transfer : new int[]{C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG}) {
                Format restored = SystemCodecHdrPolicy.withSourceColorMetadata(input,
                        new VideoColorMetadata(transfer, C.COLOR_SPACE_BT2020,
                                C.COLOR_RANGE_LIMITED, null, -1, -1));
                assertEquals(transfer, restored.colorInfo.colorTransfer);
                assertNull(restored.codecs);
                assertTrue(SystemCodecHdrPolicy.isHdr(restored));
            }
        }
    }

    @Test
    public void missingHevcCodecStringAndBt2020DoNotInventHdrWithoutSourceTransfer() {
        Format input = new Format.Builder().setSampleMimeType("video/hevc")
                .setCodecs(null)
                .setColorInfo(new ColorInfo(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED,
                        C.COLOR_TRANSFER_SDR, null)).build();
        assertSame(input, SystemCodecHdrPolicy.withSourceColorMetadata(input, VideoColorMetadata.EMPTY));
        assertFalse(SystemCodecHdrPolicy.isHdr(input));
    }

    @Test
    public void explicitBt709HevcSdrStillRejectsHdrProbeWhenCodecStringIsMissing() {
        Format input = new Format.Builder().setSampleMimeType("video/hevc")
                .setCodecs(null)
                .setColorInfo(new ColorInfo(C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED,
                        C.COLOR_TRANSFER_SDR, null)).build();
        assertSame(input, SystemCodecHdrPolicy.withSourceColorMetadata(input,
                new VideoColorMetadata(C.COLOR_TRANSFER_ST2084, C.COLOR_SPACE_BT2020,
                        C.COLOR_RANGE_LIMITED, new byte[25], -1, -1)));
        assertTrue(SystemCodecHdrPolicy.isExplicitSdr(input));
        assertFalse(SystemCodecHdrPolicy.isHdr(input));
    }

    @Test
    public void completeExtractorHlgIsNotOverwrittenByPqProbeFallback() {
        Format input = new Format.Builder().setSampleMimeType("video/hevc")
                .setColorInfo(new ColorInfo(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED,
                        C.COLOR_TRANSFER_HLG, null)).build();
        assertSame(input, SystemCodecHdrPolicy.withSourceColorMetadata(input,
                new VideoColorMetadata(C.COLOR_TRANSFER_ST2084, C.COLOR_SPACE_BT2020,
                        C.COLOR_RANGE_LIMITED, null, -1, -1)));
    }

    @Test
    public void dolbyVisionBasesRespectProfileAndCompatibilityRatherThanAssumingPq() {
        Format profile5 = dv("dvhe.05.06");
        Format profile7 = dv("dvhe.07.06");
        Format profile8 = dv("dvhe.08.06");
        assertNull(SystemCodecHdrPolicy.dolbyVisionBaseLayerFormat(profile5,
                new VideoColorMetadata(C.COLOR_TRANSFER_ST2084, -1, -1, null, 5, 0)));
        assertEquals(C.COLOR_TRANSFER_ST2084,
                SystemCodecHdrPolicy.dolbyVisionBaseTransfer(profile7, VideoColorMetadata.EMPTY));
        assertEquals(C.COLOR_TRANSFER_HLG, SystemCodecHdrPolicy.dolbyVisionBaseTransfer(profile8,
                new VideoColorMetadata(-1, -1, -1, null, 8, 4)));
        assertEquals(C.COLOR_TRANSFER_SDR, SystemCodecHdrPolicy.dolbyVisionBaseTransfer(profile8,
                new VideoColorMetadata(-1, -1, -1, null, 8, 2)));
        assertNull(SystemCodecHdrPolicy.dolbyVisionBaseLayerFormat(profile8, VideoColorMetadata.EMPTY));
    }

    @Test
    public void nativeDolbyVisionRetainsRpuAndOnlyVerifiedHevcBaseStripsIt() {
        VideoColorMetadata hlgBase = new VideoColorMetadata(-1, -1, -1, null, 8, 4);
        assertFalse(SystemCodecHdrPolicy.shouldStripDolbyVisionRpu(dv("dvhe.08.06"),
                "video/dolby-vision", hlgBase));
        assertTrue(SystemCodecHdrPolicy.shouldStripDolbyVisionRpu(dv("dvhe.08.06"),
                "video/hevc", hlgBase));
        assertFalse(SystemCodecHdrPolicy.shouldStripDolbyVisionRpu(dv("dvhe.05.06"),
                "video/hevc", new VideoColorMetadata(-1, -1, -1, null, 5, 0)));
        assertFalse(SystemCodecHdrPolicy.shouldStripDolbyVisionRpu(
                new Format.Builder().setSampleMimeType("video/hevc").build(),
                "video/hevc", hlgBase));
    }

    @Test
    public void realDvConfigurationRestoresNativeMimeAndActualProfileLevel() {
        Format input = new Format.Builder().setSampleMimeType("video/hevc")
                .setCodecs("hvc1.2.4.L153.B0")
                .setInitializationData(java.util.Collections.singletonList(new byte[]{1, 2, 3})).build();
        Format nativeDv = SystemCodecHdrPolicy.withSourceDolbyVision(input,
                new VideoColorMetadata(-1, -1, -1, null, 8, 6, 1));
        assertEquals("video/dolby-vision", nativeDv.sampleMimeType);
        assertEquals("dvhe.08.06", nativeDv.codecs);
        assertArrayEquals(input.initializationData.get(0), nativeDv.initializationData.get(0));
        assertSame(input, SystemCodecHdrPolicy.withSourceDolbyVision(input,
                new VideoColorMetadata(-1, -1, -1, null, 8, -1, 1)));
    }

    @Test
    public void sdrCompatibleBaseDoesNotRetainHdrMasteringMetadata() {
        Format input = dv("dvhe.08.06").buildUpon().setColorInfo(new ColorInfo(
                C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED,
                C.COLOR_TRANSFER_ST2084, new byte[25]))
                .setMetadata(new Metadata(new TextInformationFrame("TXXX", "format", "Dolby Vision")))
                .build();
        Format base = SystemCodecHdrPolicy.dolbyVisionBaseLayerFormat(input,
                new VideoColorMetadata(-1, -1, -1, null, 8, 6, 2));
        assertEquals("video/hevc", base.sampleMimeType);
        assertEquals(C.COLOR_TRANSFER_SDR, base.colorInfo.colorTransfer);
        assertNull(base.colorInfo.hdrStaticInfo);
        assertNull(base.metadata);
        assertTrue(SystemCodecHdrPolicy.isHdr(input));
        assertFalse(SystemCodecHdrPolicy.isHdr(base));
    }

    @Test
    public void profileNineKeepsNativeDolbyVisionOrARealAvcSdrBase() {
        Format nativeDv = dv("dvav.09.04");
        assertSame(nativeDv, SystemCodecHdrPolicy.withSourceDolbyVision(nativeDv,
                VideoColorMetadata.EMPTY));
        Format base = SystemCodecHdrPolicy.dolbyVisionBaseLayerFormat(nativeDv,
                VideoColorMetadata.EMPTY);
        assertEquals("video/avc", base.sampleMimeType);
        assertEquals(C.COLOR_TRANSFER_SDR, base.colorInfo.colorTransfer);
        assertFalse(SystemCodecHdrPolicy.shouldStripDolbyVisionRpu(nativeDv,
                "video/avc", VideoColorMetadata.EMPTY));
        assertFalse(SystemCodecHdrPolicy.shouldStripDolbyVisionRpu(nativeDv,
                "video/dolby-vision", VideoColorMetadata.EMPTY));
        assertFalse(SystemCodecHdrPolicy.isHdr(base));
    }

    private static Format dv(String codecs) {
        return new Format.Builder().setSampleMimeType("video/dolby-vision")
                .setCodecs(codecs).build();
    }

}
