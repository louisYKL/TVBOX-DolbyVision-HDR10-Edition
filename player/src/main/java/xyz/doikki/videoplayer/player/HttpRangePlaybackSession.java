package xyz.doikki.videoplayer.player;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns one bounded HTTP Range source for one logical playback URL.
 *
 * <p>ExoPlayer is allowed to close and recreate its DataSource while probing a container,
 * restoring a saved position, or seeking. Recreating {@link HttpRangeMediaDataSource} for each
 * of those opens discards the contiguous cache and starts another network lane. This session
 * keeps the source alive across those short-lived adapter objects and only releases it when the
 * URL/request headers change, {@link #reset()} is called, or the session is closed.</p>
 */
public final class HttpRangePlaybackSession implements Closeable {
    private final ReentrantLock rangeReadLock = new ReentrantLock(true);
    private final boolean prefetchEnabled;

    private SessionKey currentKey;
    private HttpRangeMediaDataSource currentSource;
    private boolean closed;

    /** Creates the normal session with bounded background fill-ahead enabled. */
    public HttpRangePlaybackSession() {
        this(true);
    }

    /** Package-private hook for deterministic JVM tests that isolate foreground Range reads. */
    HttpRangePlaybackSession(boolean prefetchEnabled) {
        this.prefetchEnabled = prefetchEnabled;
    }

    /**
     * Acquires the source for a URL/request-header identity.
     *
     * <p>The first acquire fixes the DataSpec's initial position. Later acquires for the same
     * identity update only the transfer listener; the source's initial position, cache, cursor,
     * and playback anchor remain intact.</p>
     */
    public synchronized HttpRangeMediaDataSource acquire(
            String url,
            Map<String, String> headers,
            long initialPosition,
            HttpRangeMediaDataSource.RangeTransferListener listener) throws IOException {
        if (closed) {
            throw new IOException("HTTP range playback session is closed");
        }
        SessionKey requestedKey = SessionKey.of(url, headers);
        if (currentSource != null && requestedKey.equals(currentKey)) {
            currentSource.setRangeTransferListener(listener);
            return currentSource;
        }

        closeCurrentLocked();
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback(
                        requestedKey.url,
                        requestedKey.headers,
                        listener,
                        prefetchEnabled,
                        rangeReadLock);
        source.setInitialDataSourcePosition(initialPosition);
        currentKey = requestedKey;
        currentSource = source;
        return source;
    }

    /** Returns the currently owned source, or {@code null} after reset/close. */
    public synchronized HttpRangeMediaDataSource getSource() {
        return currentSource;
    }

    /** Releases the current source and cache while keeping this session reusable. */
    public synchronized void reset() {
        closeCurrentLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeCurrentLocked();
    }

    private void closeCurrentLocked() {
        HttpRangeMediaDataSource source = currentSource;
        currentSource = null;
        currentKey = null;
        if (source != null) {
            source.close();
        }
    }

    private static final class SessionKey {
        private final String url;
        private final Map<String, String> headers;

        private SessionKey(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }

        private static SessionKey of(String url, Map<String, String> sourceHeaders)
                throws IOException {
            if (url == null || url.trim().isEmpty()) {
                throw new IOException("HTTP range playback URL is empty");
            }
            TreeMap<String, String> normalized = new TreeMap<>();
            if (sourceHeaders != null) {
                for (Map.Entry<String, String> entry : sourceHeaders.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
                    String value = entry.getValue().trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        normalized.put(key, value);
                    }
                }
            }
            return new SessionKey(url.trim(), Collections.unmodifiableMap(normalized));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SessionKey)) {
                return false;
            }
            SessionKey key = (SessionKey) other;
            return url.equals(key.url) && headers.equals(key.headers);
        }

        @Override
        public int hashCode() {
            int result = url.hashCode();
            result = 31 * result + headers.hashCode();
            return result;
        }
    }
}
