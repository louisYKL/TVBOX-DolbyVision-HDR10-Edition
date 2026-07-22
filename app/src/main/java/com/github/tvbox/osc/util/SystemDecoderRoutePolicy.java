package com.github.tvbox.osc.util;

/** Selects the hardware-backed Android MediaPlayer path for ordinary TV VOD. */
public final class SystemDecoderRoutePolicy {
    private SystemDecoderRoutePolicy() {
    }

    public static boolean shouldForceSystemDecoder(boolean java64Build,
                                                   boolean looksLikeDolbyVision) {
        return !java64Build && !looksLikeDolbyVision;
    }

    public static int resolvePlayerType(int requestedPlayerType,
                                        int routedPlayerType,
                                        boolean forceRoutedPlayerType) {
        return forceRoutedPlayerType ? routedPlayerType : requestedPlayerType;
    }
}
