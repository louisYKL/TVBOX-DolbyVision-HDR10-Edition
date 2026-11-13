package com.github.tvbox.osc.subtitle.widget;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.text.Cue;

import java.util.Collections;
import java.util.List;

/** Keeps the source pixels, colors, antialiasing and effects of PGS/VobSub subtitles. */
public final class BitmapSubtitleRenderer {
    private BitmapSubtitleRenderer() {
    }

    public static List<Cue> normalizeCues(@Nullable List<Cue> cues) {
        return normalizeCues(cues, false);
    }

    public static List<Cue> normalizeCues(@Nullable List<Cue> cues, boolean hdrOutput) {
        // Bitmap cues already contain their complete source design. Rebuilding them on the
        // application looper both destroys that design and transiently allocates three full
        // pixel surfaces. HDR styling applies only to unstyled text in SimpleSubtitleView.
        return cues == null ? Collections.emptyList() : cues;
    }

    public static Bitmap normalizeBitmap(@Nullable Bitmap source) {
        return source;
    }

    public static Bitmap normalizeBitmap(@Nullable Bitmap source, boolean hdrOutput) {
        return source;
    }
}
