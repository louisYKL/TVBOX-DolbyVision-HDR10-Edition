package com.github.tvbox.osc.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

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
        assertFalse(VideoStreamProbe.containsHdr10TextMarker("color-transfer=20"));
        assertFalse(VideoStreamProbe.containsHdr10TextMarker("dialogue: hlg is a channel name"));
    }

    @Test
    public void hdr10PlusAndDolbyVisionVariantsAreRecognized() {
        assertTrue(VideoStreamProbe.containsHdr10PlusTextMarker("SMPTE 2094-40"));
        assertTrue(VideoStreamProbe.containsDolbyVisionTextMarker("video/dolby-vision"));
        assertTrue(VideoStreamProbe.containsDolbyVisionTextMarker("dvhe.08.06"));
        assertTrue(VideoStreamProbe.containsDolbyVisionTextMarker("Dolby Vision profile 7"));
        assertTrue(VideoStreamProbe.containsHdr10TextMarker("CUVA HDR Vivid"));
    }

    @Test
    public void transferMarkerMustBeExplicit() {
        assertFalse(VideoStreamProbe.containsHdr10TextMarker("color-transfer=3 BT.2020 Main10"));
        assertTrue(VideoStreamProbe.containsHdr10TextMarker("transfer=hlg"));
    }

    @Test
    public void ambiguousExtractorSdrAllowsRealPqAndHlgContainerEvidence() {
        int transfer = VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", "hvc1.2.4.L153.B0", -1);
        VideoColorMetadata extractor = new VideoColorMetadata(transfer, 6, 2, null, -1, -1);
        assertTrue(transfer == -1);
        assertTrue(extractor.mergeMissing(new VideoColorMetadata(6, 6, 2, null, -1, -1))
                .colorTransfer == 6);
        assertTrue(extractor.mergeMissing(new VideoColorMetadata(7, 6, 2, null, -1, -1))
                .colorTransfer == 7);
        assertFalse(extractor.hasHdrTransfer());
        assertFalse(extractor.mergeMissing(VideoColorMetadata.EMPTY).hasHdrTransfer());
    }

    @Test
    public void extractorTransferRepairHandlesMissingProfileWithoutGuessingHdr() {
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", null, 2) == -1);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", null, 0x1000) == -1);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", null, 0x2000) == -1);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", "hvc1.1.6.L120.B0", 1) == 3);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", null, -1) == -1);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", null, 1) == 3);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/hevc", "hvc1.1.6.L120.B0", -1) == 3);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 1,
                "video/hevc", "hvc1.2.4.L153.B0", 2) == 3);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/avc", "avc1.640028", 2) == 3);
        assertTrue(VideoStreamProbe.extractorColorTransfer(3, 6,
                "video/dolby-vision", "dvhe.05.09", 2) == 3);
        assertTrue(VideoStreamProbe.extractorColorTransfer(7, 6,
                "video/hevc", "hvc1.2.4.L153.B0", 2) == 7);
        VideoColorMetadata unknown = new VideoColorMetadata(VideoStreamProbe.extractorColorTransfer(
                3, 6, "video/hevc", null, -1), 6, 2, null, -1, -1);
        assertFalse(unknown.hasHdrTransfer());
        assertFalse(unknown.hasHdr10BaseLayer());
        assertTrue(unknown.mergeMissing(new VideoColorMetadata(3, 6, 2, null, -1, -1))
                .colorTransfer == 3);
    }

    @Test
    public void dvCompatibilityRejectsConflictingGenericPqEvidence() {
        assertFalse(new VideoColorMetadata(6, 6, 2, null, 5, -1).hasHdr10BaseLayer());
        assertFalse(new VideoColorMetadata(6, 6, 2, null, 8, 2).hasHdr10BaseLayer());
        assertFalse(new VideoColorMetadata(6, 6, 2, null, 8, 4).hasHdr10BaseLayer());
        assertFalse(new VideoColorMetadata(6, 6, 2, null, 9, -1).hasHdr10BaseLayer());
        assertTrue(new VideoColorMetadata(3, 6, 2, null, 8, 1).hasHdr10BaseLayer());
        assertTrue(new VideoColorMetadata(6, 6, 2, null, -1, -1).hasHdr10BaseLayer());
    }

    @Test
    public void probeHeadersRoundTripPreservesTransferAndDvCompatibility() {
        HashMap<String, String> headers = new HashMap<>();
        VideoColorMetadata source = new VideoColorMetadata(7, 6, 2, null, 8, 4);
        source.putHeaders(headers);
        VideoColorMetadata copy = VideoColorMetadata.fromHeaders(headers);
        assertTrue(copy.colorTransfer == 7);
        assertTrue(copy.colorStandard == 6);
        assertTrue(copy.colorRange == 2);
        assertTrue(copy.dolbyVisionProfile == 8);
        assertTrue(copy.dolbyVisionCompatibilityId == 4);
        assertFalse(copy.hasHdr10BaseLayer());
    }

    @Test
    public void dvConfigRecordReadsProfileAfterVersionAndCompatibility() {
        byte[] data = new byte[]{'d','v','c','C', 0, 1, 0x10, 0x30, 0x40, 0, 0};
        VideoColorMetadata metadata = VideoStreamProbe.readDolbyVisionConfigRecord(data, data.length);
        assertTrue(metadata.dolbyVisionProfile == 8);
        assertTrue(metadata.dolbyVisionCompatibilityId == 4);
        assertTrue(metadata.dolbyVisionLevel == 6);
        assertTrue(metadata.colorTransfer == 7);
    }

    @Test
    public void dvProfilesNeverImplyTheWrongFallbackTransfer() {
        byte[] config = new byte[]{'d','v','c','C', 1, 0, 10, 0, 0};
        VideoColorMetadata profile5 = VideoStreamProbe.readDolbyVisionConfigRecord(config, config.length);
        assertTrue(profile5.dolbyVisionProfile == 5);
        assertTrue(profile5.colorTransfer == -1);
        assertFalse(profile5.hasHdr10BaseLayer());
        config[6] = 16;
        config[8] = 0x20;
        VideoColorMetadata profile82 = VideoStreamProbe.readDolbyVisionConfigRecord(config, config.length);
        assertTrue(profile82.colorTransfer == 3);
        assertFalse(profile82.hasHdr10BaseLayer());
        config[8] = 0x10;
        VideoColorMetadata profile81 = VideoStreamProbe.readDolbyVisionConfigRecord(config, config.length);
        assertTrue(profile81.colorTransfer == 6);
        assertTrue(profile81.hasHdr10BaseLayer());
    }

    @Test
    public void codecStringPreservesTheActualDvProfile() {
        assertTrue(VideoStreamProbe.extractDolbyVisionProfile("dvhe.08.06") == 8);
        assertTrue(VideoStreamProbe.extractDolbyVisionProfile("dvh1.05.09") == 5);
        assertTrue(VideoStreamProbe.readDolbyVisionCodecMetadata("dvh1.05.09").dolbyVisionLevel == 9);
        assertTrue(VideoStreamProbe.readDolbyVisionCodecMetadata("dvhe.08.06").dolbyVisionProfile == 8);
        assertTrue(VideoStreamProbe.readDolbyVisionCodecMetadata("dvhe.08.bad").dolbyVisionLevel == -1);
    }

    @Test
    public void mp4NclxPreservesHlgAndLimitedRange() {
        byte[] atom = new byte[]{0,0,0,19, 'c','o','l','r', 'n','c','l','x',
                0,9, 0,18, 0,9, 0};
        VideoColorMetadata color = VideoStreamProbe.readMp4ColorMetadata(atom);
        assertTrue(color.colorTransfer == 7);
        assertTrue(color.colorStandard == 6);
        assertTrue(color.colorRange == 2);
        atom[15] = 16;
        atom[18] = (byte) 0x80;
        color = VideoStreamProbe.readMp4ColorMetadata(atom);
        assertTrue(color.colorTransfer == 6);
        assertTrue(color.colorRange == 1);
    }

    @Test
    public void truncatedMp4ColorAtomDoesNotCreateMetadata() {
        byte[] atom = new byte[]{0,0,1,0, 'c','o','l','r', 'n','c','l','x',
                0,9, 0,18, 0,9, 0};
        assertTrue(VideoStreamProbe.readMp4ColorMetadata(atom).colorTransfer == -1);
    }

    @Test
    public void actualStaticMetadataIsBoundedAndDefensivelyCopied() {
        byte[] source = new byte[25];
        source[1] = 42;
        VideoColorMetadata metadata = new VideoColorMetadata(6, 6, 2, source, -1, -1);
        source[1] = 0;
        byte[] exposed = metadata.getHdrStaticInfo();
        assertTrue(exposed[1] == 42);
        exposed[1] = 0;
        HashMap<String, String> headers = new HashMap<>();
        metadata.putHeaders(headers);
        assertTrue(VideoColorMetadata.fromHeaders(headers).getHdrStaticInfo()[1] == 42);
        headers.put("X-TVBox-Probe-HdrStaticInfo", "zz");
        assertTrue(VideoColorMetadata.fromHeaders(headers).getHdrStaticInfo() == null);
        assertTrue(new VideoColorMetadata(6, 6, 2, new byte[26], -1, -1)
                .getHdrStaticInfo() == null);
    }

    @Test
    public void matroskaTransferIsNotCollapsedIntoGenericHdr() {
        VideoColorMetadata color = VideoStreamProbe.readMatroskaColorMetadata(matroskaTracks(
                ebml(0x55BA, new byte[]{18}), ebml(0x55BB, new byte[]{9}),
                ebml(0x55B9, new byte[]{1})));
        assertTrue(color.colorTransfer == 7);
        assertTrue(color.colorStandard == 6);
        assertTrue(color.colorRange == 2);
        assertFalse(color.hasHdr10BaseLayer());
    }

    @Test
    public void explicitMatroskaSdrTransferIsNotOverriddenByMasteringTags() {
        byte[] data = matroskaTracks(ebml(0x55BA, new byte[]{1}),
                ebml(0x55BB, new byte[]{9}), ebml(0x55D0, new byte[0]));
        assertFalse(VideoStreamProbe.hasMatroskaHdrTrackMetadata(data));
        assertTrue(VideoStreamProbe.readMatroskaColorMetadata(data).colorTransfer == 3);
    }

    @Test
    public void hlsDynamicRangeMarkersAreRecognized() {
        assertTrue(VideoStreamProbe.containsHlsDynamicRangeMarker("VIDEO-RANGE=PQ"));
        assertTrue(VideoStreamProbe.containsHlsDynamicRangeMarker("VIDEO-RANGE=HLG"));
        assertTrue(VideoStreamProbe.containsHlsDynamicRangeMarker("transfer=smpte-st-2084"));
        assertFalse(VideoStreamProbe.containsHlsDynamicRangeMarker("VIDEO-RANGE=SDR"));
    }

    @Test
    public void matroskaTrackColourTransferRecognizesPqAndHlgOutsideTheFilePrefix() {
        assertTrue(VideoStreamProbe.hasMatroskaHdrTrackMetadata(matroskaTracks(
                ebml(0x55BA, new byte[]{16}))));
        assertTrue(VideoStreamProbe.hasMatroskaHdrTrackMetadata(matroskaTracks(
                ebml(0x55BA, new byte[]{18}))));
    }

    @Test
    public void matroskaBt2020MasteringMetadataRecognizesHdrButGamutAloneDoesNot() {
        byte[] bt2020Mastered = matroskaTracks(
                ebml(0x55BB, new byte[]{9}),
                ebml(0x55B2, new byte[]{10}),
                ebml(0x55D0, new byte[0]));
        byte[] bt2020Only = matroskaTracks(
                ebml(0x55BB, new byte[]{9}),
                ebml(0x55B2, new byte[]{10}));

        assertTrue(VideoStreamProbe.hasMatroskaHdrTrackMetadata(bt2020Mastered));
        assertFalse(VideoStreamProbe.hasMatroskaHdrTrackMetadata(bt2020Only));
    }

    @Test
    public void hevcSeiRecognizesHdr10StaticAndHdr10PlusMetadata() {
        byte[] hdr10 = hevcSei(137, new byte[]{1, 2, 3, 4});
        byte[] hdr10Plus = hevcSei(4, new byte[]{(byte) 0xb5, 0, 0x3c, 0, 1, 4, 9});

        assertTrue(VideoStreamProbe.containsHdr10SeiMarker(hdr10, hdr10.length));
        assertFalse(VideoStreamProbe.containsHdr10PlusSeiMarker(hdr10, hdr10.length));
        assertTrue(VideoStreamProbe.containsHdr10SeiMarker(hdr10Plus, hdr10Plus.length));
        assertTrue(VideoStreamProbe.containsHdr10PlusSeiMarker(hdr10Plus, hdr10Plus.length));
    }

    @Test
    public void unrelatedSeiPayloadDoesNotPromoteHdr() {
        byte[] unrelatedSei = hevcSei(4, new byte[]{(byte) 0xb5, 0, 0x3c, 0, 1, 3, 9});
        assertFalse(VideoStreamProbe.containsHdr10SeiMarker(unrelatedSei, unrelatedSei.length));
        assertFalse(VideoStreamProbe.containsHdr10PlusSeiMarker(unrelatedSei, unrelatedSei.length));
    }

    private static byte[] hevcSei(int payloadType, byte[] payload) {
        ByteArrayOutputStream rbsp = new ByteArrayOutputStream();
        rbsp.write(payloadType);
        rbsp.write(payload.length);
        rbsp.write(payload, 0, payload.length);
        rbsp.write(0x80);
        byte[] body = rbsp.toByteArray();
        byte[] sample = new byte[4 + 2 + body.length];
        int nalLength = 2 + body.length;
        sample[0] = (byte) (nalLength >> 24);
        sample[1] = (byte) (nalLength >> 16);
        sample[2] = (byte) (nalLength >> 8);
        sample[3] = (byte) nalLength;
        sample[4] = (byte) (39 << 1);
        sample[5] = 1;
        System.arraycopy(body, 0, sample, 6, body.length);
        return sample;
    }

    private static byte[] matroskaTracks(byte[]... colourElements) {
        byte[] colour = ebml(0x55B0, concat(colourElements));
        byte[] video = ebml(0xE0, colour);
        byte[] codecId = ebml(0x86, "V_MPEGH/ISO/HEVC".getBytes(StandardCharsets.ISO_8859_1));
        byte[] trackType = ebml(0x83, new byte[]{1});
        byte[] entry = ebml(0xAE, concat(trackType, codecId, video));
        return ebml(new byte[]{0x16, 0x54, (byte) 0xAE, 0x6B}, entry);
    }

    private static byte[] ebml(int id, byte[] payload) {
        if (id <= 0xff) {
            return ebml(new byte[]{(byte) id}, payload);
        }
        if (id <= 0xffff) {
            return ebml(new byte[]{(byte) (id >> 8), (byte) id}, payload);
        }
        if (id <= 0xffffff) {
            return ebml(new byte[]{(byte) (id >> 16), (byte) (id >> 8), (byte) id}, payload);
        }
        return ebml(new byte[]{(byte) (id >> 24), (byte) (id >> 16),
                (byte) (id >> 8), (byte) id}, payload);
    }

    private static byte[] ebml(byte[] id, byte[] payload) {
        if (payload.length >= 127) {
            throw new IllegalArgumentException("test payload too large");
        }
        return concat(id, new byte[]{(byte) (0x80 | payload.length)}, payload);
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value, 0, value.length);
        }
        return output.toByteArray();
    }
}
