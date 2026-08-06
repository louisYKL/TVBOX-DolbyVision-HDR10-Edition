package xyz.doikki.videoplayer.player;

import android.annotation.SuppressLint;
import android.media.MediaDataSource;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@SuppressLint("NewApi")
public final class HttpRangeMediaDataSource extends MediaDataSource {
    private static final String TAG = "HttpRangeDataSource";
    private static final long DEFAULT_WINDOW_SIZE = 512L * 1024L;
    private static final long MAX_WINDOW_SIZE = 2L * 1024L * 1024L;
    private static final long STREAMING_DEFAULT_WINDOW_SIZE = resolveStreamingDefaultWindowSize();
    private static final long STREAMING_MAX_WINDOW_SIZE = resolveStreamingMaxWindowSize();
    private static final long STREAMING_PREFETCH_WINDOW_SIZE = resolveStreamingPrefetchWindowSize();
    private static final long STREAMING_START_BUFFER_SIZE = resolveStreamingStartBufferSize();
    private static final long STREAMING_TARGET_BUFFER_SIZE = resolveStreamingTargetBufferSize();
    private static final long STREAMING_MAX_CACHE_SIZE = resolveStreamingMaxCacheSize();
    private static final long STREAMING_BACK_BUFFER_SIZE = resolveStreamingBackBufferSize();
    private static final long PROBE_WINDOW_SIZE = 256L * 1024L;
    // The direct 6677 provider occasionally reports a bounded entity with an eight-byte
    // Content-Range header shift while the body still starts at the requested logical offset.
    // Keep the exception in response-header parsing only. The physical HTTP Range request must
    // always use the same coordinates that the decoder requested; expanding it would prepend
    // unrelated bytes and corrupt EBML/Matroska parsing.
    private static final long LOOPBACK_MALFORMED_RANGE_HEADER_SHIFT_BYTES = 8L;
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] EBML_SIGNATURE = new byte[]{
            0x1A, 0x45, (byte) 0xDF, (byte) 0xA3
    };
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 150L;
    private static final String HEADER_PROBE_CONTAINER = "x-tvbox-probe-container";
    private static final String HEADER_PROBE_DOLBY_VISION = "x-tvbox-probe-dolbyvision";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    // Keep one background fill-ahead task. Foreground decoder reads cancel that task when
    // they need a different range, so metadata parsing and seeks never wait behind preload.
    private static final ExecutorService PREFETCH_EXECUTOR = Executors.newSingleThreadExecutor();

    private final String url;
    private final HashMap<String, String> headers = new HashMap<>();
    private final long defaultWindowSize;
    private final long maxWindowSize;
    private final long prefetchWindowSize;
    private final boolean prefetchEnabled;
    private final boolean interruptForegroundReadOnSeek;
    // Only the system ExoPlayer route may commit a verified contiguous short prefix. Legacy
    // MediaDataSource paths keep strict entity-length validation and retry incomplete bodies.
    private final boolean allowVerifiedShortPayload;
    private final RangeTransferListener rangeTransferListener;
    private volatile boolean closed;
    private volatile long totalSize = -1L;
    private long windowStart = -1L;
    private long windowEnd = -1L;
    private byte[] windowData = new byte[0];
    private final NavigableMap<Long, WindowData> cachedWindows = new TreeMap<>();
    private long cachedBytes;
    private long playbackAnchorPosition = -1L;
    private long lastReadPosition = -1L;
    private volatile boolean playbackPrebufferRequested;
    private volatile boolean playbackPrebufferActive;
    private volatile long playbackPrebufferAnchorPosition = -1L;
    private volatile long bufferedAheadBytesSnapshot;
    private volatile long playbackStartBufferTargetSnapshot;
    private volatile long cachedBytesSnapshot;
    private int debugLoadCount;
    private int debugResponseCount;
    private int debugReadCount;
    private int debugPrefetchCount;
    private volatile Future<?> prefetchFuture;
    private long scheduledPrefetchStart = -1L;
    private volatile long activePrefetchId = -1L;
    private long nextPrefetchId;
    private volatile boolean playbackSeekInProgress;
    private final AtomicLong playbackSeekGeneration = new AtomicLong();
    private final AtomicReference<Call> activePlaybackCall = new AtomicReference<>();
    private final ThreadLocal<Long> foregroundReadGeneration = new ThreadLocal<>();
    private final ConcurrentHashMap<Long, Call> activePrefetchCalls = new ConcurrentHashMap<>();
    // AppFuse can request a missing range for tens of seconds. Keep UI-side cache polling
    // out of that blocking read so loading a video never stalls the Android main thread.
    private final ReentrantLock blockingReadLock = new ReentrantLock();
    // Keep foreground decoder reads and fill-ahead reads mutually exclusive per source.
    // Overlapping requests compete for the same proxy stream and cause severe frame drops.
    private final ReentrantLock rangeRequestLock = new ReentrantLock();
    private static Method sRuntimeLogInfoMethod;
    private static boolean sRuntimeLogLookupDone;

    /** Receives bytes at the moment the HTTP body supplies them. */
    public interface RangeTransferListener {
        void onBytesTransferred(int bytes);
    }

    public HttpRangeMediaDataSource(String url, Map<String, String> headers) {
        this(url, headers, DEFAULT_WINDOW_SIZE, MAX_WINDOW_SIZE, false, null);
    }

    public static HttpRangeMediaDataSource createForStreamingPlayback(String url, Map<String, String> headers) {
        return new HttpRangeMediaDataSource(url, headers,
                STREAMING_DEFAULT_WINDOW_SIZE, STREAMING_MAX_WINDOW_SIZE, false, null);
    }

    /**
     * Range-backed source for the ExoPlayer system-codec path. ExoPlayer already owns one
     * sequential loading loop; a second asynchronous prefetch lane only makes the 6677 proxy
     * cancel and reopen the same range. Use a larger bounded foreground window instead so each
     * request gets the endpoint's efficient 8-16 MiB transfer without competing connections.
     */
    public static HttpRangeMediaDataSource createForSystemStreamingPlayback(
            String url, Map<String, String> headers) {
        return createForSystemStreamingPlayback(url, headers, null);
    }

    public static HttpRangeMediaDataSource createForSystemStreamingPlayback(
            String url, Map<String, String> headers, RangeTransferListener transferListener) {
        return new HttpRangeMediaDataSource(url, headers,
                8L * 1024L * 1024L,
                16L * 1024L * 1024L,
                false,
                Boolean.FALSE,
                true,
                transferListener);
    }

    static HttpRangeMediaDataSource createForProxyFileDescriptor(String url,
                                                                  Map<String, String> headers) {
        return new HttpRangeMediaDataSource(url, headers,
                STREAMING_DEFAULT_WINDOW_SIZE, STREAMING_MAX_WINDOW_SIZE, true, null);
    }

    private HttpRangeMediaDataSource(String url,
                                     Map<String, String> headers,
                                     long defaultWindowSize,
                                     long maxWindowSize,
                                     boolean interruptForegroundReadOnSeek,
                                     Boolean prefetchEnabledOverride) {
        this(url, headers, defaultWindowSize, maxWindowSize, interruptForegroundReadOnSeek,
                prefetchEnabledOverride, false, null);
    }

    private HttpRangeMediaDataSource(String url,
                                     Map<String, String> headers,
                                     long defaultWindowSize,
                                     long maxWindowSize,
                                     boolean interruptForegroundReadOnSeek,
                                     Boolean prefetchEnabledOverride,
                                     boolean allowVerifiedShortPayload,
                                     RangeTransferListener transferListener) {
        this.url = url;
        this.defaultWindowSize = Math.max(DEFAULT_WINDOW_SIZE, defaultWindowSize);
        this.maxWindowSize = Math.max(this.defaultWindowSize, maxWindowSize);
        this.prefetchWindowSize = Math.max(this.defaultWindowSize, STREAMING_PREFETCH_WINDOW_SIZE);
        this.prefetchEnabled = prefetchEnabledOverride == null
                ? this.defaultWindowSize >= STREAMING_DEFAULT_WINDOW_SIZE
                : prefetchEnabledOverride;
        this.allowVerifiedShortPayload = allowVerifiedShortPayload;
        this.rangeTransferListener = transferListener;
        this.interruptForegroundReadOnSeek = interruptForegroundReadOnSeek;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = entry.getKey().trim();
                if (isManagedRequestHeader(key)) {
                    continue;
                }
                String value = entry.getValue().trim();
                if (!value.isEmpty()) {
                    this.headers.put(key, value);
                }
            }
        }
        if (prefetchEnabled) {
            PlaybackWarmupCache.Entry warmup = PlaybackWarmupCache.take(url, this.headers);
            if (warmup != null && warmup.data.length > 0) {
                WindowData window = new WindowData(0L, warmup.data.length - 1L,
                        warmup.data, warmup.totalSize,
                        0L, warmup.data.length - 1L, warmup.data.length - 1L, 206);
                cachedWindows.put(0L, window);
                cachedBytes = warmup.data.length;
                applyWindowLocked(window);
                refreshBufferSnapshotsLocked();
            }
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        final long readGeneration = playbackSeekGeneration.get();
        blockingReadLock.lock();
        foregroundReadGeneration.set(readGeneration);
        try {
            synchronized (this) {
                if (closed) {
                    throw new IOException("MediaDataSource already closed");
                }
                if (size <= 0) {
                    return 0;
                }
                if (position < 0L) {
                    return -1;
                }
                if (totalSize >= 0L && position >= totalSize) {
                    return -1;
                }
                notePlaybackReadLocked(position);
                int totalCopied = 0;
                long cursor = position;
                int requestedSize = size;
                while (size > 0) {
                    ensureWindowLoaded(cursor, size);
                    if (totalSize >= 0L && cursor >= totalSize) {
                        break;
                    }
                    if (windowData.length == 0 || cursor < windowStart || cursor > windowEnd) {
                        break;
                    }
                    int startIndex = (int) (cursor - windowStart);
                    int available = windowData.length - startIndex;
                    if (available <= 0) {
                        break;
                    }
                    int copyLength = Math.min(size, available);
                    System.arraycopy(windowData, startIndex, buffer, offset, copyLength);
                    offset += copyLength;
                    size -= copyLength;
                    totalCopied += copyLength;
                    cursor += copyLength;
                    notePlaybackReadLocked(cursor);
                    if (copyLength < available) {
                        break;
                    }
                    if (totalSize >= 0L && cursor >= totalSize) {
                        break;
                    }
                }
                if (totalCopied > 0) {
                    // Schedule fill-ahead only after this read is complete. If one decoder
                    // request spans two windows, scheduling in the middle of the loop would
                    // immediately be cancelled by the next foreground cache miss.
                    maybeSchedulePrefetchLocked(cursor, requestedSize);
                }
                if (debugReadCount < 8) {
                    debugReadCount++;
                    logInfo("echo-range-source readAt pos=" + position + " size=" + requestedSize + " copied=" + totalCopied + " total=" + totalSize);
                }
                if (readGeneration != playbackSeekGeneration.get()) {
                    logSupersededRead(position, readGeneration);
                    throw new PlaybackReadSupersededException(position, readGeneration);
                }
                return totalCopied > 0 ? totalCopied : -1;
            }
        } catch (IOException error) {
            if (readGeneration != playbackSeekGeneration.get()) {
                logSupersededRead(position, readGeneration);
                throw new PlaybackReadSupersededException(position, readGeneration);
            }
            throw error;
        } finally {
            foregroundReadGeneration.remove();
            blockingReadLock.unlock();
        }
    }

    @Override
    public long getSize() throws IOException {
        blockingReadLock.lock();
        try {
            synchronized (this) {
                if (closed) {
                    throw new IOException("MediaDataSource already closed");
                }
                ensureSizeKnown();
                return totalSize;
            }
        } finally {
            blockingReadLock.unlock();
        }
    }

    /**
     * Returns the length learned from a completed Range response without issuing another request.
     * A system DataSource may intentionally skip the eager byte-zero size probe, so its adapter
     * uses this snapshot to terminate cleanly once the first real read has exposed Content-Range.
     */
    public long getKnownTotalSize() {
        return totalSize;
    }

    /**
     * Seeds a length that was established by an earlier DataSource open for the same active
     * playback URL. ExoPlayer can create a fresh DataSource for a saved-position read, while the
     * local 6677 endpoint may temporarily reject that non-zero range before it exposes the whole
     * file again. Carrying the verified length forward prevents that temporary 416 from becoming
     * a false end-of-input.
     */
    public void primeKnownTotalSize(long verifiedTotalSize) {
        if (verifiedTotalSize <= 0L) {
            return;
        }
        synchronized (this) {
            if (!closed && totalSize < 0L) {
                totalSize = verifiedTotalSize;
                refreshBufferSnapshotsLocked();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelActivePlaybackCall();
        cancelAllPrefetchCalls();
        // Never wait for an in-flight network read on the activity thread during release.
        // The read observes closed after its request and the object can then be collected.
        if (!blockingReadLock.tryLock()) {
            return;
        }
        try {
            synchronized (this) {
                windowData = new byte[0];
                windowStart = -1L;
                windowEnd = -1L;
                cachedWindows.clear();
                cachedBytes = 0L;
                bufferedAheadBytesSnapshot = 0L;
                playbackStartBufferTargetSnapshot = 0L;
                cachedBytesSnapshot = 0L;
                playbackAnchorPosition = -1L;
                lastReadPosition = -1L;
                playbackPrebufferRequested = false;
                playbackPrebufferActive = false;
                playbackPrebufferAnchorPosition = -1L;
                cancelActivePrefetchLocked();
            }
        } finally {
            blockingReadLock.unlock();
        }
    }

    private void ensureSizeKnown() throws IOException {
        if (totalSize >= 0L) {
            if (prefetchEnabled && windowData.length > 0) {
                scheduleFillAheadLocked(0L);
            }
            return;
        }
        loadWindow(0L, PROBE_WINDOW_SIZE, 0L);
        if (totalSize < 0L) {
            loadWindow(0L, Long.MAX_VALUE, 0L);
        }
        if (totalSize < 0L) {
            throw new IOException("Unable to determine content length for " + url);
        }
        // Start filling the playback cache as soon as the container size is known. This keeps
        // the first MediaPlayer read small while the rest of the buffer is fetched off-thread.
        if (prefetchEnabled && windowData.length > 0) {
            scheduleFillAheadLocked(0L);
        }
    }

    private void ensureWindowLoaded(long position, int requestedSize) throws IOException {
        WindowData cached = findCachedWindowLocked(position);
        if (cached != null) {
            applyWindowLocked(cached);
            maybeSchedulePrefetchLocked(position, requestedSize);
            return;
        }
        long alignedStart;
        long windowSize;
        if (prefetchEnabled) {
            RangeWindowPolicy.Window foregroundWindow = RangeWindowPolicy.resolveForegroundWindow(
                    position, requestedSize, defaultWindowSize, maxWindowSize);
            // A cache miss occurs at the decoder cursor. Reusing the aligned block start
            // would download the already cached prefix for a second time.
            alignedStart = Math.max(0L, position);
            windowSize = foregroundWindow.size;
        } else {
            alignedStart = Math.max(0L, position);
            windowSize = Math.min(maxWindowSize,
                    Math.max(defaultWindowSize, (long) requestedSize * 2L));
        }
        loadWindow(alignedStart, windowSize, position);
        maybeSchedulePrefetchLocked(position, requestedSize);
    }

    private void loadWindow(long start, long minWindowSize, long requestedPosition) throws IOException {
        cancelInflightPrefetchForForegroundReadLocked();
        WindowData loaded = requestWindowData(start, minWindowSize);
        if (closed) {
            throw new IOException("MediaDataSource closed during range read");
        }
        storeWindowLocked(loaded);
        WindowData active = findCachedWindowLocked(requestedPosition);
        applyWindowLocked(active == null ? loaded : active);
        if (debugLoadCount < 12) {
            debugLoadCount++;
            logInfo("echo-range-source loadWindow start=" + loaded.start
                    + " end=" + loaded.requestedEnd
                    + " payload=" + loaded.payloadStart + "-" + loaded.payloadEnd
                    + " read=" + loaded.data.length
                    + " total=" + loaded.totalSize
                    + " code=" + loaded.responseCode);
        }
    }

    private long getKnownTotalSizeSnapshot() {
        return totalSize;
    }

    private long alignWindowStart(long position) {
        if (!prefetchEnabled || position <= 0L) {
            return Math.max(0L, position);
        }
        long blockSize = Math.max(DEFAULT_WINDOW_SIZE, defaultWindowSize);
        return position - (position % blockSize);
    }

    private void notePlaybackReadLocked(long position) {
        // Keep the playback anchor even when this source uses ExoPlayer's single foreground
        // loading lane (prefetchEnabled=false). Cache trimming must know which windows are
        // behind the decoder; otherwise it evicts the newest window and the next read fetches
        // the same Range again, producing the observed slow/zero-speed oscillation.
        if (position < 0L) {
            return;
        }
        if (isAuxiliaryTailProbeLocked(position)) {
            return;
        }
        if (playbackAnchorPosition < 0L) {
            playbackAnchorPosition = position;
            lastReadPosition = position;
            if (position >= defaultWindowSize * 4L) {
                cancelActivePrefetchLocked();
            }
            refreshBufferSnapshotsLocked();
            return;
        }
        long delta = lastReadPosition < 0L ? 0L : position - lastReadPosition;
        boolean discontinuity = Math.abs(delta) >= defaultWindowSize * 4L;
        if (delta >= 0L && delta <= defaultWindowSize * 4L) {
            playbackAnchorPosition = position;
        } else if (discontinuity) {
            playbackAnchorPosition = position;
            // A saved-position restore or a user seek has moved the decoder to a new
            // byte range. Do not let the old-position prefetch consume the connection
            // while the new playback range is still empty.
            cancelActivePrefetchLocked();
        }
        lastReadPosition = position;
        syncPlaybackPrebufferStateLocked(position);
        refreshBufferSnapshotsLocked();
    }

    /** Starts or continues background prefetch around the current playback lane. */
    public void requestPlaybackPrebuffer() {
        playbackPrebufferRequested = true;
        // A native AppFuse read may own the lock while fetching a missing range. Starting
        // playback takes priority, so this hint returns immediately instead of waiting.
        if (!blockingReadLock.tryLock()) {
            return;
        }
        try {
            synchronized (this) {
                if (closed || totalSize <= 0L) {
                    return;
                }
                if (playbackAnchorPosition < 0L) {
                    playbackAnchorPosition = 0L;
                    lastReadPosition = 0L;
                }
                syncPlaybackPrebufferStateLocked(playbackAnchorPosition);
                scheduleFillAheadLocked(resolveBufferAnchorLocked(playbackAnchorPosition));
            }
        } finally {
            blockingReadLock.unlock();
        }
    }

    public void finishPlaybackPrebuffer() {
        playbackPrebufferRequested = false;
        if (!blockingReadLock.tryLock()) {
            return;
        }
        try {
            synchronized (this) {
                syncPlaybackPrebufferStateLocked(playbackAnchorPosition);
                scheduleFillAheadLocked(resolveBufferAnchorLocked(playbackAnchorPosition));
            }
        } finally {
            blockingReadLock.unlock();
        }
    }

    /**
     * Gives a decoder seek priority over fill-ahead traffic without blocking the caller.
     */
    public void onPlaybackSeekStarted() {
        if (closed) {
            return;
        }
        playbackSeekInProgress = true;
        playbackPrebufferRequested = false;
        playbackPrebufferActive = false;
        playbackPrebufferAnchorPosition = -1L;
        bufferedAheadBytesSnapshot = 0L;
        if (interruptForegroundReadOnSeek) {
            playbackSeekGeneration.incrementAndGet();
            cancelActivePlaybackCall();
        }
        cancelAllPrefetchCalls();
        Future<?> future = prefetchFuture;
        if (future != null) {
            // The active Call is cancelled above. Keep the executor thread's interrupt state
            // clean so the next foreground read can acquire the range lane normally.
            future.cancel(false);
        }
    }

    public void onPlaybackSeekFinished() {
        playbackSeekInProgress = false;
        if (!blockingReadLock.tryLock()) {
            return;
        }
        try {
            synchronized (this) {
                if (closed || playbackAnchorPosition < 0L) {
                    return;
                }
                refreshBufferSnapshotsLocked();
                scheduleFillAheadLocked(playbackAnchorPosition);
            }
        } finally {
            blockingReadLock.unlock();
        }
    }

    public long getBufferedAheadBytes() {
        return Math.max(0L, bufferedAheadBytesSnapshot);
    }

    public long getPlaybackStartBufferTargetBytes() {
        return Math.max(0L, playbackStartBufferTargetSnapshot);
    }

    public long getCachedBytes() {
        return Math.max(0L, cachedBytesSnapshot);
    }

    private boolean isAuxiliaryTailProbeLocked(long position) {
        if (playbackSeekInProgress
                || totalSize <= 0L
                || position < totalSize - (defaultWindowSize * 4L)) {
            return false;
        }
        // Matroska parsers may inspect the tail before any playback read. Keep the
        // initial byte-zero fill-ahead lane intact until start/seek establishes an anchor.
        if (playbackAnchorPosition < 0L) {
            return !playbackPrebufferRequested;
        }
        // Container parsing can read an index from the tail before it touches video data.
        // Do not let that metadata probe replace the actual playback anchor; its foreground
        // read remains free to cancel fill-ahead traffic through loadWindow().
        long anchor = resolveBufferAnchorLocked(playbackAnchorPosition);
        return anchor >= 0L && anchor < totalSize - (defaultWindowSize * 8L);
    }

    private WindowData findCachedWindowLocked(long position) {
        Map.Entry<Long, WindowData> floor = cachedWindows.floorEntry(position);
        if (floor == null) {
            return null;
        }
        WindowData data = floor.getValue();
        return position <= data.end ? data : null;
    }

    private void storeWindowLocked(WindowData loaded) {
        if (loaded == null || loaded.data.length <= 0) {
            return;
        }
        removeOverlappingWindowsLocked(loaded.start, loaded.end);
        cachedWindows.put(loaded.start, loaded);
        cachedBytes += loaded.data.length;
        if (loaded.totalSize > 0L) {
            totalSize = loaded.totalSize;
        }
        trimCacheLocked();
        refreshBufferSnapshotsLocked();
    }

    private void removeOverlappingWindowsLocked(long start, long end) {
        Map.Entry<Long, WindowData> floor = cachedWindows.floorEntry(end);
        while (floor != null) {
            WindowData existing = floor.getValue();
            if (existing.end < start) {
                break;
            }
            cachedWindows.remove(floor.getKey());
            cachedBytes -= existing.data.length;
            floor = cachedWindows.floorEntry(end);
        }
    }

    private void trimCacheLocked() {
        long anchor = resolveBufferAnchorLocked(playbackAnchorPosition);
        long keepBehind = anchor >= 0L ? Math.max(0L, anchor - STREAMING_BACK_BUFFER_SIZE) : -1L;
        while (!cachedWindows.isEmpty()) {
            Map.Entry<Long, WindowData> first = cachedWindows.firstEntry();
            Map.Entry<Long, WindowData> last = cachedWindows.lastEntry();
            boolean removeBehind = anchor >= 0L && first.getValue().end < keepBehind;
            if (removeBehind) {
                removeWindowLocked(first.getKey());
                continue;
            }
            if (cachedBytes <= STREAMING_MAX_CACHE_SIZE) {
                break;
            }
            if (anchor >= 0L && first.getValue().end < anchor) {
                removeWindowLocked(first.getKey());
                continue;
            }
            if (last != null
                    && anchor >= 0L
                    && last.getValue().start > anchor + STREAMING_TARGET_BUFFER_SIZE
                    && !last.getKey().equals(first.getKey())) {
                removeWindowLocked(last.getKey());
                continue;
            }
            if (last != null && !last.getKey().equals(first.getKey())) {
                removeWindowLocked(last.getKey());
                continue;
            }
            break;
        }
    }

    private void removeWindowLocked(Long key) {
        if (key == null) {
            return;
        }
        WindowData removed = cachedWindows.remove(key);
        if (removed != null) {
            cachedBytes -= removed.data.length;
        }
    }

    private void cancelInflightPrefetchForForegroundReadLocked() {
        boolean prefetchActive = prefetchFuture != null && !prefetchFuture.isDone();
        if (!RangeWindowPolicy.shouldYieldPrefetchToForeground(prefetchActive)) {
            return;
        }
        cancelActivePrefetchLocked();
    }

    private void cancelActivePrefetchLocked() {
        long cancelledPrefetchId = activePrefetchId;
        Future<?> future = prefetchFuture;
        prefetchFuture = null;
        scheduledPrefetchStart = -1L;
        activePrefetchId = -1L;
        cancelPrefetchCall(cancelledPrefetchId);
        if (future != null) {
            // Closing the OkHttp Call is enough to release the network read. Do not interrupt
            // the shared executor thread: an interrupt can leak into the next foreground range
            // request and make a healthy read fail as "interrupted" after a short payload.
            future.cancel(false);
        }
    }

    private long resolvePrefetchAnchorLocked(long fallbackPosition) {
        return resolveBufferAnchorLocked(fallbackPosition);
    }

    private long resolveBufferAnchorLocked(long fallbackPosition) {
        return PlaybackPrebufferPolicy.resolveAnchor(
                playbackPrebufferActive,
                playbackPrebufferAnchorPosition,
                playbackAnchorPosition,
                fallbackPosition);
    }

    private void syncPlaybackPrebufferStateLocked(long fallbackPosition) {
        if (playbackPrebufferRequested && !playbackPrebufferActive) {
            playbackPrebufferAnchorPosition = PlaybackPrebufferPolicy.resolveAnchor(
                    false, -1L, playbackAnchorPosition, fallbackPosition);
            playbackPrebufferActive = true;
            logInfo("echo-range-source prebuffer-anchor begin anchor="
                    + playbackPrebufferAnchorPosition
                    + " live=" + playbackAnchorPosition);
            refreshBufferSnapshotsLocked();
            return;
        }
        if (!playbackPrebufferRequested && playbackPrebufferActive) {
            long completedAnchor = playbackPrebufferAnchorPosition;
            playbackPrebufferActive = false;
            playbackPrebufferAnchorPosition = -1L;
            trimCacheLocked();
            refreshBufferSnapshotsLocked();
            logInfo("echo-range-source prebuffer-anchor finish anchor=" + completedAnchor
                    + " live=" + playbackAnchorPosition
                    + " ahead=" + bufferedAheadBytesSnapshot
                    + " cache=" + cachedBytesSnapshot);
        }
    }

    private long getBufferedAheadBytesLocked(long position) {
        WindowData current = findCachedWindowLocked(position);
        if (current == null) {
            return 0L;
        }
        long contiguousEnd = current.end;
        Map.Entry<Long, WindowData> next = cachedWindows.higherEntry(current.start);
        while (next != null && next.getValue().start <= contiguousEnd + 1L) {
            contiguousEnd = Math.max(contiguousEnd, next.getValue().end);
            next = cachedWindows.higherEntry(next.getKey());
        }
        return contiguousEnd - position + 1L;
    }

    private long getNextMissingWindowStartLocked(long position) {
        WindowData current = findCachedWindowLocked(position);
        if (current == null) {
            return alignWindowStart(position);
        }
        long contiguousEnd = current.end;
        Map.Entry<Long, WindowData> next = cachedWindows.higherEntry(current.start);
        while (next != null && next.getValue().start <= contiguousEnd + 1L) {
            contiguousEnd = Math.max(contiguousEnd, next.getValue().end);
            next = cachedWindows.higherEntry(next.getKey());
        }
        return contiguousEnd + 1L;
    }

    private void scheduleFillAheadLocked(long position) {
        if (!prefetchEnabled
                || closed
                || playbackSeekInProgress) {
            return;
        }
        if (totalSize <= 0L || windowData.length <= 0) {
            scheduleInitialPrefetchLocked();
            return;
        }
        long anchor = resolvePrefetchAnchorLocked(position);
        if (getBufferedAheadBytesLocked(anchor) >= STREAMING_TARGET_BUFFER_SIZE) {
            return;
        }
        long nextStart = getNextMissingWindowStartLocked(anchor);
        if (nextStart >= totalSize) {
            return;
        }
        if (prefetchFuture != null && !prefetchFuture.isDone()) {
            long activeStart = scheduledPrefetchStart;
            boolean sameLane = activeStart >= anchor - prefetchWindowSize
                    && activeStart <= nextStart + prefetchWindowSize;
            if (sameLane) {
                return;
            }
            cancelActivePrefetchLocked();
        }
        final long prefetchId = ++nextPrefetchId;
        activePrefetchId = prefetchId;
        scheduledPrefetchStart = nextStart;
        final long prefetchStart = nextStart;
        prefetchFuture = PREFETCH_EXECUTOR.submit(() -> runPrefetch(prefetchId, prefetchStart));
    }

    private void scheduleInitialPrefetchLocked() {
        if (prefetchFuture != null && !prefetchFuture.isDone()) {
            return;
        }
        final long prefetchId = ++nextPrefetchId;
        activePrefetchId = prefetchId;
        scheduledPrefetchStart = 0L;
        prefetchFuture = PREFETCH_EXECUTOR.submit(() -> runPrefetch(prefetchId, 0L));
    }

    private void maybeSchedulePrefetchLocked(long nextPosition, int requestedSize) {
        if (isAuxiliaryTailProbeLocked(nextPosition)) {
            // A metadata tail read can preempt the initial request, but must not permanently
            // abandon byte-zero fill-ahead before actual playback begins.
            if (playbackAnchorPosition < 0L && !playbackSeekInProgress) {
                scheduleFillAheadLocked(0L);
            }
            return;
        }
        scheduleFillAheadLocked(nextPosition);
    }

    private void runPrefetch(long prefetchId, long start) {
        Throwable failure = null;
        long nextStart = start;
        try {
            while (true) {
                synchronized (this) {
                    if (closed || prefetchId != activePrefetchId) {
                        return;
                    }
                    if (playbackSeekInProgress) {
                        return;
                    }
                    if (totalSize <= 0L || windowData.length <= 0) {
                        nextStart = 0L;
                    } else {
                        long anchor = resolvePrefetchAnchorLocked(nextStart);
                        if (getBufferedAheadBytesLocked(anchor) >= STREAMING_TARGET_BUFFER_SIZE
                                || cachedBytes >= STREAMING_MAX_CACHE_SIZE) {
                            return;
                        }
                        nextStart = getNextMissingWindowStartLocked(anchor);
                        if (nextStart >= totalSize) {
                            return;
                        }
                    }
                }
                long requestSize = getKnownTotalSizeSnapshot() > 0L
                        ? prefetchWindowSize
                        : PROBE_WINDOW_SIZE;
                WindowData loaded = requestWindowData(nextStart, requestSize, prefetchId);
                synchronized (this) {
                    if (closed || prefetchId != activePrefetchId) {
                        return;
                    }
                    storeWindowLocked(loaded);
                    if (totalSize <= 0L) {
                        logInfo("echo-range-source prefetch-no-content-length start=" + loaded.start);
                        return;
                    }
                    scheduledPrefetchStart = loaded.end + 1L;
                    if (debugPrefetchCount < 16) {
                        debugPrefetchCount++;
                        logInfo("echo-range-source prefetched start=" + loaded.start
                                + " end=" + loaded.end
                                + " total=" + loaded.totalSize
                                + " cache=" + (cachedBytes / (1024L * 1024L)) + "MB");
                    }
                }
            }
        } catch (Throwable th) {
            failure = th;
        } finally {
            synchronized (this) {
                if (failure != null && prefetchId == activePrefetchId && debugPrefetchCount < 16) {
                    debugPrefetchCount++;
                    logInfo("echo-range-source prefetch-failed start=" + nextStart
                            + " err=" + failure.getClass().getSimpleName()
                            + ":" + failure.getMessage());
                }
                if (prefetchId == activePrefetchId) {
                    scheduledPrefetchStart = -1L;
                    prefetchFuture = null;
                    activePrefetchId = -1L;
                    // Reads can advance the playback head between the last prefetch check and
                    // this cleanup. Re-evaluate once so the cache never stops refilling merely
                    // because the previous task reached its old target first.
                    if (!closed && failure == null) {
                        scheduleFillAheadLocked(resolvePrefetchAnchorLocked(nextStart));
                    }
                }
            }
        }
    }

    private void applyWindowLocked(WindowData data) {
        if (data.totalSize > 0L) {
            totalSize = data.totalSize;
        }
        windowData = data.data;
        windowStart = data.start;
        windowEnd = data.end;
    }

    private WindowData requestWindowData(long start, long minWindowSize) throws IOException {
        return requestWindowData(start, minWindowSize, -1L);
    }

    private WindowData requestWindowData(long start,
                                         long minWindowSize,
                                         long prefetchId) throws IOException {
        boolean locked = false;
        try {
            rangeRequestLock.lockInterruptibly();
            locked = true;
            return requestWindowDataLocked(start, minWindowSize, prefetchId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Range request interrupted before network read", interrupted);
        } finally {
            if (locked) {
                rangeRequestLock.unlock();
            }
        }
    }

    private WindowData requestWindowDataLocked(long start,
                                               long minWindowSize,
                                               long prefetchId) throws IOException {
        long knownTotalSize = getKnownTotalSizeSnapshot();
        if (knownTotalSize >= 0L && start >= knownTotalSize) {
            return new WindowData(start, start - 1L, new byte[0], knownTotalSize,
                    start, start - 1L, start - 1L, 416);
        }
        long requestedWindow = Math.max(1L, minWindowSize);
        long desiredEnd = requestedWindow == Long.MAX_VALUE ? -1L : start + requestedWindow - 1L;
        if (knownTotalSize >= 0L) {
            desiredEnd = desiredEnd >= 0L ? Math.min(desiredEnd, knownTotalSize - 1L) : knownTotalSize - 1L;
        }
        final boolean prefetch = prefetchId >= 0L;
        final long recoveryStartedAtNanos = System.nanoTime();
        IOException lastPayloadFailure = null;
        for (int payloadAttempt = 1; ; payloadAttempt++) {
            if (isForegroundReadSuperseded(prefetchId)) {
                throw new IOException("Playback range read superseded by seek");
            }
            Response response = openRangeResponse(start, desiredEnd, prefetchId);
            try {
                long resolvedTotalSize = knownTotalSize;
                if (response.code() == 416) {
                    long unsatisfiedTotal = ContentRangeInfo.parseUnsatisfiedTotal(response.header("Content-Range"));
                    if (knownTotalSize >= 0L) {
                        if (start >= knownTotalSize) {
                            return new WindowData(start, start - 1L, new byte[0], knownTotalSize,
                                    start, start - 1L, desiredEnd, response.code());
                        }
                        // The local 6677 endpoint can briefly report the length of its current
                        // cache window as `bytes */N` while a valid, larger file is still being
                        // streamed. Never replace an already verified file length with that
                        // transient value: doing so turns a retryable read into a false EOF and
                        // leaves the decoder stuck at the new position.
                        lastPayloadFailure = new IOException("Transient HTTP 416 before known EOF at "
                                + start + "/" + knownTotalSize + " reported=" + unsatisfiedTotal);
                    } else if (shouldTreatUnknown416AsEof(url, start, unsatisfiedTotal)) {
                        // With no prior verified length, the RFC 7233 unsatisfied-range form is
                        // the only length evidence available. Treat it as EOF only at or beyond
                        // that reported end; otherwise retry because the proxy may still be
                        // growing its readable window.
                        return new WindowData(start, start - 1L, new byte[0], unsatisfiedTotal,
                                start, start - 1L, desiredEnd, response.code());
                    } else {
                        // The direct 6677 proxy can expose a transient cache length as
                        // `bytes */N` while the remote file is still growing. At a non-zero
                        // position that response is not EOF when the total is otherwise unknown;
                        // retry the exact range instead of poisoning the DataSource with a false
                        // end-of-input. A real EOF is still handled once a verified total is
                        // known, or by the bounded retry policy turning this into a source error.
                        lastPayloadFailure = new IOException("HTTP 416 before known EOF at "
                                + start + " reported=" + unsatisfiedTotal);
                    }
                } else if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Unexpected HTTP " + response.code() + " for " + start + "-" + desiredEnd);
                } else {
                    String contentRangeHeader = response.header("Content-Range");
                    ContentRangeInfo rangeInfo = ContentRangeInfo.parse(contentRangeHeader);
                    long responseBodyLength = response.body().contentLength();
                    boolean bareLoopbackRangeStart = isBareLoopbackRangeStart(
                            response.code(), contentRangeHeader, start);
                    boolean shiftedLoopbackRange = isLoopbackShiftedRangeHeader(
                            url, rangeInfo, start, desiredEnd);
                    boolean loopbackProxy = isLoopbackProxyPlayUrl(url);
                    if (debugResponseCount < 12) {
                        debugResponseCount++;
                        logInfo("echo-range-source response start=" + start
                                + " end=" + desiredEnd
                                + " range=" + contentRangeHeader
                                + " bodyLength=" + responseBodyLength
                                + " loopback=" + loopbackProxy
                                + " shifted=" + shiftedLoopbackRange);
                    }
                    if (rangeInfo != null && rangeInfo.total > 0L) {
                        resolvedTotalSize = rangeInfo.total;
                    } else if (start == 0L) {
                        if (responseBodyLength > 0L) {
                            resolvedTotalSize = responseBodyLength;
                        }
                    }

                    // A tail seek can be the first request on a saved-position reopen, so
                    // knownTotalSize is still unset when the request is built. Once this
                    // response establishes the real entity length, immediately clamp the
                    // logical window to that length. Without this, the 6677 endpoint may stream
                    // bytes beyond the Matroska file tail and the extractor sees fabricated EBML
                    // data, reported as `Invalid integer size` at the resume position.
                    if (resolvedTotalSize > 0L) {
                        if (start >= resolvedTotalSize) {
                            return new WindowData(start, start - 1L, new byte[0], resolvedTotalSize,
                                    start, start - 1L, desiredEnd, response.code());
                        }
                        desiredEnd = desiredEnd >= start
                                ? Math.min(desiredEnd, resolvedTotalSize - 1L)
                                : resolvedTotalSize - 1L;
                    }

                    // For the known 6677 header defect, the body is still contiguous from the
                    // requested offset even though Content-Range says start+8/end-8. Rebase only
                    // the logical interpretation of that response; never skip eight body bytes
                    // and never alter the actual Range header sent on the wire.
                    // The local proxy's physical Range request is always logicalStart-logicalEnd.
                    // Its malformed Content-Range header is metadata only; the response body
                    // therefore starts at the requested logical offset even when the header says
                    // start+8. Keeping this invariant prevents stale/alternate proxy headers
                    // from shifting the bytes fed to Matroska's EBML parser.
                    long payloadStart = (loopbackProxy && (shiftedLoopbackRange
                            || (rangeInfo != null && rangeInfo.start == start))) ? start
                            : (rangeInfo != null ? rangeInfo.start : 0L);
                    long payloadEnd;
                    if (rangeInfo != null) {
                        payloadEnd = rangeInfo.end;
                    } else {
                        payloadEnd = responseBodyLength > 0L
                                ? payloadStart + responseBodyLength - 1L : desiredEnd;
                    }
                    if (loopbackProxy && payloadStart == start && desiredEnd >= start) {
                        // Never let a malformed or over-optimistic Content-Range make the
                        // decoder consume bytes past the exact requested entity.
                        payloadEnd = Math.min(payloadEnd, desiredEnd);
                        if (responseBodyLength > 0L) {
                            payloadEnd = Math.min(payloadEnd,
                                    start + responseBodyLength - 1L);
                        }
                    }
                    if (resolvedTotalSize > 0L) {
                        payloadEnd = Math.min(payloadEnd, resolvedTotalSize - 1L);
                    }
                // 6677 can answer the first seek reopen with its initial 1 MB block even
                // though this request starts later. Closing and retrying the same byte range
                // yields the correct 206 response; treating that first block as EOF turns a
                // valid resume/tail read into a permanent loading-0% failure.
                    boolean retryableRangeReset = start > 0L
                            && response.code() == 200
                            && rangeInfo != null
                            && rangeInfo.start == 0L
                            && rangeInfo.end < start;
                    if (retryableRangeReset) {
                        lastPayloadFailure = new IOException("Server reset non-zero range "
                                + start + " to " + rangeInfo.start + "-" + rangeInfo.end);
                    } else {
                        if (payloadEnd < start) {
                            throw new IOException("Invalid payload window " + payloadStart + "-" + payloadEnd + " for " + start);
                        }
                        if (start > 0L && rangeInfo == null) {
                            throw new IOException("Server ignored range request for offset " + start);
                        }
                        if (rangeInfo != null && rangeInfo.start > start && !shiftedLoopbackRange) {
                            throw new IOException("Range response starts after requested offset: " + rangeInfo.start + " > " + start);
                        }

                        long skipBytes = Math.max(0L, start - payloadStart);
                        long availableAfterSkip = payloadEnd - start + 1L;
                        long targetLength = desiredEnd >= start ? desiredEnd - start + 1L : availableAfterSkip;
                        long bytesToRead = targetLength > 0L ? Math.min(targetLength, availableAfterSkip) : availableAfterSkip;

                        InputStream stream = response.body().byteStream();
                        byte[] data;
                        try {
                            discardFully(stream, skipBytes);
                            data = readUpTo(stream, bytesToRead, rangeTransferListener);
                        } catch (IOException bodyFailure) {
                            lastPayloadFailure = new IOException(
                                    "Range payload ended early at " + start + "-" + desiredEnd,
                                    bodyFailure);
                            data = null;
                        }
                        if (data != null && data.length > 0
                                && isCorruptLoopbackPayload(url, start, data)) {
                            // A 6677 overrun request can return eight bytes from an image
                            // response followed by the video's EBML header. The Content-Range
                            // metadata still looks usable, so accepting this body would feed a
                            // PNG prefix into Matroska and leave ExoPlayer permanently buffering
                            // at zero. Discard the whole response and retry the same logical
                            // range; never trim the prefix because the following bytes are from a
                            // different entity, not a shifted video window.
                            lastPayloadFailure = new IOException(
                                    "Loopback range returned image-prefixed payload at " + start);
                            logInfo("echo-range-source reject-corrupt-payload start=" + start
                                    + " bytes=" + data.length
                                    + " prefix=" + hexPrefix(data));
                            data = null;
                        }
                        if (data != null && data.length > 0) {
                            long expectedBytes = bytesToRead;
                            boolean completePayload = data.length >= expectedBytes;
                            boolean reachedKnownEnd = resolvedTotalSize > 0L
                                    && start <= Long.MAX_VALUE - data.length
                                    && start + data.length >= resolvedTotalSize;
                            boolean toleratedBareLoopbackShortPayload = bareLoopbackRangeStart
                                    && isExpectedLoopbackShortPayload(expectedBytes, data.length);
                            // The loopback endpoint can keep a valid Range connection open while
                            // it fills the remote-drive entity. For the ExoPlayer route, commit a
                            // verified contiguous prefix immediately and let the next decoder
                            // read continue at its exact end. This exception is limited to the
                            // local proxy's exact +8 header signature; ordinary origins and
                            // ordinary short responses still require the advertised window.
                            boolean verifiedShortPrefix = allowVerifiedShortPayload
                                    && !completePayload
                                    && data.length > 0
                                    && (shiftedLoopbackRange
                                    || (rangeInfo != null && rangeInfo.start == start));
                            if (completePayload || reachedKnownEnd || toleratedBareLoopbackShortPayload
                                    || verifiedShortPrefix) {
                                if (debugLoadCount < 12) {
                                    logInfo("echo-range-source body start=" + start
                                            + " bytes=" + data.length
                                            + " prefix=" + hexPrefix(data));
                                }
                                long resolvedEnd = start + data.length - 1L;
                                return new WindowData(start, resolvedEnd, data, resolvedTotalSize,
                                        payloadStart,
                                        verifiedShortPrefix ? resolvedEnd : payloadEnd,
                                        desiredEnd, response.code());
                            }
                            // A Range response can advertise an 8 MiB entity while the proxy
                            // closes the body after a much smaller prefix. Returning that prefix as
                            // a valid window moves ExoPlayer to a false boundary and eventually
                            // stalls on an interrupted follow-up request. Reopen the same Range
                            // until the advertised payload is complete (or the known file end is
                            // reached).
                            lastPayloadFailure = new IOException(
                                    "Range payload short at " + start + ": expected "
                                            + expectedBytes + " bytes, received " + data.length);
                        }
                        if (data != null && data.length == 0) {
                            lastPayloadFailure = new IOException(
                                    "Empty range payload before EOF at " + start + "/" + resolvedTotalSize);
                        }
                    }
                }
            } finally {
                response.close();
                clearRangeCall(prefetchId);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - recoveryStartedAtNanos);
            if (!RangePayloadRetryPolicy.shouldRetry(payloadAttempt, elapsedMs, prefetch)) {
                throw lastPayloadFailure == null
                        ? new IOException("Unable to recover range payload at " + start)
                        : lastPayloadFailure;
            }
            logInfo("echo-range-source payload-retry attempt=" + payloadAttempt
                    + " start=" + start + " end=" + desiredEnd
                    + " total=" + knownTotalSize + " prefetch=" + prefetch);
            waitForPayloadRetry(payloadAttempt, prefetchId);
        }
    }

    private static boolean isLoopbackProxyPlayUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(value.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            return ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && uri.getPort() > 0
                    && path != null
                    && path.contains("/proxy/play/");
        } catch (Exception ignored) {
            return false;
        }
    }

    static long resolveLoopbackWireStart(String url, long logicalStart) {
        // Kept package-visible for regression tests and callers that previously used the
        // coordinate helper. Physical and logical offsets are intentionally identical now.
        return Math.max(0L, logicalStart);
    }

    static long resolveLoopbackWireEnd(String url, long logicalEnd) {
        return logicalEnd;
    }

    static boolean shouldTreatUnknown416AsEof(String url, long start, long unsatisfiedTotal) {
        return unsatisfiedTotal > 0L
                && start >= unsatisfiedTotal
                && !(start > 0L && isLoopbackProxyPlayUrl(url));
    }

    private void waitForPayloadRetry(int attempt, long prefetchId) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new IOException("Range request cancelled before empty-payload retry");
        }
        if (prefetchId >= 0L && !isPrefetchActive(prefetchId)) {
            throw new IOException("Prefetch request cancelled before payload retry");
        }
        if (isForegroundReadSuperseded(prefetchId)) {
            throw new IOException("Playback range read superseded before payload retry");
        }
        try {
            Thread.sleep(RangePayloadRetryPolicy.retryDelayMs(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Range request interrupted before empty-payload retry", e);
        }
    }

    private void refreshBufferSnapshotsLocked() {
        long anchor = resolveBufferAnchorLocked(0L);
        bufferedAheadBytesSnapshot = getBufferedAheadBytesLocked(anchor);
        playbackStartBufferTargetSnapshot = PlaybackPrebufferPolicy.resolveRequiredBytes(
                STREAMING_START_BUFFER_SIZE, totalSize, anchor);
        cachedBytesSnapshot = Math.max(0L, cachedBytes);
    }

    private Response openRangeResponse(long start, long end, long prefetchId) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Call call = null;
            try {
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .header("Connection", "close")
                        .header("Accept-Encoding", "identity");
                // Keep wire coordinates identical to the decoder's logical coordinates. The
                // 6677 endpoint has a header-only +8/-8 defect; expanding the physical request
                // prepends unrelated bytes and makes valid Matroska clusters look corrupt.
                long wireStart = resolveLoopbackWireStart(url, start);
                // A saved-position reopen can be the first request that exposes the file length.
                // Sending start+8MiB while that length is unknown is unsafe for 6677: if the
                // cursor is near EOF, the proxy emits an image-prefixed error entity instead of
                // the requested tail. An open-ended Range lets the proxy clamp to its actual EOF;
                // readUpTo below still limits the bytes committed to this foreground window.
                boolean unknownLengthLoopbackSeek = isLoopbackProxyPlayUrl(url)
                        && wireStart > 0L
                        && getKnownTotalSizeSnapshot() < 0L;
                long wireEnd = unknownLengthLoopbackSeek
                        ? -1L : resolveLoopbackWireEnd(url, end);
                builder.header("Range", wireEnd >= wireStart
                        ? "bytes=" + wireStart + "-" + wireEnd
                        : "bytes=" + wireStart + "-");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!isNullOrEmpty(entry.getKey())
                            && entry.getValue() != null
                            && !isManagedRequestHeader(entry.getKey())) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
                call = CLIENT.newCall(builder.build());
                if (!registerRangeCall(prefetchId, call)) {
                    throw new IOException(prefetchId >= 0L
                            ? "Prefetch request cancelled before range open"
                            : "Playback request cancelled before range open");
                }
                if (debugResponseCount < 12 && unknownLengthLoopbackSeek) {
                    logInfo("echo-range-source open-ended-seek start=" + wireStart);
                }
                return call.execute();
            } catch (IOException e) {
                if (call != null) {
                    clearRangeCall(prefetchId);
                }
                lastError = e;
                logInfo("echo-range-source retry " + attempt + "/" + MAX_RETRIES
                        + " start=" + start + " end=" + end + " err=" + e.getMessage());
                if (attempt >= MAX_RETRIES) {
                    break;
                }
                if (prefetchId >= 0L && !isPrefetchActive(prefetchId)) {
                    throw e;
                }
                if (isForegroundReadSuperseded(prefetchId)) {
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastError == null ? new IOException("Unknown range request failure") : lastError;
    }

    private boolean isForegroundReadSuperseded(long prefetchId) {
        if (prefetchId >= 0L || !interruptForegroundReadOnSeek) {
            return false;
        }
        Long readGeneration = foregroundReadGeneration.get();
        return readGeneration != null && readGeneration != playbackSeekGeneration.get();
    }

    private void logSupersededRead(long position, long readGeneration) {
        logInfo("echo-range-source discard superseded read pos=" + position
                + " generation=" + readGeneration
                + " current=" + playbackSeekGeneration.get());
    }

    static final class PlaybackReadSupersededException extends IOException {
        PlaybackReadSupersededException(long position, long generation) {
            super("Playback range read superseded at " + position + " generation=" + generation);
        }
    }

    private boolean registerRangeCall(long prefetchId, Call call) {
        if (prefetchId < 0L) {
            if (closed || isForegroundReadSuperseded(prefetchId)) {
                call.cancel();
                return false;
            }
            Call previous = activePlaybackCall.getAndSet(call);
            if (previous != null && previous != call) {
                previous.cancel();
            }
            if (closed || isForegroundReadSuperseded(prefetchId)) {
                activePlaybackCall.compareAndSet(call, null);
                call.cancel();
                return false;
            }
            return true;
        }
        if (closed || prefetchId != activePrefetchId || Thread.currentThread().isInterrupted()) {
            call.cancel();
            return false;
        }
        activePrefetchCalls.put(prefetchId, call);
        if (closed || prefetchId != activePrefetchId || Thread.currentThread().isInterrupted()) {
            activePrefetchCalls.remove(prefetchId, call);
            call.cancel();
            return false;
        }
        return true;
    }

    private boolean isPrefetchActive(long prefetchId) {
        return !closed && prefetchId >= 0L && prefetchId == activePrefetchId;
    }

    private void clearRangeCall(long prefetchId) {
        if (prefetchId < 0L) {
            activePlaybackCall.set(null);
        } else {
            activePrefetchCalls.remove(prefetchId);
        }
    }

    private void cancelActivePlaybackCall() {
        Call call = activePlaybackCall.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
    }

    private void cancelPrefetchCall(long prefetchId) {
        if (prefetchId < 0L) {
            return;
        }
        Call call = activePrefetchCalls.remove(prefetchId);
        if (call != null) {
            call.cancel();
        }
    }

    private void cancelAllPrefetchCalls() {
        for (Call call : activePrefetchCalls.values()) {
            if (call != null) {
                call.cancel();
            }
        }
        activePrefetchCalls.clear();
    }

    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // android.jar logging methods are stubs in local JVM regression tests.
        }
        writeRuntimeLog(message);
    }

    private static void writeRuntimeLog(String message) {
        try {
            Method method = getRuntimeLogInfoMethod();
            if (method != null) {
                method.invoke(null, message);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method getRuntimeLogInfoMethod() {
        if (sRuntimeLogLookupDone) {
            return sRuntimeLogInfoMethod;
        }
        synchronized (HttpRangeMediaDataSource.class) {
            if (sRuntimeLogLookupDone) {
                return sRuntimeLogInfoMethod;
            }
            try {
                Class<?> logClass = Class.forName("com.github.tvbox.osc.util.LOG");
                sRuntimeLogInfoMethod = logClass.getMethod("i", String.class);
            } catch (Throwable ignored) {
                sRuntimeLogInfoMethod = null;
            }
            sRuntimeLogLookupDone = true;
            return sRuntimeLogInfoMethod;
        }
    }

    private static long resolveStreamingDefaultWindowSize() {
        if (is32BitProcess()) {
            return 2L * 1024L * 1024L;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 4L * 1024L * 1024L;
        }
        return 8L * 1024L * 1024L;
    }

    private static long resolveStreamingMaxWindowSize() {
        if (is32BitProcess()) {
            return 4L * 1024L * 1024L;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 12L * 1024L * 1024L;
        }
        return 24L * 1024L * 1024L;
    }

    private static long resolveStreamingPrefetchWindowSize() {
        if (is32BitProcess()) {
            return 4L * 1024L * 1024L;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        return maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L
                ? 8L * 1024L * 1024L
                : 16L * 1024L * 1024L;
    }

    private static long resolveStreamingStartBufferSize() {
        if (is32BitProcess()) {
            return 24L * 1024L * 1024L;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        return maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L
                ? 12L * 1024L * 1024L
                : 24L * 1024L * 1024L;
    }

    private static long resolveStreamingTargetBufferSize() {
        if (is32BitProcess()) {
            return 32L * 1024L * 1024L;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 16L * 1024L * 1024L;
        }
        return 32L * 1024L * 1024L;
    }

    private static long resolveStreamingMaxCacheSize() {
        if (is32BitProcess()) {
            return 40L * 1024L * 1024L;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 32L * 1024L * 1024L;
        }
        return 64L * 1024L * 1024L;
    }

    private static long resolveStreamingBackBufferSize() {
        if (is32BitProcess()) {
            return 4L * 1024L * 1024L;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 4L * 1024L * 1024L;
        }
        return 8L * 1024L * 1024L;
    }

    private static boolean is32BitProcess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return !android.os.Process.is64Bit();
            }
            String abi = Build.CPU_ABI == null ? "" : Build.CPU_ABI;
            return !abi.contains("64");
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void discardFully(InputStream stream, long bytesToSkip) throws IOException {
        long remaining = bytesToSkip;
        byte[] scratch = new byte[8192];
        while (remaining > 0L) {
            int read = stream.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read < 0) {
                throw new IOException("Unexpected EOF while skipping " + bytesToSkip + " bytes");
            }
            remaining -= read;
        }
    }

    private static byte[] readUpTo(InputStream stream,
                                   long maxBytes,
                                   RangeTransferListener transferListener) throws IOException {
        if (maxBytes <= 0L) {
            return new byte[0];
        }
        if (maxBytes > Integer.MAX_VALUE) {
            throw new IOException("Requested window too large: " + maxBytes);
        }
        byte[] buffer = new byte[(int) maxBytes];
        int total = 0;
        while (total < buffer.length) {
            int read;
            try {
                read = stream.read(buffer, total, buffer.length - total);
            } catch (IOException error) {
                if (total > 0) {
                    return Arrays.copyOf(buffer, total);
                }
                throw error;
            }
            if (read < 0) {
                break;
            }
            if (read > 0 && transferListener != null) {
                transferListener.onBytesTransferred(read);
            }
            total += read;
        }
        return total == buffer.length ? buffer : Arrays.copyOf(buffer, total);
    }

    private static boolean isManagedRequestHeader(String key) {
        if (isNullOrEmpty(key)) {
            return true;
        }
        String lower = key.trim().toLowerCase();
        return "range".equals(lower)
                || "accept-ranges".equals(lower)
                || "content-range".equals(lower)
                || "content-length".equals(lower)
                || "connection".equals(lower)
                || "accept-encoding".equals(lower)
                || "host".equals(lower)
                || HEADER_PROBE_CONTAINER.equals(lower)
                || HEADER_PROBE_DOLBY_VISION.equals(lower);
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String hexPrefix(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        int count = Math.min(16, data.length);
        StringBuilder result = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                result.append(' ');
            }
            int value = data[i] & 0xff;
            if (value < 0x10) {
                result.append('0');
            }
            result.append(Integer.toHexString(value).toUpperCase());
        }
        return result.toString();
    }

    private static boolean isCorruptLoopbackPayload(String responseUrl,
                                                    long requestedStart,
                                                    byte[] data) {
        if (requestedStart <= 0L
                || !isLoopbackProxyPlayUrl(responseUrl)
                || data == null
                || data.length < PNG_SIGNATURE.length + EBML_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        for (int i = 0; i < EBML_SIGNATURE.length; i++) {
            if (data[PNG_SIGNATURE.length + i] != EBML_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBareLoopbackRangeStart(int responseCode,
                                                    String contentRangeHeader,
                                                    long requestedStart) {
        return responseCode == 206
                && contentRangeHeader != null
                && contentRangeHeader.trim().equalsIgnoreCase("bytes " + requestedStart);
    }

    private static boolean isLoopbackShiftedRangeHeader(String responseUrl,
                                                        ContentRangeInfo range,
                                                        long requestedStart,
                                                        long requestedEnd) {
        if (!isLoopbackProxyPlayUrl(responseUrl)
                || range == null
                || requestedStart > Long.MAX_VALUE - LOOPBACK_MALFORMED_RANGE_HEADER_SHIFT_BYTES) {
            return false;
        }
        if (range.start != requestedStart + LOOPBACK_MALFORMED_RANGE_HEADER_SHIFT_BYTES) {
            return false;
        }
        // The endpoint has shipped more than one broken Content-Range formatter: the start is
        // consistently shifted by eight bytes, while the end has been observed as end-8, the
        // requested end, and (for chunked responses) beyond the requested end. The body is the
        // useful source of truth here. Once the exact +8 start signature is present on the local
        // proxy, ignore both advertised coordinates and rebase the entity at the logical request
        // offset below. Do not broaden this to arbitrary origins or arbitrary offsets: those are
        // real out-of-order responses and must still fail rather than feed corrupt bytes to EBML.
        return true;
    }

    private static boolean isExpectedLoopbackShortPayload(long expectedBytes, long receivedBytes) {
        return expectedBytes > LOOPBACK_MALFORMED_RANGE_HEADER_SHIFT_BYTES
                && receivedBytes > 0L
                && expectedBytes - receivedBytes == LOOPBACK_MALFORMED_RANGE_HEADER_SHIFT_BYTES;
    }

    private static final class ContentRangeInfo {
        private final long start;
        private final long end;
        private final long total;

        private ContentRangeInfo(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.total = total;
        }

        private static ContentRangeInfo parse(String headerValue) {
            if (isNullOrEmpty(headerValue)) {
                return null;
            }
            try {
                String value = headerValue.trim();
                if (!value.startsWith("bytes")) {
                    return null;
                }
                String[] rangeAndTotal = value.substring("bytes".length()).trim().split("/", 2);
                if (rangeAndTotal.length != 2) {
                    return null;
                }
                String[] startEnd = rangeAndTotal[0].trim().split("-", 2);
                if (startEnd.length != 2) {
                    return null;
                }
                long start = Long.parseLong(startEnd[0].trim());
                long end = Long.parseLong(startEnd[1].trim());
                long total = "*".equals(rangeAndTotal[1].trim()) ? -1L : Long.parseLong(rangeAndTotal[1].trim());
                if (start < 0L || end < start) {
                    return null;
                }
                return new ContentRangeInfo(start, end, total);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static long parseUnsatisfiedTotal(String headerValue) {
            if (isNullOrEmpty(headerValue)) {
                return -1L;
            }
            try {
                String value = headerValue.trim();
                if (!value.startsWith("bytes")) {
                    return -1L;
                }
                int slash = value.lastIndexOf('/');
                if (slash < 0 || slash + 1 >= value.length()) {
                    return -1L;
                }
                String total = value.substring(slash + 1).trim();
                if ("*".equals(total)) {
                    return -1L;
                }
                return Long.parseLong(total);
            } catch (Throwable ignored) {
                return -1L;
            }
        }
    }

    private static final class WindowData {
        private final long start;
        private final long end;
        private final byte[] data;
        private final long totalSize;
        private final long payloadStart;
        private final long payloadEnd;
        private final long requestedEnd;
        private final int responseCode;

        private WindowData(long start,
                           long end,
                           byte[] data,
                           long totalSize,
                           long payloadStart,
                           long payloadEnd,
                           long requestedEnd,
                           int responseCode) {
            this.start = start;
            this.end = end;
            this.data = data;
            this.totalSize = totalSize;
            this.payloadStart = payloadStart;
            this.payloadEnd = payloadEnd;
            this.requestedEnd = requestedEnd;
            this.responseCode = responseCode;
        }
    }
}
