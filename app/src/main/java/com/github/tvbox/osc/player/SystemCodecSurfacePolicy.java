package com.github.tvbox.osc.player;

import androidx.annotation.Nullable;

import java.util.Locale;

final class SystemCodecSurfacePolicy {
    private SystemCodecSurfacePolicy() {
    }

    static boolean isSameHolder(@Nullable Object boundHolder, @Nullable Object requestedHolder) {
        return requestedHolder != null && boundHolder == requestedHolder;
    }

    static boolean isSameDirectSurface(@Nullable Object boundSurface,
                                       @Nullable Object requestedSurface) {
        return requestedSurface != null && boundSurface == requestedSurface;
    }

    static boolean requiresSetOutputSurfaceWorkaround(@Nullable String decoderName) {
        if (decoderName == null) {
            return false;
        }
        String normalizedName = decoderName.trim().toUpperCase(Locale.US);
        return normalizedName.startsWith("OMX.NVT.") || normalizedName.startsWith("C2.NVT.");
    }
}
