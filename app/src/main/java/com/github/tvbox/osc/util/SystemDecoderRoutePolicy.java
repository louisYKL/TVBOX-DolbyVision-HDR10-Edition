package com.github.tvbox.osc.util;

/** Selects the hardware-backed Android MediaPlayer path for ordinary TV VOD. */
final class SystemDecoderRoutePolicy {
    private SystemDecoderRoutePolicy() {
    }

    static boolean shouldForceSystemDecoder(boolean java64Build,
                                            boolean looksLikeDolbyVision,
                                            boolean systemPlayerRequested) {
        return !java64Build && !looksLikeDolbyVision && systemPlayerRequested;
    }
}
