package com.github.tvbox.osc.util;

/** Selects the hardware-backed Android MediaPlayer path for TV VOD. */
public final class SystemDecoderRoutePolicy {
    private SystemDecoderRoutePolicy() {
    }

    public static boolean isConfirmedSingleLayerDolbyVision(boolean hasDolbyVision,
                                                            int dolbyVisionProfile,
                                                            boolean hasHdr10BaseLayer) {
        if (!hasDolbyVision || hasHdr10BaseLayer) {
            return false;
        }
        // Profile 5 is the normal single-layer DV form. A probe with no profile
        // and no HDR10 base layer remains conservative because it can be the same
        // format behind a local proxy.
        return dolbyVisionProfile == 5 || dolbyVisionProfile <= 0;
    }

    /**
     * Android MediaPlayer does not expose a reliable "decode this track to PCM" output
     * switch. For every identified encoded surround format, use the compatibility player:
     * it retains MediaCodec video decoding and sends decoded stereo PCM to AudioTrack.
     */
    public static boolean requiresCompatPcmOutput(boolean hasAc3Audio,
                                                   boolean hasEac3Audio,
                                                   boolean hasDtsAudio,
                                                   boolean hasTrueHdAudio,
                                                   boolean hasAtmosLikeAudio) {
        return hasAc3Audio
                || hasEac3Audio
                || hasDtsAudio
                || hasTrueHdAudio
                || hasAtmosLikeAudio;
    }

    public static boolean shouldForceSystemDecoder(boolean java64Build,
                                                   boolean confirmedSingleLayerDolbyVision,
                                                   boolean requiresCompatPcmAudioDecoder) {
        return !java64Build
                && !confirmedSingleLayerDolbyVision
                && !requiresCompatPcmAudioDecoder;
    }

    public static boolean shouldRetryWithCompatPcmAfterSystemFailure(boolean java64Build,
                                                                       boolean activeSystemPlayer,
                                                                       boolean confirmedSingleLayerDolbyVision,
                                                                       boolean nativeAudioDecoderFailure,
                                                                       boolean alreadyTried) {
        // A weak container probe can miss a compressed track. Some TV firmwares then report
        // MEDIA_INFO_AUDIO_NOT_PLAYING (804) without a fatal MediaPlayer error. Retry exactly
        // once on the PCM compatibility route; its video path still uses MediaCodec hardware
        // decoding, while audio is decoded to stereo PCM for the external output route.
        return activeSystemPlayer && nativeAudioDecoderFailure && !alreadyTried;
    }

    public static int resolvePlayerType(int requestedPlayerType,
                                        int routedPlayerType,
                                        boolean forceRoutedPlayerType) {
        return forceRoutedPlayerType ? routedPlayerType : requestedPlayerType;
    }
}
