package com.github.tvbox.osc.util.live;

/** The saved independent live address is authoritative even when VOD has not loaded. */
public final class LiveSourcePolicy {
    public static final String EXTRA_MAIN_URL = "live.main.url";
    public static final String EXTRA_LIVE_URL = "live.independent.url";

    private LiveSourcePolicy() { }

    public static String resolveUrl(String mainUrl, String liveUrl) {
        String independent = liveUrl == null ? "" : liveUrl.trim();
        return independent.isEmpty() ? (mainUrl == null ? "" : mainUrl.trim()) : independent;
    }
}
