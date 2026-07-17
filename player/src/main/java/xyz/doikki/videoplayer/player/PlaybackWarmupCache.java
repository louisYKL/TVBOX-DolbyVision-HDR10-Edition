package xyz.doikki.videoplayer.player;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hands a short playback preflight read to the real streaming data source once.
 */
public final class PlaybackWarmupCache {
    private static final int MAX_ENTRIES = 4;
    private static final int MAX_BYTES = 512 * 1024;
    private static final long TTL_MS = 20_000L;
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>();

    private PlaybackWarmupCache() {
    }

    public static synchronized void offer(String url,
                                          Map<String, String> headers,
                                          byte[] data,
                                          long totalSize) {
        if (url == null || url.trim().isEmpty() || data == null || data.length == 0) {
            return;
        }
        pruneExpiredLocked(System.currentTimeMillis());
        int length = Math.min(MAX_BYTES, data.length);
        ENTRIES.put(buildKey(url, headers), new Entry(
                Arrays.copyOf(data, length), totalSize, System.currentTimeMillis()));
        while (ENTRIES.size() > MAX_ENTRIES) {
            String eldest = ENTRIES.keySet().iterator().next();
            ENTRIES.remove(eldest);
        }
    }

    static synchronized Entry take(String url, Map<String, String> headers) {
        long now = System.currentTimeMillis();
        pruneExpiredLocked(now);
        Entry entry = ENTRIES.remove(buildKey(url, headers));
        return entry != null && now - entry.createdAtMs <= TTL_MS ? entry : null;
    }

    private static void pruneExpiredLocked(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().createdAtMs > TTL_MS) {
                iterator.remove();
            }
        }
    }

    private static String buildKey(String url, Map<String, String> headers) {
        StringBuilder key = new StringBuilder(url == null ? "" : url.trim());
        TreeMap<String, String> normalizedHeaders = new TreeMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() == null || header.getValue() == null) {
                    continue;
                }
                String name = header.getKey().trim().toLowerCase(Locale.US);
                if (isTransportManagedHeader(name) || name.startsWith("x-tvbox-probe-")) {
                    continue;
                }
                normalizedHeaders.put(name, header.getValue().trim());
            }
        }
        for (Map.Entry<String, String> header : normalizedHeaders.entrySet()) {
            key.append('\n').append(header.getKey()).append(':').append(header.getValue());
        }
        return key.toString();
    }

    private static boolean isTransportManagedHeader(String name) {
        return "range".equals(name)
                || "accept-ranges".equals(name)
                || "content-range".equals(name)
                || "content-length".equals(name)
                || "connection".equals(name)
                || "accept-encoding".equals(name)
                || "host".equals(name);
    }

    static synchronized void clearForTests() {
        ENTRIES.clear();
    }

    static final class Entry {
        final byte[] data;
        final long totalSize;
        final long createdAtMs;

        Entry(byte[] data, long totalSize, long createdAtMs) {
            this.data = data;
            this.totalSize = totalSize;
            this.createdAtMs = createdAtMs;
        }
    }
}
