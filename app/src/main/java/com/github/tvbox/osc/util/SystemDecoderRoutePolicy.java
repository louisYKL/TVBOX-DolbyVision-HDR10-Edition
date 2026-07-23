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
        // 0.2.4 product decision: the compatibility (MPV) decoder is removed entirely across
        // all three variants (java32 / java64 / Hisense). Every stream — SDR, HDR10, HDR10+,
        // HLG and Dolby Vision — decodes on the vendor system hardware decoder, and the system
        // player decodes all audio (including AC-3/E-AC-3/DTS/TrueHD/Atmos) to PCM. HDR still
        // activates from the real stream probe (via the forced-system Decision's requiresHdrOutput
        // flag); playback never leaves the system decoder for any content type.
        return true;
    }

    public static boolean shouldRetryWithCompatPcmAfterSystemFailure(boolean java64Build,
                                                                       boolean activeSystemPlayer,
                                                                       boolean confirmedSingleLayerDolbyVision,
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
