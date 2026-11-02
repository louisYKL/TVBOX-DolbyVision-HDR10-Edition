package com.github.tvbox.osc.player;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.video.ColorInfo;

import java.util.Locale;

/**
 * Classifies the dynamic range exposed by an ExoPlayer video format.
 *
 * Main10 and BT.2020 describe bit depth and gamut, not transfer function. They
 * can both occur in SDR, so HDR mode requires dynamic-range evidence instead.
 * Keeping this decision in a pure helper lets runtime playback and regression
 * tests share the same rules.
 */
final class SystemCodecHdrPolicy {
    private SystemCodecHdrPolicy() {
    }

    static boolean isHdr(@Nullable String sampleMimeType,
                         @Nullable String codecs,
                         @Nullable ColorInfo colorInfo,
                         @Nullable String metadataText) {
        String mime = lower(sampleMimeType);
        String codec = lower(codecs);
        String metadata = lower(metadataText);
        boolean dolbyVision = "video/dolby-vision".equals(mime)
                || codec.contains("dvhe")
                || codec.contains("dvh1")
                || codec.contains("dovi")
                || metadata.contains("dolby vision")
                || metadata.contains("dolby-vision")
                || metadata.contains("dolbyvision")
                || metadata.contains("dvhe")
                || metadata.contains("dvh1");
        boolean hdrTransfer = colorInfo != null
                && (colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                || colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG);
        boolean hdrStaticInfo = colorInfo != null
                && colorInfo.hdrStaticInfo != null
                && colorInfo.hdrStaticInfo.length > 0;
        // Some vendor extractors preserve the BT.2020 + 10-bit signal but
        // replace the transfer characteristic with UNSPECIFIED (-1). Treat
        // that combination as HDR, while keeping an explicit SDR transfer
        // (and BT.2020 by itself) on the SDR path.
        boolean main10WideGamutWithoutSdrTransfer = colorInfo != null
                && colorInfo.colorSpace == C.COLOR_SPACE_BT2020
                && colorInfo.colorTransfer == -1
                && isHevcMain10(mime, codec);
        boolean textMarker = containsHdrMarker(codec) || containsHdrMarker(metadata);
        return dolbyVision || hdrTransfer || hdrStaticInfo
                || main10WideGamutWithoutSdrTransfer || textMarker;
    }

    /**
     * Classifies the complete ExoPlayer format, including vendor fields that are
     * only present in Format.toString() when the extractor did not populate
     * ColorInfo. A few phone MediaExtractors expose PQ/HLG this way.
     */
    static boolean isHdr(@Nullable Format format) {
        if (format == null) {
            return false;
        }
        String formatText;
        try {
            formatText = format.toString();
        } catch (Throwable ignored) {
            formatText = "";
        }
        String metadata = metadataText(format.metadata);
        if (formatText.length() > 0) {
            metadata = metadata.length() == 0 ? formatText : metadata + " " + formatText;
        }
        return isHdr(format.sampleMimeType, format.codecs, format.colorInfo, metadata)
                || containsHdrMarker(lower(format.id))
                || containsHdrMarker(lower(format.containerMimeType));
    }

    static boolean isDolbyVision(@Nullable String sampleMimeType,
                                 @Nullable String codecs,
                                 @Nullable String metadataText) {
        String mime = lower(sampleMimeType);
        String codec = lower(codecs);
        String metadata = lower(metadataText);
        return "video/dolby-vision".equals(mime)
                || codec.contains("dvhe")
                || codec.contains("dvh1")
                || codec.contains("dovi")
                || metadata.contains("dolby vision")
                || metadata.contains("dolby-vision")
                || metadata.contains("dolbyvision")
                || metadata.contains("dvhe")
                || metadata.contains("dvh1");
    }

    static String metadataText(@Nullable Metadata metadata) {
        if (metadata == null || metadata.length() == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < metadata.length(); i++) {
            Metadata.Entry entry = metadata.get(i);
            if (entry == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(entry.getClass().getName()).append(' ').append(entry);
        }
        return text.toString();
    }

    static boolean hasExplicitHdrMarker(@Nullable String value) {
        return containsHdrMarker(lower(value));
    }

    private static boolean isHevcMain10(String mime, String codecs) {
        if (!("video/hevc".equals(mime) || "video/dolby-vision".equals(mime))) {
            return false;
        }
        return codecs.startsWith("hvc1.2.") || codecs.startsWith("hev1.2.");
    }

    private static boolean containsHdrMarker(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        return value.contains("hdr10+")
                || value.contains("hdr10plus")
                || value.contains("hdr10")
                || value.contains("smpte2084")
                || value.contains("smpte 2084")
                || value.contains("smpte2094")
                || value.contains("smpte 2094")
                || value.contains("arib-std-b67")
                || value.contains("color-transfer=6")
                || value.contains("color-transfer=7")
                || value.contains("color_transfer=6")
                || value.contains("color_transfer=7")
                || value.contains("colortransfer=6")
                || value.contains("colortransfer=7")
                || value.contains("transfer=6")
                || value.contains("transfer=7")
                || value.contains("transfer:6")
                || value.contains("transfer:7")
                || value.contains("transfer=st2084")
                || value.contains("transfer=smpte2084")
                || value.contains("transfer=smpte-st-2084")
                || value.contains("transfer=pq")
                || value.contains("transfer=hlg")
                || value.contains("transfer:hlg")
                || value.contains("video-range=pq")
                || value.contains("video-range=hlg")
                || value.contains("video_range=pq")
                || value.contains("video_range=hlg")
                || value.contains("video range=pq")
                || value.contains("video range=hlg")
                || value.contains("smpte-st-2084")
                || value.contains("hdr-static-info")
                || value.contains("mastering-display")
                || value.contains("max-cll")
                || value.contains("maxfall");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
