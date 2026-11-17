package com.github.tvbox.osc.util;

import java.util.Locale;

/** Keeps every built-in VOD route on the unified device-codec player. */
public final class SystemDecoderRoutePolicy {
    private SystemDecoderRoutePolicy() {
    }

    public static int resolvePlayerType(int requestedPlayerType,
                                        int routedPlayerType,
                                        boolean forceRoutedPlayerType) {
        return PlayerHelper.PLAYER_TYPE_SYSTEM;
    }

    public static boolean supportsNativeDolbyVision(boolean hardwareDolbyVisionDecoder,
                                                     boolean dolbyVisionOutput) {
        return hardwareDolbyVisionDecoder && dolbyVisionOutput;
    }

    public static boolean shouldUseNativeDolbyVision(boolean dolbyVisionStream,
                                                     boolean hardwareDolbyVisionDecoder,
                                                     boolean dolbyVisionOutput) {
        return dolbyVisionStream
                && supportsNativeDolbyVision(hardwareDolbyVisionDecoder, dolbyVisionOutput);
    }

    /** Dolby Vision profiles which carry an HDR10-compatible base layer. */
    public static boolean hasHdr10BaseLayer(int profile, int compatibilityId) {
        return profile == 7 || (profile == 8 && compatibilityId == 1);
    }

    /** Profile 8.4 is HLG and must not be routed as PQ/HDR10. */
    public static boolean hasHlgBaseLayer(int profile, int compatibilityId) {
        return profile == 8 && compatibilityId == 4;
    }

    /** Profile 8.2 carries an SDR base layer; profile 5 has no HDR10 base layer. */
    public static boolean hasSdrBaseLayer(int profile, int compatibilityId) {
        return profile == 9 || (profile == 8 && compatibilityId == 2);
    }

    public static String dolbyVisionBaseMode(int profile, int compatibilityId, int transfer) {
        if (profile == 5) return "dv-no-compatible-base";
        if (hasHlgBaseLayer(profile, compatibilityId)) return "base-hlg";
        if (hasSdrBaseLayer(profile, compatibilityId)) return "base-sdr";
        if (hasHdr10BaseLayer(profile, compatibilityId)) return "base-hdr";
        // A known compatibility id is authoritative over a conflicting coarse color hint.
        if (profile == 8 && compatibilityId > 0) return "dv-no-compatible-base";
        // Compatibility IDs are omitted by some Android extractors. A PQ transfer
        // is sufficient evidence for the HDR10 base of profile 8.1 when the ID is
        // unavailable; do not make a playable HDR10 stream fail preflight.
        if (profile == 8 && compatibilityId <= 0 && transfer == 6) return "base-hdr";
        // A coarse transfer value without a known DV compatibility ID does not
        // identify the base layer. Keep the stream in the unresolved state until
        // dvcC/dvvC or extractor metadata supplies an authoritative mapping.
        return "dv-no-compatible-base";
    }

    public static boolean isEligibleHardwareVideoDecoder(boolean softwareOnly) {
        return !softwareOnly;
    }

    /** Some vendor HDR aliases omit the hardware flag, but still explicitly reject software. */
    public static boolean isEligibleHdrVideoCodec(int sdkInt, String codecName,
                                                  boolean hardwareAccelerated, boolean softwareOnly) {
        if (sdkInt >= 29 && softwareOnly) return false;
        if (sdkInt >= 29 && hardwareAccelerated) return true;
        return isLikelyHardwareCodec(28, codecName, false, false);
    }

    /**
     * Native Dolby Vision must never use the broad HDR vendor-alias fallback above.
     * On API 29 and later, a DV decoder is eligible only when Android explicitly marks
     * it hardware accelerated and it is not software-only. Without that proof the
     * renderer must use the verified HEVC/AVC compatibility-base route instead.
     * Older APIs do not expose the hardware flag, so retain the conservative codec-name
     * check used by the platform decoder list.
     */
    public static boolean isEligibleNativeDolbyVisionCodec(int sdkInt,
                                                            String codecName,
                                                            boolean hardwareAccelerated,
                                                            boolean softwareOnly) {
        if (softwareOnly) {
            return false;
        }
        if (sdkInt >= 29) {
            return hardwareAccelerated;
        }
        return isLikelyHardwareCodec(sdkInt, codecName, hardwareAccelerated, false);
    }

    /**
     * A small set of vendor decoders advertise video/dolby-vision but emit no frames for
     * profile 7/8 streams when the display path only accepts the HDR10 base layer.  Keep these
     * codecs available for ordinary HDR playback, but force the DV-compatible base-layer route
     * so audio cannot continue against a black video surface.
     */
    public static boolean shouldAvoidNativeDolbyVision(String codecName) {
        if (codecName == null) {
            return false;
        }
        String normalized = codecName.trim().toLowerCase(Locale.US);
        return normalized.contains("nvt")
                || normalized.contains("hisilicon")
                || normalized.contains("hisi")
                || normalized.contains("hi3798")
                || normalized.contains("hi351")
                || normalized.contains("mstar");
    }

    /**
     * Mirrors Android/ExoPlayer's pre-API 29 codec classification without treating every
     * platform audio decoder as software. Older Android releases do not expose
     * MediaCodecInfo.isHardwareAccelerated(), so vendor OMX/C2/ARC codec names are the only
     * reliable public signal available there.
     */
    public static boolean isLikelyHardwareCodec(int sdkInt,
                                                String codecName,
                                                boolean hardwareAccelerated,
                                                boolean softwareOnly) {
        if (sdkInt >= 29) {
            return hardwareAccelerated && !softwareOnly;
        }
        String normalized = codecName == null
                ? "" : codecName.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.startsWith("arc.")) {
            return true;
        }
        if (normalized.startsWith("omx.google.")
                || normalized.startsWith("omx.ffmpeg.")
                || normalized.startsWith("c2.android.")
                || normalized.startsWith("c2.google.")
                || normalized.startsWith("sw.")
                || normalized.contains(".sw.")
                || normalized.contains("software")) {
            return false;
        }
        return normalized.startsWith("omx.") || normalized.startsWith("c2.");
    }

    public static int platformAudioDecoderPriority(boolean softwareOnly) {
        return softwareOnly ? 1 : 0;
    }

    public static int platformAudioDecoderPriority(int sdkInt,
                                                   String codecName,
                                                   boolean hardwareAccelerated,
                                                   boolean softwareOnly) {
        return isLikelyHardwareCodec(
                sdkInt, codecName, hardwareAccelerated, softwareOnly) ? 0 : 1;
    }

    public static boolean shouldUseFfmpegAudioFallback(boolean platformDecoderAvailable,
                                                       boolean ffmpegAvailable) {
        return !platformDecoderAvailable && ffmpegAvailable;
    }
}
