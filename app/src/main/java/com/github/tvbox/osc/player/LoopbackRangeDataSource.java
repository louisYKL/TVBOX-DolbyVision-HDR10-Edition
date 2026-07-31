package com.github.tvbox.osc.player;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.doikki.videoplayer.player.HttpRangeMediaDataSource;

/**
 * ExoPlayer adapter for the spider's direct 6677 VOD endpoint.
 *
 * <p>The endpoint closes or pauses large HTTP entities while the remote drive fills the next
 * piece. A plain ExoPlayer HTTP stream consequently drains its forward buffer at every entity
 * boundary. {@link HttpRangeMediaDataSource} already handles the endpoint's exact ranges,
 * bounded retries, a single range-request lane, and a small forward cache. This adapter keeps
 * that implementation on the system-player path instead of maintaining a second, uncached
 * Range implementation.</p>
 */
final class LoopbackRangeDataSource extends BaseDataSource {
    private static final long MALFORMED_RANGE_HEADER_SHIFT_BYTES = 8L;
    private static final long MAX_MALFORMED_RANGE_CORRECTION_BYTES = 64L;
    private static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory fallbackFactory;
        private final String userAgent;
        private final int connectTimeoutMs;
        private final int readTimeoutMs;
        // A progressive extractor can create more than one DataSource while it inspects a
        // container, restores saved progress, or seeks. Keep only lengths learned during this
        // player's current lifetime so a subsequent non-zero open is not forced back to an
        // unknown-length 416/EOF path.
        private final ConcurrentHashMap<String, Long> verifiedContentLengths = new ConcurrentHashMap<>();
        private volatile Map<String, String> defaultRequestHeaders = Collections.emptyMap();

        Factory(DataSource.Factory fallbackFactory,
                String userAgent,
                int connectTimeoutMs,
                int readTimeoutMs) {
            this.fallbackFactory = fallbackFactory;
            this.userAgent = userAgent;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }

        void setDefaultRequestHeaders(Map<String, String> headers) {
            if (headers == null || headers.isEmpty()) {
                defaultRequestHeaders = Collections.emptyMap();
                return;
            }
            defaultRequestHeaders = Collections.unmodifiableMap(new HashMap<>(headers));
        }

