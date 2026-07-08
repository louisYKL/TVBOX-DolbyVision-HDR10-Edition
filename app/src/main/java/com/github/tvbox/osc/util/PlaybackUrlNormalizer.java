package com.github.tvbox.osc.util;

import android.net.Uri;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;

import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public final class PlaybackUrlNormalizer {
    private PlaybackUrlNormalizer() {
    }

    public static UrlWithHeaders splitUrlAndHeaders(String path, Map<String, String> headers) {
        HashMap<String, String> mergedHeaders = headers == null ? new HashMap<>() : new HashMap<>(headers);
        if (TextUtils.isEmpty(path) || !path.contains("@")) {
            return new UrlWithHeaders(path, mergedHeaders);
        }
        String[] parts = path.split("@", 2);
        String cleanUrl = parts[0];
        String suffix = parts.length > 1 ? parts[1] : "";
        try {
            if (suffix.startsWith("Headers=")) {
                String rawJson = URLDecoder.decode(suffix.substring("Headers=".length()), "UTF-8");
                JSONObject jsonObject = new JSONObject(rawJson);
                for (Iterator<String> iterator = jsonObject.keys(); iterator.hasNext(); ) {
                    String key = iterator.next();
                    mergedHeaders.put(key, jsonObject.optString(key, ""));
                }
            } else {
                for (String token : suffix.split("@")) {
                    String[] kv = token.split("=", 2);
                    if (kv.length != 2) {
                        continue;
                    }
                    String key = kv[0];
                    String value = URLDecoder.decode(kv[1], "UTF-8");
                    if ("User-Agent".equalsIgnoreCase(key) || "Referer".equalsIgnoreCase(key) || "Origin".equalsIgnoreCase(key) || "Cookie".equalsIgnoreCase(key)) {
                        mergedHeaders.put(key, value);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new UrlWithHeaders(cleanUrl, mergedHeaders);
    }

    public static String normalizeHttpUrl(String path) {
        if (TextUtils.isEmpty(path)) {
            return path;
        }
        try {
            Uri parsed = Uri.parse(path);
            if (TextUtils.isEmpty(parsed.getScheme()) || parsed.isOpaque()) {
                return path;
            }
            URI normalized = new URI(
                    parsed.getScheme(),
                    parsed.getUserInfo(),
                    parsed.getHost(),
                    parsed.getPort(),
                    parsed.getPath(),
                    parsed.getQuery(),
                    parsed.getFragment()
            );
            return normalized.toASCIIString();
        } catch (Exception ignored) {
            return path;
        }
    }

    public static String resolvePlaybackUrl(String path, Map<String, String> headers, boolean live) {
        if (TextUtils.isEmpty(path)) {
            return path;
        }
        String normalizedPath = normalizeHttpUrl(path);
        if (isAppLocalProxyUrl(normalizedPath)) {
            String nested = unwrapAppStreamProxyToForeignLocalPlay(normalizedPath);
            if (!TextUtils.isEmpty(nested)) {
                return nested;
            }
            return normalizedPath;
        }
        if (!shouldProxy(normalizedPath, live)) {
            return normalizedPath;
        }
        String localAddress = ControlManager.get().getAddress(true);
        if (TextUtils.isEmpty(localAddress)) {
            return normalizedPath;
        }
        try {
            StringBuilder builder = new StringBuilder(localAddress)
                    .append("proxy?go=")
                    .append(live ? "live&type=m3u8" : "bom")
                    .append("&url=")
                    .append(URLEncoder.encode(normalizedPath, "UTF-8"));
            appendHeaders(builder, headers);
            return builder.toString();
        } catch (Exception ignored) {
            return normalizedPath;
        }
    }

    public static String resolveSystemPlaybackUrl(String path, Map<String, String> headers, boolean live) {
        if (TextUtils.isEmpty(path)) {
            return path;
        }
        String normalizedPath = normalizeHttpUrl(path);
        if (isAnyLocalProxyPlayUrl(normalizedPath)) {
            LOG.i("echo-playback-url direct-local-proxy-play -> " + safeSnippet(normalizedPath));
            return normalizedPath;
        }
        if (isAppLocalProxyUrl(normalizedPath)) {
            String nested = unwrapAppStreamProxyToForeignLocalPlay(normalizedPath);
            if (!TextUtils.isEmpty(nested) && !live && !isHlsLike(nested)) {
                LOG.i("echo-playback-url unwrap-app-stream -> " + safeSnippet(nested));
                return nested;
            }
            if (!live && isMatroskaLike(normalizedPath)) {
                LOG.i("echo-playback-url keep-app-stream-matroska -> " + safeSnippet(normalizedPath));
                return normalizedPath;
            }
            if (!TextUtils.isEmpty(nested)) {
                LOG.i("echo-playback-url unwrap-app-stream -> " + safeSnippet(nested));
                return nested;
            }
            return normalizedPath;
        }
        if (isForeignLocalProxyPlayUrl(normalizedPath)) {
            if (!live && !isHlsLike(normalizedPath)) {
                LOG.i("echo-playback-url direct-foreign-local-play -> " + safeSnippet(normalizedPath));
                return normalizedPath;
            }
            LOG.i("echo-playback-url wrap-foreign-local-play -> " + safeSnippet(normalizedPath));
            return wrapWithStreamProxy(normalizedPath, headers);
        }
        if (isForeignLocalProxyUrl(normalizedPath)) {
            if (!live && !isHlsLike(normalizedPath)) {
                LOG.i("echo-playback-url direct-foreign-local-proxy -> " + safeSnippet(normalizedPath));
                return normalizedPath;
            }
            return wrapWithStreamProxy(normalizedPath, headers);
        }
        // Keep direct system-player playback for regular VOD files so TV firmware can
        // engage the native hardware video pipeline, HDR mode, and vendor post-processing.
        if (!live && !isHlsLike(normalizedPath)) {
            return normalizedPath;
        }
        return resolvePlaybackUrl(normalizedPath, headers, live);
    }

    public static String resolveSystemFallbackPlaybackUrl(String path, Map<String, String> headers, boolean live) {
        if (TextUtils.isEmpty(path)) {
            return path;
        }
        String normalizedPath = normalizeHttpUrl(path);
        return resolveSystemPlaybackUrl(normalizedPath, headers, live);
    }

    public static String resolveCompatPlaybackUrl(String path, Map<String, String> headers, boolean live) {
        if (TextUtils.isEmpty(path)) {
            return path;
        }
        String normalizedPath = normalizeHttpUrl(path);
        if (isAnyLocalProxyPlayUrl(normalizedPath)) {
            LOG.i("echo-playback-url compat-direct-local-proxy-play -> " + safeSnippet(normalizedPath));
            return normalizedPath;
        }
        if (isAppLocalProxyUrl(normalizedPath)) {
            String nested = unwrapAppStreamProxyToForeignLocalPlay(normalizedPath);
            if (!TextUtils.isEmpty(nested)) {
                LOG.i("echo-playback-url compat-unwrap-app-stream -> " + safeSnippet(nested));
                return nested;
            }
            LOG.i("echo-playback-url compat-direct-app-local-proxy -> " + safeSnippet(normalizedPath));
            return normalizedPath;
        }
        if (isForeignLocalProxyPlayUrl(normalizedPath)) {
            LOG.i("echo-playback-url compat-direct-foreign-local-play -> " + safeSnippet(normalizedPath));
            return normalizedPath;
        }
        if (isForeignLocalProxyUrl(normalizedPath)) {
            LOG.i("echo-playback-url compat-direct-foreign-local-proxy -> " + safeSnippet(normalizedPath));
            return normalizedPath;
        }
        if (!live && !isHlsLike(normalizedPath)) {
            return normalizedPath;
        }
        return resolvePlaybackUrl(normalizedPath, headers, live);
    }

    public static boolean isHlsLike(String uri) {
        if (TextUtils.isEmpty(uri)) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.US);
        if (lower.contains(".m3u8") || lower.contains("type=hls") || lower.contains("format=hls")) {
            return true;
        }
        try {
            Uri parsedUri = Uri.parse(uri);
            String path = parsedUri.getPath();
            if (path == null) {
                return false;
            }
            path = path.toLowerCase(Locale.US);
            return path.endsWith("/live.php")
                    || path.contains("/live/")
                    || path.contains("/playlist/")
                    || path.contains("/m3u8")
                    || path.contains("index.m3u");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean shouldProxy(String path, boolean live) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        if (lower.startsWith("rtmp://") || lower.startsWith("rtsp://") || lower.startsWith("udp://") || lower.startsWith("rtp://") || lower.startsWith("data:")) {
            return false;
        }
        if (live) {
            return isHlsLike(lower);
        }
        return lower.contains(".m3u8");
    }

    private static boolean isMatroskaLike(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.contains(".mkv") || lower.contains(".webm");
    }

    private static String wrapWithStreamProxy(String path, Map<String, String> headers) {
        String localAddress = ControlManager.get().getAddress(true);
        if (TextUtils.isEmpty(localAddress)) {
            return path;
        }
        try {
            StringBuilder builder = new StringBuilder(localAddress)
                    .append("proxy?go=stream")
                    .append("&url=")
                    .append(URLEncoder.encode(path, "UTF-8"));
            appendHeaders(builder, headers);
            return builder.toString();
        } catch (Exception ignored) {
            return path;
        }
    }

    private static boolean isLocalProxyUrl(String path) {
        return !TextUtils.isEmpty(path) && (path.startsWith("http://127.0.0.1")
                || path.startsWith("https://127.0.0.1")
                || path.startsWith("http://localhost")
                || path.startsWith("https://localhost"));
    }

    private static boolean isAnyLocalProxyPlayUrl(String path) {
        if (!isLocalProxyUrl(path)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(path);
            String valuePath = uri.getPath();
            return valuePath != null && valuePath.contains("/proxy/play/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAppLocalProxyUrl(String path) {
        if (!isLocalProxyUrl(path)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(path);
            String localAddress = ControlManager.get().getAddress(true);
            if (!TextUtils.isEmpty(localAddress)) {
                Uri appUri = Uri.parse(localAddress);
                if (appUri != null
                        && appUri.getPort() > 0
                        && uri != null
                        && uri.getPort() == appUri.getPort()) {
                    return true;
                }
                if (path.startsWith(localAddress) && uri != null && uri.getPort() == 9978) {
                    return true;
                }
            }
            if (uri != null && uri.getPort() == 9978) {
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isForeignLocalProxyUrl(String path) {
        return isLocalProxyUrl(path) && !isAppLocalProxyUrl(path);
    }

    private static boolean isForeignLocalProxyPlayUrl(String path) {
        if (!isForeignLocalProxyUrl(path)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(path);
            String valuePath = uri.getPath();
            return valuePath != null && valuePath.contains("/proxy/play/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String unwrapAppStreamProxyToForeignLocalPlay(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(path);
            String host = uri.getHost();
            String go = uri.getQueryParameter("go");
            String nestedUrl = uri.getQueryParameter("url");
            if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    || (!"stream".equalsIgnoreCase(go) && !"play".equalsIgnoreCase(go))
                    || TextUtils.isEmpty(nestedUrl)) {
                return null;
            }
            String nestedNormalized = normalizeHttpUrl(nestedUrl);
            if (isForeignLocalProxyPlayUrl(nestedNormalized)) {
                return nestedNormalized;
            }
            String doubleNested = unwrapAppStreamProxyToForeignLocalPlay(nestedNormalized);
            if (!TextUtils.isEmpty(doubleNested)) {
                return doubleNested;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void appendHeaders(StringBuilder builder, Map<String, String> headers) {
        builder.append(encodeHeadersQuery(headers));
    }

    private static String safeSnippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 220 ? value.substring(0, 220) : value;
    }

    public static String encodeHeadersQuery(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        try {
            JSONObject headerJson = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                if (isInternalPlaybackHeader(key)) {
                    continue;
                }
                headerJson.put(key, value.trim());
            }
            if (headerJson.length() > 0) {
                return "&header=" + URLEncoder.encode(headerJson.toString(), "UTF-8");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static boolean isInternalPlaybackHeader(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        String lower = key.trim().toLowerCase(Locale.US);
        return lower.startsWith("x-tvbox-probe-");
    }

    private static boolean hasInternalHeaderValue(Map<String, String> headers, String headerName, String expectedValue) {
        if (headers == null || TextUtils.isEmpty(headerName) || TextUtils.isEmpty(expectedValue)) {
            return false;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && headerName.equalsIgnoreCase(entry.getKey())
                    && entry.getValue() != null
                    && expectedValue.equalsIgnoreCase(entry.getValue().trim())) {
                return true;
            }
        }
        return false;
    }

    public static final class UrlWithHeaders {
        public final String url;
        public final Map<String, String> headers;

        public UrlWithHeaders(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }
}
