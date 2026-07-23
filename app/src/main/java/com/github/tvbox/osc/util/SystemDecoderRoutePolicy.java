package com.github.tvbox.osc.util;

/**
 * Selects the hardware-backed Android MediaPlayer path for ALL playback.
 *
 * Product decision (0.2.4): the compatibility (MPV) decoder is removed entirely across
 * all three variants (java32 / java64 / Hisense). Every stream — SDR, HDR10, HDR10+,
 * HLG and Dolby Vision — must decode on the vendor system hardware decoder. HDR
 * activation still follows the real stream probe (see the {@code requiresHdrOutput}
 * flag on the forced-system Decision), but playback never leaves the system decoder.
 */
final class SystemDecoderRoutePolicy {
    private SystemDecoderRoutePolicy() {
    }

    static boolean shouldForceSystemDecoder(boolean java64Build,
                                            boolean looksLikeDolbyVision,
                                            boolean systemPlayerRequested) {
        // Unconditionally force the system hardware decoder. The compatibility player
        // is no longer used on any build or for any content type.
        return true;
    }
}
