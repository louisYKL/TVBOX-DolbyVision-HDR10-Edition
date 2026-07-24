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

    public static boolean shouldUseHdr10BaseLayer(boolean dolbyVisionStream,
                                                  boolean hardwareDolbyVisionDecoder,
                                                  boolean dolbyVisionOutput) {
        return dolbyVisionStream
                && !supportsNativeDolbyVision(hardwareDolbyVisionDecoder, dolbyVisionOutput);
    }

    public static boolean isEligibleHardwareVideoDecoder(boolean softwareOnly) {
        return !softwareOnly;
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
