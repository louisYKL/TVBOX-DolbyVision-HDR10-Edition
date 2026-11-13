package com.github.tvbox.osc.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
