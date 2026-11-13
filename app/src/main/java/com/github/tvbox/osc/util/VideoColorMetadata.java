package com.github.tvbox.osc.util;

import java.util.Map;

/** Actual stream color metadata; unknown values must never be replaced by display guesses. */
public final class VideoColorMetadata {
    public static final VideoColorMetadata EMPTY = new VideoColorMetadata(-1, -1, -1, null, -1, -1);
    public final int colorTransfer;
    public final int colorStandard;
    public final int colorRange;
    public final int dolbyVisionProfile;
    public final int dolbyVisionLevel;
    public final int dolbyVisionCompatibilityId;
    private final byte[] hdrStaticInfo;

    public VideoColorMetadata(int transfer, int standard, int range, byte[] staticInfo,
                              int profile, int compatibilityId) {
        this(transfer, standard, range, staticInfo, profile, -1, compatibilityId);
    }

    public VideoColorMetadata(int transfer, int standard, int range, byte[] staticInfo,
                              int profile, int level, int compatibilityId) {
        colorTransfer = transfer;
        colorStandard = standard;
        colorRange = range;
        dolbyVisionProfile = profile;
        dolbyVisionLevel = level;
        dolbyVisionCompatibilityId = compatibilityId;
        hdrStaticInfo = staticInfo != null && staticInfo.length == 25 ? staticInfo.clone() : null;
    }

    public byte[] getHdrStaticInfo() {
        return hdrStaticInfo == null ? null : hdrStaticInfo.clone();
    }

    public boolean hasHdrTransfer() {
        return colorTransfer == 6 || colorTransfer == 7;
    }

    public boolean hasHdr10BaseLayer() {
        if (dolbyVisionProfile == 7) return true;
        if (dolbyVisionProfile == 8) {
            if (dolbyVisionCompatibilityId == 1) return true;
            if (dolbyVisionCompatibilityId > 0) return false;
        } else if (dolbyVisionProfile > 0) {
            // Profile 5 IPTPQc2 (and Profile 9 SDR) cannot become an HDR10 base
            // merely because an extractor or header also supplied a PQ transfer.
            return false;
        }
        return colorTransfer == 6;
    }

    /** Keep the more authoritative extractor's fields; fill only omissions from the container. */
    public VideoColorMetadata mergeMissing(VideoColorMetadata fallback) {
        if (fallback == null) return this;
        return new VideoColorMetadata(colorTransfer >= 0 ? colorTransfer : fallback.colorTransfer,
                colorStandard >= 0 ? colorStandard : fallback.colorStandard,
                colorRange >= 0 ? colorRange : fallback.colorRange,
                hdrStaticInfo != null ? hdrStaticInfo : fallback.hdrStaticInfo,
                dolbyVisionProfile > 0 ? dolbyVisionProfile : fallback.dolbyVisionProfile,
                dolbyVisionLevel >= 0 ? dolbyVisionLevel : fallback.dolbyVisionLevel,
                dolbyVisionCompatibilityId >= 0 ? dolbyVisionCompatibilityId
                        : fallback.dolbyVisionCompatibilityId);
    }

    public static VideoColorMetadata fromHeaders(Map<String, String> headers) {
        if (headers == null) return EMPTY;
        return new VideoColorMetadata(integer(headers, "ColorTransfer"),
                integer(headers, "ColorStandard"), integer(headers, "ColorRange"),
                decodeStatic(value(headers, "HdrStaticInfo")),
                integer(headers, "DolbyVisionProfile"),
                integer(headers, "DolbyVisionLevel"),
                integer(headers, "DolbyVisionCompatibilityId"));
    }

    void putHeaders(Map<String, String> headers) {
        put(headers, "ColorTransfer", colorTransfer);
        put(headers, "ColorStandard", colorStandard);
        put(headers, "ColorRange", colorRange);
        put(headers, "DolbyVisionProfile", dolbyVisionProfile);
        put(headers, "DolbyVisionLevel", dolbyVisionLevel);
        put(headers, "DolbyVisionCompatibilityId", dolbyVisionCompatibilityId);
        headers.remove("X-TVBox-Probe-HdrStaticInfo");
        if (hdrStaticInfo != null) {
            StringBuilder hex = new StringBuilder(50);
            char[] digits = "0123456789abcdef".toCharArray();
            for (byte b : hdrStaticInfo) {
                hex.append(digits[(b >>> 4) & 15]).append(digits[b & 15]);
            }
            headers.put("X-TVBox-Probe-HdrStaticInfo", hex.toString());
        }
    }

    private static void put(Map<String, String> headers, String key, int value) {
        String header = "X-TVBox-Probe-" + key;
        headers.remove(header);
        if (value >= 0) headers.put(header, Integer.toString(value));
    }

    private static int integer(Map<String, String> headers, String key) {
        try { return Integer.parseInt(value(headers, key)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private static String value(Map<String, String> headers, String suffix) {
        String name = "X-TVBox-Probe-" + suffix;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static byte[] decodeStatic(String value) {
        if (value == null || value.length() != 50) return null;
        byte[] bytes = new byte[25];
        for (int i = 0; i < bytes.length; i++) {
            int a = Character.digit(value.charAt(i * 2), 16);
            int b = Character.digit(value.charAt(i * 2 + 1), 16);
            if (a < 0 || b < 0) return null;
            bytes[i] = (byte) ((a << 4) | b);
        }
        return bytes;
    }
}
