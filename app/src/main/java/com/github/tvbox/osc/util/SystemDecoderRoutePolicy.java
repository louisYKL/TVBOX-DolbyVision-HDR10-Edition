package com.github.tvbox.osc.util;

/** Selects the hardware-backed Android MediaPlayer path for TV VOD. */
public final class SystemDecoderRoutePolicy {
    private SystemDecoderRoutePolicy() {
    }

    public static boolean requiresCompatPcmAudioDecoder(boolean java64Build,
                                                         boolean hasAc3Audio,
                                                         boolean hasEac3Audio,
                                                         boolean hasDtsAudio,
                                                         boolean hasTrueHdAudio,
                                                         boolean hasAtmosLikeAudio) {
        return !java64Build && (hasAc3Audio
                || hasEac3Audio
                || hasDtsAudio
                || hasTrueHdAudio
                || hasAtmosLikeAudio);
    }

    public static boolean shouldForceSystemDecoder(boolean java64Build,
                                                   boolean looksLikeDolbyVision,
                                                   boolean requiresCompatPcmAudioDecoder,
                                                   boolean systemPlayerRequested) {
        return !java64Build
                && !looksLikeDolbyVision
                && !requiresCompatPcmAudioDecoder
                && systemPlayerRequested;
    }

    public static boolean shouldRetryWithCompatPcmAfterSystemFailure(boolean java64Build,
                                                                       boolean activeSystemPlayer,
                                                                       boolean hasDtsAudio,
                                                                       boolean hasTrueHdAudio,
                                                                       boolean nativeAudioDecoderFailure,
                                                                       boolean alreadyTried) {
        boolean knownUnsupportedAudio = !java64Build && (hasDtsAudio || hasTrueHdAudio);
        return activeSystemPlayer
                && !alreadyTried
                && (knownUnsupportedAudio || nativeAudioDecoderFailure);
    }

    public static int resolvePlayerType(int requestedPlayerType,
                                        int routedPlayerType,
                                        boolean forceRoutedPlayerType) {
        return forceRoutedPlayerType ? routedPlayerType : requestedPlayerType;
    }
}
