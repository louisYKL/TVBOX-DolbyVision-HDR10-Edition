package xyz.doikki.videoplayer.player;

import android.net.Uri;
import android.text.TextUtils;

import java.net.URLDecoder;
import java.util.Locale;
import java.util.Map;

public final class HdrPlaybackHeuristics {
    private HdrPlaybackHeuristics() {
    }

    public static boolean looksLikeDolbyVision(String url, Map<String, String> headers) {
        return looksLikeDolbyVision(url, headers, (String[]) null);
    }

    public static boolean looksLikeDolbyVision(String url, Map<String, String> headers, String... extraHints) {
        if (looksLikeDolbyVisionText(url)) {
            return true;
        }
        if (headers == null || headers.isEmpty()) {
            if (extraHints == null || extraHints.length == 0) {
                return false;
            }
        } else {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (looksLikeDolbyVisionText(entry.getKey()) || looksLikeDolbyVisionText(entry.getValue())) {
                    return true;
                }
            }
        }
        if (extraHints != null) {
            for (String hint : extraHints) {
                if (looksLikeDolbyVisionText(hint)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean looksLikeDolbyVisionText(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        if (looksLikeDolbyVisionTextInternal(value)) {
            return true;
        }
        String decoded = decodeRepeatedly(value, 2);
        if (!TextUtils.isEmpty(decoded)
                && !TextUtils.equals(decoded, value)
                && looksLikeDolbyVisionTextInternal(decoded)) {
            return true;
        }
        try {
            Uri uri = Uri.parse(value);
            String path = uri.getPath();
            if (!TextUtils.isEmpty(path) && looksLikeDolbyVisionTextInternal(path)) {
                return true;
            }
            String nestedUrl = uri.getQueryParameter("url");
            if (!TextUtils.isEmpty(nestedUrl) && looksLikeDolbyVisionText(nestedUrl)) {
                return true;
            }
            for (String name : uri.getQueryParameterNames()) {
                String v = uri.getQueryParameter(name);
                if (looksLikeDolbyVisionText(name) || looksLikeDolbyVisionText(v)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean looksLikeDolbyVisionTextInternal(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        if (value.contains("杜比视界") || (value.contains("杜比") && value.contains("视界"))) {
            return true;
        }
        if (lower.contains("dolby vision")) {
            return true;
        }
        return lower.contains("dovi")
                || lower.contains("杜比视界")
                || lower.contains("杜比世界")
                || lower.contains("杜比视觉")
                || lower.contains("dolbyvision")
                || lower.contains("dolby_vision")
                || lower.contains("dolby-vision")
                || lower.contains("dvhe")
                || lower.contains("dvh1")
                || lower.contains("dvav")
                || lower.contains("dva1")
                || lower.contains("dvc1")
                || lower.contains("dvcc")
                || lower.contains("dvvc")
                || lower.contains("dvv1")
                || lower.contains("dv profile")
                || lower.contains("dv_profile")
                || lower.contains("dv-profile")
                || lower.contains("profile 5")
                || lower.contains("profile 8")
                || lower.contains(".dv.")
                || lower.contains("-dv-")
                || lower.contains("[dv]")
                || lower.contains("(dv)")
                || lower.matches(".*(^|[^a-z0-9])dv([^a-z0-9]|$).*");
    }

    private static String decodeRepeatedly(String value, int maxDepth) {
        String current = value;
        for (int i = 0; i < maxDepth; i++) {
            try {
                String decoded = URLDecoder.decode(current, "UTF-8");
                if (TextUtils.equals(decoded, current)) {
                    break;
                }
                current = decoded;
            } catch (Throwable ignored) {
                break;
            }
        }
        return current;
    }
}
