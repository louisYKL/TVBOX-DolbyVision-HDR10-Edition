package com.github.tvbox.osc.player;

import android.media.MediaFormat;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.video.ColorInfo;
import com.github.tvbox.osc.util.VideoColorMetadata;

import java.util.Arrays;

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
        boolean textMarker = containsHdrMarker(codec) || containsHdrMarker(metadata);
        return dolbyVision || hdrTransfer || hdrStaticInfo || textMarker;
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

    static boolean isExplicitSdr(@Nullable Format format) {
        if (format == null || isHdr(format) || format.colorInfo == null
                || format.colorInfo.colorTransfer != C.COLOR_TRANSFER_SDR) {
            return false;
        }
        // Some phone extractors omit the codec string as well as defaulting the
        // transfer to SDR. BT.2020 HEVC with no profile is equally incomplete;
        // neither case is itself evidence of HDR.
        return !isAmbiguousBt2020Sdr(format);
    }

    /** Restore source-owned color fields lost by an extractor, without inventing mastering data. */
    static Format withSourceColorMetadata(Format format, @Nullable VideoColorMetadata source) {
        if (source == null || format == null) {
            return format;
        }
        if (isExplicitSdr(format)) return format;
        ColorInfo current = format.colorInfo;
        int transfer = current == null ? -1 : current.colorTransfer;
        int standard = current == null ? -1 : current.colorSpace;
        int range = current == null ? -1 : current.colorRange;
        byte[] staticInfo = current == null ? null : current.hdrStaticInfo;
        // Source PQ/HLG can repair the extractor's ambiguous SDR default even when
        // its HEVC codec/profile string is absent. Explicit BT.709 SDR still wins.
        boolean ambiguousSdr = isAmbiguousBt2020Sdr(format);
        if (transfer < 0 || (ambiguousSdr && source.hasHdrTransfer())) {
            transfer = source.colorTransfer;
        }
        if (standard < 0) standard = source.colorStandard;
        if (range < 0) range = source.colorRange;
        if (staticInfo == null || staticInfo.length == 0) staticInfo = source.getHdrStaticInfo();
        if (transfer < 0 && standard < 0 && range < 0 && staticInfo == null) return format;
        if (current != null && current.colorTransfer == transfer && current.colorSpace == standard
                && current.colorRange == range && Arrays.equals(current.hdrStaticInfo, staticInfo)) {
            return format;
        }
        return format.buildUpon().setColorInfo(new ColorInfo(standard, range, transfer, staticInfo)).build();
    }

    static int dolbyVisionProfile(Format format, @Nullable VideoColorMetadata source) {
        String codecs = lower(format == null ? null : format.codecs);
        String[] fields = codecs.split("\\.");
        if (fields.length >= 2 && (fields[0].equals("dvhe") || fields[0].equals("dvh1")
                || fields[0].equals("dvav") || fields[0].equals("dva1"))) {
            try { return Integer.parseInt(fields[1]); } catch (NumberFormatException ignored) { }
        }
        return source == null ? -1 : source.dolbyVisionProfile;
    }

    /** Restore DV signalling only when its real configuration supplied both profile and level. */
    static Format withSourceDolbyVision(Format format, @Nullable VideoColorMetadata source) {
        if (source == null || source.dolbyVisionProfile <= 0 || source.dolbyVisionLevel <= 0
                || isDolbyVision(format.sampleMimeType, format.codecs, null)) return format;
        String prefix;
        int profile = source.dolbyVisionProfile;
        if ("video/hevc".equals(format.sampleMimeType)
                && (profile == 4 || profile == 5 || profile == 7 || profile == 8)) {
            prefix = "dvhe";
        } else if ("video/avc".equals(format.sampleMimeType) && profile == 9) {
            prefix = "dvav";
        } else {
            return format;
        }
        return format.buildUpon().setSampleMimeType("video/dolby-vision")
                .setCodecs(String.format(Locale.US, "%s.%02d.%02d", prefix,
                        profile, source.dolbyVisionLevel)).build();
    }

    /** Only standardized compatible bases may be handed to an ordinary HEVC decoder. */
    static int dolbyVisionBaseTransfer(Format format, @Nullable VideoColorMetadata source) {
        int profile = dolbyVisionProfile(format, source);
        if (profile == 7) return C.COLOR_TRANSFER_ST2084;
        if (profile == 9) return C.COLOR_TRANSFER_SDR;
        if (profile != 8) return -1; // Profile 5's IPTPQc2 is not HDR10/PQ YCbCr.
        int compatibility = source == null ? -1 : source.dolbyVisionCompatibilityId;
        if (compatibility == 1) return C.COLOR_TRANSFER_ST2084;
        if (compatibility == 4) return C.COLOR_TRANSFER_HLG;
        if (compatibility == 2) return C.COLOR_TRANSFER_SDR;
        ColorInfo info = format == null ? null : format.colorInfo;
        int transfer = info != null && info.colorTransfer >= 0 ? info.colorTransfer
                : source == null ? -1 : source.colorTransfer;
        return transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
                || transfer == C.COLOR_TRANSFER_SDR ? transfer : -1;
    }

    @Nullable
    static Format dolbyVisionBaseLayerFormat(Format format, @Nullable VideoColorMetadata source) {
        int transfer = dolbyVisionBaseTransfer(format, source);
        if (transfer < 0) return null;
        Format restored = withSourceColorMetadata(format, source);
        ColorInfo info = restored.colorInfo;
        int standard = info == null ? -1 : info.colorSpace;
        int range = info == null ? -1 : info.colorRange;
        byte[] staticInfo = info == null ? null : info.hdrStaticInfo;
        if (transfer == C.COLOR_TRANSFER_SDR) staticInfo = null;
        // The compatibility ID defines the base transfer. Keep actual primaries, range and
        // mastering metadata; do not synthesize a 1000-nit mastering display descriptor.
        String mime = dolbyVisionProfile(format, source) == 9 ? "video/avc" : "video/hevc";
        return restored.buildUpon().setSampleMimeType(mime).setCodecs(null)
                .setMetadata(null)
                .setColorInfo(new ColorInfo(standard, range, transfer, staticInfo)).build();
    }

    static boolean shouldStripDolbyVisionRpu(Format format, String actualCodecMime,
                                             @Nullable VideoColorMetadata source) {
        return "video/hevc".equals(actualCodecMime)
                && isDolbyVision(format.sampleMimeType, format.codecs, null)
                && dolbyVisionBaseTransfer(format, source) >= 0;
    }

    static boolean isHdr(@Nullable MediaFormat mediaFormat) {
        if (mediaFormat == null) {
            return false;
        }
        int transfer = safeGetInteger(mediaFormat, MediaFormat.KEY_COLOR_TRANSFER, -1);
        if (transfer == MediaFormat.COLOR_TRANSFER_ST2084
                || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
            return true;
        }
        if (containsNonEmptyValue(mediaFormat, MediaFormat.KEY_HDR_STATIC_INFO)) {
            return true;
        }
        if (containsNonEmptyValue(mediaFormat, "hdr10-plus-info")) {
            return true;
        }
        // Some phone codecs expose the transfer as a vendor string in the
        // MediaFormat dump instead of the integer COLOR_TRANSFER key.
        String dump;
        try {
            dump = mediaFormat.toString();
        } catch (Throwable ignored) {
            dump = "";
        }
        return containsHdrMarker(lower(dump));
    }

    static boolean isExplicitSdr(@Nullable MediaFormat mediaFormat) {
        if (mediaFormat == null) {
            return false;
        }
        int transfer = safeGetInteger(mediaFormat, MediaFormat.KEY_COLOR_TRANSFER, -1);
        return transfer == C.COLOR_TRANSFER_SDR
                && !isHdr(mediaFormat);
    }

    static boolean isExplicitSdr(@Nullable Format format, @Nullable MediaFormat outputFormat) {
        if (!isExplicitSdr(format) || outputFormat == null) {
            return false;
        }
        int transfer = safeGetInteger(outputFormat, MediaFormat.KEY_COLOR_TRANSFER, -1);
        return transfer == C.COLOR_TRANSFER_SDR
                && !isHdr(outputFormat);
    }

    private static int safeGetInteger(MediaFormat mediaFormat, String key, int fallback) {
        try {
            return mediaFormat.containsKey(key) ? mediaFormat.getInteger(key) : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean containsNonEmptyValue(MediaFormat mediaFormat, String key) {
        try {
            if (!mediaFormat.containsKey(key)) {
                return false;
            }
            java.nio.ByteBuffer value = mediaFormat.getByteBuffer(key);
            return value != null && value.remaining() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAmbiguousBt2020Sdr(Format format) {
        ColorInfo info = format.colorInfo;
        if (info == null || info.colorSpace != C.COLOR_SPACE_BT2020
                || info.colorTransfer != C.COLOR_TRANSFER_SDR) return false;
        String mime = lower(format.sampleMimeType);
        String codecs = lower(format.codecs).trim();
        if (!("video/hevc".equals(mime) || "video/dolby-vision".equals(mime))) {
            return false;
        }
        return codecs.length() == 0 || codecs.startsWith("hvc1.2.") || codecs.startsWith("hev1.2.");
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
                || value.contains("maxfall")
                || value.contains("hdr vivid")
                || value.contains("hdr-vivid")
                || value.contains("hdrvivid")
                || value.contains("cuva");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
