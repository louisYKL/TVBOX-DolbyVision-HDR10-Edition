package com.github.tvbox.osc.util;

/** Selects the hardware-backed Android MediaPlayer path for TV VOD. */
public final class SystemDecoderRoutePolicy {
    private SystemDecoderRoutePolicy() {
    }

    public static boolean requiresCompatPcmAudioDecoder(boolean java64Build,
                                                         boolean hasDtsAudio,
                                                         boolean hasTrueHdAudio) {
        // The Huawei system player has no decoder for audio/dts_NG. Keeping these
        // tracks on NuPlayer results in a permanent buffering overlay followed by
        // MEDIA_ERROR_UNKNOWN. MPV retains MediaCodec hardware video decoding and
        // decodes only the unsupported audio to stereo PCM.
        return !java64Build && (hasDtsAudio || hasTrueHdAudio);
    }

    public static boolean shouldForceSystemDecoder(boolean java64Build,
                                                   boolean looksLikeDolbyVision,
                                                   boolean requiresCompatPcmAudioDecoder) {
        // 0.2.4 product decision: the compatibility (MPV) decoder is removed entirely across all
        // three variants. Every stream — SDR/HDR10/HDR10+/HLG/Dolby Vision, any container, any
        // audio codec — decodes on the vendor system hardware decoder, and the system player
        // decodes audio to PCM itself. Unconditionally force the system decoder.
        return true;
    }

    public static boolean shouldRetryWithCompatPcmAfterSystemFailure(boolean java64Build,
                                                                       boolean activeSystemPlayer,
                                                                       boolean hasDtsAudio,
                                                                       boolean hasTrueHdAudio,
                                                                       boolean nativeAudioDecoderFailure,
                                                                       boolean alreadyTried) {
        // 0.2.4 product decision: the compatibility (MPV) player is removed entirely. Playback
        // never falls back off the system decoder, so this retry is disabled unconditionally.
        return false;
    }

    public static int resolvePlayerType(int requestedPlayerType,
                                        int routedPlayerType,
                                        boolean forceRoutedPlayerType) {
        return forceRoutedPlayerType ? routedPlayerType : requestedPlayerType;
    }
}
