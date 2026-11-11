package com.github.tvbox.osc.subtitle;

import java.util.regex.Pattern;

/** Decides whether the application's neutral HDR style may replace subtitle source styling. */
public final class SubtitleHdrStylePolicy {
    private static final Pattern ASS_OVERRIDE = Pattern.compile("\\{\\\\[^}]+}");
    private static final Pattern MARKUP = Pattern.compile(
            "(?is)<\\s*/?\\s*(?:font|span|b|i|u|s|strike|ruby|rt|rp|v|c|lang|br|style|em|strong|small|big|sup|sub)(?:\\s|[./>])");
    private static final Pattern WEBVTT_CUE_CLASS = Pattern.compile("(?is)<c(?:\\.[\\w-]+)+>");

    private SubtitleHdrStylePolicy() {
    }

    /**
     * HDR tone adjustment is only for unstyled text cues. Source style metadata and recognized
     * markup are treated as authoritative and must be rendered through the source-style path.
     */
    public static boolean shouldApplyHdrStyle(boolean hasDefinedStyle, String rawText) {
        if (hasDefinedStyle) {
            return false;
        }
        if (rawText == null || rawText.isEmpty()) {
            return true;
        }
        return !ASS_OVERRIDE.matcher(rawText).find()
                && !MARKUP.matcher(rawText).find()
                && !WEBVTT_CUE_CLASS.matcher(rawText).find();
    }
}