        @Override
        public DataSource createDataSource() {
            return new LoopbackRangeDataSource(
                    fallbackFactory.createDataSource(),
                    userAgent,
                    connectTimeoutMs,
                    readTimeoutMs,
                    defaultRequestHeaders,
                    verifiedContentLengths);
        }
    }

    private final DataSource fallback;
    // Kept in the factory for API compatibility and to make the direct-source policy explicit.
    // HttpRangeMediaDataSource owns the actual bounded network timeouts.
    @SuppressWarnings("unused")
    private final String userAgent;
    @SuppressWarnings("unused")
    private final int connectTimeoutMs;
    @SuppressWarnings("unused")
    private final int readTimeoutMs;
    private final Map<String, String> defaultRequestHeaders;
    private final ConcurrentHashMap<String, Long> verifiedContentLengths;

    private HttpRangeMediaDataSource rangeSource;
    private DataSpec dataSpec;
    private Uri openedUri;
    private boolean usingFallback;
    private boolean specialOpen;
    private boolean prebufferRequested;
    private long nextPosition;
    private long bytesRemaining;
    private long knownTotalLength = C.LENGTH_UNSET;
    private String lengthCacheKey;
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();

    private LoopbackRangeDataSource(DataSource fallback,
                                    String userAgent,
                                    int connectTimeoutMs,
                                    int readTimeoutMs,
                                    Map<String, String> defaultRequestHeaders,
                                    ConcurrentHashMap<String, Long> verifiedContentLengths) {
        super(true);
        this.fallback = fallback;
        this.userAgent = userAgent;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.defaultRequestHeaders = defaultRequestHeaders == null
                ? Collections.emptyMap() : defaultRequestHeaders;
        this.verifiedContentLengths = verifiedContentLengths == null
                ? new ConcurrentHashMap<>() : verifiedContentLengths;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        if (!shouldHandle(dataSpec)) {
            usingFallback = true;
            return fallback.open(dataSpec);
        }

        usingFallback = false;
        openedUri = dataSpec.uri;
        nextPosition = Math.max(0L, dataSpec.position);
        bytesRemaining = dataSpec.length;
        knownTotalLength = C.LENGTH_UNSET;
        lengthCacheKey = dataSpec.uri.toString();
        responseHeaders = Collections.emptyMap();
        prebufferRequested = false;
        transferInitializing(dataSpec);
        Map<String, String> headers = new HashMap<>(defaultRequestHeaders);
        if (dataSpec.httpRequestHeaders != null) {
            headers.putAll(dataSpec.httpRequestHeaders);
        }
        rangeSource = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                dataSpec.uri.toString(), headers, this::onRangeBytesTransferred);
        Long cachedTotalLength = verifiedContentLengths.get(lengthCacheKey);
        if (cachedTotalLength != null && cachedTotalLength > 0L) {
            rangeSource.primeKnownTotalSize(cachedTotalLength);
        }
        // Do not probe byte zero on every ExoPlayer open. Progressive playback commonly
        // reopens at a saved position or after a transient source retry; forcing getSize()
        // here made each reopen download a redundant 256KB header before the decoder could
        // read the requested range. HttpRangeMediaDataSource learns the total from the first
        // actual Content-Range response, while C.LENGTH_UNSET is a valid DataSource length.
        // A cached length is only primed into the Range source above; do not expose it as this
        // open's declared remaining length until the current request confirms it.
        specialOpen = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (usingFallback) {
            return fallback.read(buffer, offset, length);
        }
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0L || (knownTotalLength >= 0L && nextPosition >= knownTotalLength)) {
            return C.RESULT_END_OF_INPUT;
        }
        int requested = length;
        if (bytesRemaining != C.LENGTH_UNSET) {
            requested = (int) Math.min((long) requested, bytesRemaining);
        }
        if (requested <= 0) {
            return C.RESULT_END_OF_INPUT;
        }
        int count = rangeSource.readAt(nextPosition, buffer, offset, requested);
        long discoveredTotalLength = rangeSource.getKnownTotalSize();
        if (discoveredTotalLength >= 0L) {
            knownTotalLength = discoveredTotalLength;
            if (lengthCacheKey != null && discoveredTotalLength > 0L) {
                verifiedContentLengths.put(lengthCacheKey, discoveredTotalLength);
            }
            if (bytesRemaining == C.LENGTH_UNSET) {
                bytesRemaining = Math.max(0L, discoveredTotalLength - nextPosition);
            }
        }
        if (count <= 0) {
            if (knownTotalLength >= 0L && nextPosition >= knownTotalLength) {
                return C.RESULT_END_OF_INPUT;
            }
            throw new IOException("Direct loopback range returned no data at " + nextPosition);
        }
        nextPosition += count;
        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= count;
        }
        // Network bytes were reported while HttpRangeMediaDataSource read the HTTP body.
        // Reporting them again when this in-memory window is consumed would turn a steady
        // transfer into an artificial bursty/duplicated speed reading.
        return count;
    }

    private void onRangeBytesTransferred(int bytes) {
        if (bytes > 0 && specialOpen && !usingFallback) {
            bytesTransferred(bytes);
        }
    }

    @Override
    public Uri getUri() {
        return usingFallback ? fallback.getUri() : openedUri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return usingFallback ? fallback.getResponseHeaders() : responseHeaders;
    }

    @Override
    public void close() throws IOException {
        IOException closeFailure = null;
        if (usingFallback) {
            try {
                fallback.close();
            } catch (IOException error) {
                closeFailure = error;
            }
        } else {
            if (rangeSource != null) {
                try {
                    rangeSource.close();
                } catch (Throwable error) {
                    if (error instanceof IOException) {
                        closeFailure = (IOException) error;
                    }
                }
            }
            rangeSource = null;
            if (specialOpen) {
                specialOpen = false;
                transferEnded();
            }
        }
        usingFallback = false;
        dataSpec = null;
        openedUri = null;
        responseHeaders = Collections.emptyMap();
        nextPosition = 0L;
        bytesRemaining = 0L;
        knownTotalLength = C.LENGTH_UNSET;
        lengthCacheKey = null;
        prebufferRequested = false;
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void closeRangeSourceQuietly() {
        if (rangeSource == null) {
            return;
        }
        try {
            rangeSource.close();
        } catch (Throwable ignored) {
        }
        rangeSource = null;
    }

    static boolean shouldHandle(DataSpec dataSpec) {
        return dataSpec != null
                && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET
                && isLoopbackProxyPlayUrl(dataSpec.uri == null ? null : dataSpec.uri.toString());
    }

    static boolean isLoopbackProxyPlayUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            return ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && uri.getPort() == 6677
                    && path != null
                    && path.contains("/proxy/play/");
        } catch (Exception ignored) {
            return false;
        }
    }

    static long safeRangeEnd(long start, long byteCount) {
        if (byteCount <= 0L) {
            return start;
        }
        long delta = byteCount - 1L;
        return start > Long.MAX_VALUE - delta ? Long.MAX_VALUE : start + delta;
    }

    static long malformedRangeShift(String contentRange,
                                    long expectedStart,
                                    long requestedEnd) {
        return malformedRangeShift(ContentRange.parse(contentRange), expectedStart, requestedEnd);
    }

    private static long malformedRangeShift(@Nullable ContentRange range,
                                            long expectedStart,
                                            long requestedEnd) {
        if (range == null || range.start <= expectedStart || range.end < range.start) {
            return 0L;
        }
        long shift = range.start - expectedStart;
        if (shift <= 0L || shift > MAX_MALFORMED_RANGE_CORRECTION_BYTES
                || requestedEnd < shift || range.end != requestedEnd - shift) {
            return 0L;
        }
        return shift;
    }

    static boolean isExpectedMalformedHeader(String contentRange,
                                             long expectedStart,
                                             long requestedEnd) {
        return malformedRangeShift(contentRange, expectedStart, requestedEnd)
                == MALFORMED_RANGE_HEADER_SHIFT_BYTES;
    }

    static boolean isAcceptableRangeHeader(String contentRange,
                                           long expectedStart,
                                           long requestedEnd) {
        ContentRange range = ContentRange.parse(contentRange);
        return range != null
                && (range.start == expectedStart
                || malformedRangeShift(range, expectedStart, requestedEnd)
                == MALFORMED_RANGE_HEADER_SHIFT_BYTES);
    }

    static boolean shouldRetryRangeReset(int responseCode,
                                         String contentRange,
                                         long expectedStart) {
        ContentRange range = ContentRange.parse(contentRange);
        return expectedStart > 0L
                && responseCode == 200
                && (range == null || (range.start == 0L && range.end < expectedStart));
    }

    private static final class ContentRange {
        final long start;
        final long end;

        private ContentRange(long start, long end) {
            this.start = start;
            this.end = end;
        }

        static ContentRange parse(String value) {
            if (value == null) {
                return null;
            }
            try {
                String text = value.trim();
                if (!text.toLowerCase(Locale.US).startsWith("bytes")) {
                    return null;
                }
                int slash = text.indexOf('/');
                String rangePart = (slash < 0 ? text.substring("bytes".length())
                        : text.substring("bytes".length(), slash)).trim();
                int dash = rangePart.indexOf('-');
                if (dash <= 0 || dash + 1 >= rangePart.length()) {
                    return null;
                }
                long start = Long.parseLong(rangePart.substring(0, dash).trim());
                long end = Long.parseLong(rangePart.substring(dash + 1).trim());
                return start >= 0L && end >= start ? new ContentRange(start, end) : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
