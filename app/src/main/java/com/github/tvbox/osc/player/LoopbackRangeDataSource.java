package com.github.tvbox.osc.player;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.github.tvbox.osc.util.PlaybackUrlNormalizer;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
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
        private final ConcurrentHashMap<String, Long> verifiedContentLengths = new ConcurrentHashMap<>();
        // Container header/index/sample opens are leases on the same bounded cache. Closing an
        // Exo DataSource must not discard the runway that the next open is about to consume.
        private final xyz.doikki.videoplayer.player.HttpRangePlaybackSession playbackSession =
                new xyz.doikki.videoplayer.player.HttpRangePlaybackSession();
        private volatile Map<String, String> defaultRequestHeaders = Collections.emptyMap();
        private volatile boolean playbackSeekPending;
        // A resumed progressive item first opens its container at byte zero. That header
        // probe is not the requested playback position and must not fill/release a VOD gate.
        private volatile boolean waitingForInitialResumeAnchor;
        private volatile boolean containerMetadataPending = true;
        private volatile SeekMap playbackSeekMap;
        private volatile long requestedPositionMs;
        private volatile long resolvedSeekByte = -1L;

        Factory(DataSource.Factory fallbackFactory, String userAgent,
                int connectTimeoutMs, int readTimeoutMs) {
            this.fallbackFactory = fallbackFactory;
            this.userAgent = userAgent;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }

        void setDefaultRequestHeaders(Map<String, String> headers) {
            defaultRequestHeaders = headers == null || headers.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(headers));
        }

        void resetPlaybackBufferSnapshot() {
            playbackSeekPending = false;
            waitingForInitialResumeAnchor = false;
            containerMetadataPending = true;
            playbackSeekMap = null;
            requestedPositionMs = 0L;
            resolvedSeekByte = -1L;
            playbackSession.reset();
        }

        void setInitialResumePosition(long positionMs) {
            requestedPositionMs = Math.max(0L, positionMs);
            containerMetadataPending = true;
            waitingForInitialResumeAnchor = positionMs > 0L;
            playbackSeekPending = true;
        }

        void release() {
            playbackSession.close();
            verifiedContentLengths.clear();
        }

        boolean isPlaybackSeekPending() {
            return playbackSeekPending;
        }

        @Override
        public DataSource createDataSource() {
            return new LoopbackRangeDataSource(this, fallbackFactory.createDataSource(),
                    userAgent, connectTimeoutMs, readTimeoutMs,
                    defaultRequestHeaders, verifiedContentLengths);
        }

        private HttpRangeMediaDataSource openRange(String url, Map<String, String> headers,
                long initialPosition, HttpRangeMediaDataSource.RangeTransferListener listener)
                throws IOException {
            HttpRangeMediaDataSource previous = playbackSession.getSource();
            HttpRangeMediaDataSource source = playbackSession.acquire(
                    url, headers, initialPosition, listener);
            if (containerMetadataPending) {
                source.setPlaybackFillAheadEnabled(false);
                source.setContainerMetadataMode(true);
            } else if (previous != source && resolvedSeekByte >= 0L) {
                source.onExtractorSeekStarted(resolvedSeekByte);
                playbackSeekPending = false;
            }
            // A reused source already received this seek notification. Resetting it at every
            // metadata reopen would replace the requested seek with the container's index read.
            if (previous != source && playbackSeekPending && resolvedSeekByte < 0L) {
                source.onPlaybackSeekStarted();
            }
            return source;
        }

        private int readRange(HttpRangeMediaDataSource source, long position,
                              byte[] buffer, int offset, int length) throws IOException {
            // Only the HTTP request lane is serialized inside the source. Cache hits never wait
            // for a background network window, and misses can cancel prefetch before waiting.
            int read = source.readAt(position, buffer, offset, length);
            if (read > 0 && !containerMetadataPending && !waitingForInitialResumeAnchor
                    && source.getPlaybackAnchorPositionSnapshot() >= 0L
                    && !source.isPlaybackSeekAnchorPending()) {
                if (source.isPlaybackSeekInProgress()) {
                    source.onPlaybackSeekFinished();
                }
                playbackSeekPending = false;
            }
            return read;
        }

        long getBufferedAheadBytes() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            return source == null ? 0L : source.getBufferedAheadBytes();
        }

        long getBufferedAheadBytesForProgress() {
            if (containerMetadataPending || waitingForInitialResumeAnchor || playbackSeekPending) {
                return 0L;
            }
            HttpRangeMediaDataSource source = playbackSession.getSource();
            return source == null ? 0L : source.getBufferedAheadBytesForProgress();
        }

        long getPlaybackStartBufferTargetBytes() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            return source == null ? 0L : source.getPlaybackStartBufferTargetBytes();
        }

        IOException getPlaybackCacheError() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            return source == null ? null : source.getPlaybackCacheError();
        }

        boolean hasRequiredBuffer(int requestedBytes) {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            if (source == null || containerMetadataPending || waitingForInitialResumeAnchor
                    || playbackSeekPending || source.isPlaybackSeekAnchorPending()) {
                return false;
            }
            // The source latches a verified runway for this startup/seek episode. It is reset
            // only on a real seek, not on container reopens or as the extractor consumes bytes.
            if (source.isStartupBufferGateSatisfied()) {
                return true;
            }
            return shouldReleaseFromUnanchoredStartup(false, source.getBufferedAheadBytes(),
                    source.getPlaybackStartBufferTargetBytes(), requestedBytes);
        }

        static boolean shouldUseUnanchoredStartup(boolean seekPending,
                long playbackAnchorPosition, boolean playbackSourceOpen,
                long playbackSourceInitialPosition) {
            return !seekPending && (!playbackSourceOpen || (playbackAnchorPosition >= 0L
                    ? playbackAnchorPosition == 0L : playbackSourceInitialPosition == 0L));
        }

        static boolean shouldReleaseFromRetainedSnapshot(boolean seekPending,
                long retainedBytes, long requiredBytes) {
            return false;
        }

        static boolean shouldReleaseFromUnanchoredStartup(boolean seekPending,
                long bufferedAheadBytes, long sourceTargetBytes, long requiredBytes) {
            long required = Math.min(Math.max(0L, sourceTargetBytes), Math.max(0L, requiredBytes));
            return !seekPending && required > 0L && Math.max(0L, bufferedAheadBytes) >= required;
        }

        void onPlaybackSeekStarted() {
            playbackSeekPending = true;
            resolvedSeekByte = -1L;
            HttpRangeMediaDataSource source = playbackSession.getSource();
            if (source != null) {
                source.onPlaybackSeekStarted();
            }
        }

        void onPlaybackSeekStarted(long positionMs) {
            requestedPositionMs = Math.max(0L, positionMs);
            onPlaybackSeekStarted();
            SeekMap seekMap = playbackSeekMap;
            if (seekMap != null) {
                // Exo may satisfy the seek from sample queues without calling Extractor.seek
                // or opening another DataSource. The retained seek map still gives the exact
                // preceding sync-point byte, so every direction starts a fresh 96 MiB runway.
                resolveSeekMapAnchor(seekMap);
            }
        }

        void onExtractorSeekStarted(long position, long timeUs) {
            // The initial seek(0, 0) precedes all container parsing. It is a header probe,
            // never evidence that the saved sample position has been resolved.
            if (containerMetadataPending) {
                return;
            }
            if (!isPlaybackAnchorResolved(waitingForInitialResumeAnchor, position, timeUs)) {
                return;
            }
            if (!playbackSeekPending && resolvedSeekByte == Math.max(0L, position)) {
                return;
            }
            waitingForInitialResumeAnchor = false;
            resolvedSeekByte = Math.max(0L, position);
            HttpRangeMediaDataSource source = playbackSession.getSource();
            if (source != null) {
                source.setContainerMetadataMode(false);
                source.onExtractorSeekStarted(position);
                source.setPlaybackFillAheadEnabled(true);
                playbackSeekPending = false;
            }
        }

        static boolean isPlaybackAnchorResolved(boolean waitingForResume,
                                                 long position, long timeUs) {
            return !waitingForResume || position > 0L || timeUs > 0L;
        }

        void onSeekMapResolved(SeekMap seekMap) {
            if (seekMap == null) {
                return;
            }
            playbackSeekMap = seekMap;
            if (containerMetadataPending || playbackSeekPending) {
                resolveSeekMapAnchor(seekMap);
            }
        }

        private void resolveSeekMapAnchor(SeekMap seekMap) {
            long timeUs = requestedPositionMs > Long.MAX_VALUE / 1000L
                    ? Long.MAX_VALUE : requestedPositionMs * 1000L;
            long bytePosition = seekMap.getSeekPoints(timeUs).first.position;
            containerMetadataPending = false;
            waitingForInitialResumeAnchor = false;
            onExtractorSeekStarted(bytePosition, timeUs);
        }

        void onMp3OnlySourceResolved() {
            // Mp3Extractor must remain unwrapped for Exo's stream handling and therefore
            // cannot report its byte seek here. Audio-only MP3 uses the time policy instead.
            waitingForInitialResumeAnchor = false;
            containerMetadataPending = false;
            playbackSeekPending = false;
            HttpRangeMediaDataSource source = playbackSession.getSource();
            if (source != null) {
                source.setContainerMetadataMode(false);
                source.setPlaybackFillAheadEnabled(true);
            }
        }

        void onPlaybackRebufferStarted() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            if (source != null && !waitingForInitialResumeAnchor && !playbackSeekPending) {
                source.beginPlaybackRebuffer();
            }
        }

        /** Called only after actual playback output, never for a READY notification alone. */
        void onPlaybackOutputResumed() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            if (source != null && !containerMetadataPending
                    && !waitingForInitialResumeAnchor && !playbackSeekPending) {
                source.finishPlaybackRebuffer();
            }
        }

        boolean hasPlaybackRangeSource() {
            return playbackSession.getSource() != null;
        }

        long getKnownTotalSize() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            return source == null ? C.LENGTH_UNSET : source.getKnownTotalSizeSnapshot();
        }

        String getBufferDiagnostics() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            if (source == null) {
                return "rangeSource=none seekPending=" + playbackSeekPending;
            }
            return "rangeSource=" + System.identityHashCode(source)
                    + " anchor=" + source.getPlaybackAnchorPositionSnapshot()
                    + " cachedBytes=" + source.getCachedBytesSnapshot()
                    + " arrivingAheadBytes=" + source.getBufferedAheadBytesForProgress()
                    + " totalBytes=" + source.getKnownTotalSizeSnapshot()
                    + " startupReady=" + source.isStartupBufferGateSatisfied()
                    + " seekPending=" + playbackSeekPending
                    + " resumeAnchorPending=" + waitingForInitialResumeAnchor
                    + " metadataPending=" + containerMetadataPending
                    + " anchorPending=" + source.isPlaybackSeekAnchorPending()
                    + " " + source.getCacheDiagnosticsSnapshot();
        }

        boolean hasWholeFileBuffered() {
            HttpRangeMediaDataSource source = playbackSession.getSource();
            return source != null && !waitingForInitialResumeAnchor
                    && !containerMetadataPending
                    && !playbackSeekPending && !source.isPlaybackSeekAnchorPending()
                    && isWholeFileBuffered(source.getKnownTotalSizeSnapshot(),
                    source.getPlaybackAnchorPositionSnapshot(), source.getBufferedAheadBytes());
        }
    }
    private final Factory factory;
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
    private long nextPosition;
    private long bytesRemaining;
    private long knownTotalLength = C.LENGTH_UNSET;
    private final RangeReadBuffer readBuffer = new RangeReadBuffer();
    private final RangeReadBuffer.Reader rangeReader = this::readUnbufferedRange;
    private String lengthCacheKey;
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();

    private LoopbackRangeDataSource(Factory factory,
                                    DataSource fallback,
                                    String userAgent,
                                    int connectTimeoutMs,
                                    int readTimeoutMs,
                                    Map<String, String> defaultRequestHeaders,
                                    ConcurrentHashMap<String, Long> verifiedContentLengths) {
        super(true);
        this.factory = factory;
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
        readBuffer.reset();
        closeRangeSourceQuietly();
        if (specialOpen) {
            specialOpen = false;
            transferEnded();
        }
        this.dataSpec = dataSpec;
        nextPosition = Math.max(0L, dataSpec.position);
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
        transferInitializing(dataSpec);
        Map<String, String> headers = new HashMap<>(defaultRequestHeaders);
        if (dataSpec.httpRequestHeaders != null) {
            headers.putAll(dataSpec.httpRequestHeaders);
        }
        // Metadata and tail probes do not claim the fill lane. After the first real decoder read,
        // the factory promotes this source and starts one serialized forward-fill lane. That lane
        // is required because ExoPlayer may stop requesting samples while the 96 MiB byte gate is
        // holding playback at READY; without it the first 24 MiB Range can deadlock startup.
        rangeSource = factory.openRange(dataSpec.uri.toString(), headers,
                dataSpec.position, this::onRangeBytesTransferred);
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
        if (rangeSource == null) {
            throw new IOException("Direct loopback range source is closed");
        }
        long remaining = bytesRemaining;
        if (knownTotalLength >= 0L) {
            long entityRemaining = Math.max(0L, knownTotalLength - nextPosition);
            remaining = remaining < 0L ? entityRemaining : Math.min(remaining, entityRemaining);
        }
        // DefaultExtractorInput forwards EBML varints as one-byte DataSource reads. Routing
        // every byte through the Range locks and cursor accounting made a cached Cue index
        // take minutes on A55 TVs. Coalesce only these tiny reads in a bounded 16 KiB buffer.
        int count = readBuffer.read(rangeReader, nextPosition, buffer, offset, requested, remaining);
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

    private int readUnbufferedRange(long position, byte[] buffer, int offset, int length)
            throws IOException {
        HttpRangeMediaDataSource source = rangeSource;
        if (source == null) {
            throw new IOException("Direct loopback range source is closed");
        }
        int count = factory.readRange(source, position, buffer, offset, length);
        // Consult the shared source only on a buffer refill. A cached EBML byte must not
        // acquire another source lock or box an 88 GiB Long into a ConcurrentHashMap.
        long discoveredTotalLength = source.getKnownTotalSize();
        if (discoveredTotalLength >= 0L) {
            if (knownTotalLength != discoveredTotalLength) {
                knownTotalLength = discoveredTotalLength;
                if (lengthCacheKey != null && discoveredTotalLength > 0L) {
                    verifiedContentLengths.put(lengthCacheKey, discoveredTotalLength);
                }
            }
            if (bytesRemaining == C.LENGTH_UNSET) {
                bytesRemaining = Math.max(0L, discoveredTotalLength - nextPosition);
            }
        }
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
        readBuffer.reset();
        IOException closeFailure = null;
        if (usingFallback) {
            try {
                fallback.close();
            } catch (IOException error) {
                closeFailure = error;
            }
        } else {
            closeRangeSourceQuietly();
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
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void closeRangeSourceQuietly() {
        // End this adapter lease. The factory owns the cache until the media session ends.
        rangeSource = null;
    }

    static boolean isWholeFileBuffered(long totalSize, long anchor, long bufferedAheadBytes) {
        if (totalSize <= 0L || anchor < 0L || anchor >= totalSize) {
            return false;
        }
        // bufferedAheadBytes is measured from the active playback anchor, not from byte zero.
        // Comparing it with the complete file length makes a resume that is already near EOF
        // wait forever (for example, 854 KiB remaining in an 88 GiB file).
        return Math.max(0L, bufferedAheadBytes) >= totalSize - anchor;
    }

    static boolean shouldHandle(DataSpec dataSpec) {
        return dataSpec != null
                && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET
                && shouldHandleVodUrl(dataSpec.uri == null ? null : dataSpec.uri.toString());
    }

    static boolean shouldHandleVodUrl(String value) {
        if (isLoopbackProxyPlayUrl(value)) {
            return true;
        }
        if (value == null || value.trim().isEmpty() || PlaybackUrlNormalizer.isHlsLike(value)) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || host == null
                    || "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || (path != null && path.toLowerCase(java.util.Locale.US).endsWith(".mpd"))) {
                return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
