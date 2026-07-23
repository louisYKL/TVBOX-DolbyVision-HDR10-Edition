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

    public HttpRangeMediaDataSource(String url, Map<String, String> headers) {
        this(url, headers, DEFAULT_WINDOW_SIZE, MAX_WINDOW_SIZE, false);
    }

    public static HttpRangeMediaDataSource createForStreamingPlayback(String url, Map<String, String> headers) {
        return new HttpRangeMediaDataSource(url, headers,
                STREAMING_DEFAULT_WINDOW_SIZE, STREAMING_MAX_WINDOW_SIZE, false);
    }

    static HttpRangeMediaDataSource createForProxyFileDescriptor(String url,
                                                                  Map<String, String> headers) {
        return new HttpRangeMediaDataSource(url, headers,
                STREAMING_DEFAULT_WINDOW_SIZE, STREAMING_MAX_WINDOW_SIZE, true);
    }

    private HttpRangeMediaDataSource(String url,
                                     Map<String, String> headers,
                                     long defaultWindowSize,
                                     long maxWindowSize,
                                     boolean interruptForegroundReadOnSeek) {
        this.url = url;
        this.defaultWindowSize = Math.max(DEFAULT_WINDOW_SIZE, defaultWindowSize);
        this.maxWindowSize = Math.max(this.defaultWindowSize, maxWindowSize);
        this.prefetchWindowSize = Math.max(this.defaultWindowSize, STREAMING_PREFETCH_WINDOW_SIZE);
        this.prefetchEnabled = this.defaultWindowSize >= STREAMING_DEFAULT_WINDOW_SIZE;
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
                    maybeSchedulePrefetchLocked(cursor, requestedSize);
                    if (copyLength < available) {
                        break;
                    }
                    if (totalSize >= 0L && cursor >= totalSize) {
                        break;
                    }
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
        if (!prefetchEnabled || position < 0L) {
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
            future.cancel(true);
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
            future.cancel(true);
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
                    if (unsatisfiedTotal > 0L) {
                        resolvedTotalSize = unsatisfiedTotal;
                    }
                    if (resolvedTotalSize >= 0L && start >= resolvedTotalSize) {
                        return new WindowData(start, start - 1L, new byte[0], resolvedTotalSize,
                                start, start - 1L, desiredEnd, response.code());
                    }
                }
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Unexpected HTTP " + response.code() + " for " + start + "-" + desiredEnd);
                }
                ContentRangeInfo rangeInfo = ContentRangeInfo.parse(response.header("Content-Range"));
                if (rangeInfo != null && rangeInfo.total > 0L) {
                    resolvedTotalSize = rangeInfo.total;
                } else if (start == 0L) {
                    long bodyLength = response.body().contentLength();
                    if (bodyLength > 0L) {
                        resolvedTotalSize = bodyLength;
                    }
                }

                long payloadStart = rangeInfo != null ? rangeInfo.start : 0L;
                long payloadEnd;
                if (rangeInfo != null) {
                    payloadEnd = rangeInfo.end;
                } else {
                    long bodyLength = response.body().contentLength();
                    payloadEnd = bodyLength > 0L ? payloadStart + bodyLength - 1L : desiredEnd;
                }
                if (payloadEnd < start) {
                    throw new IOException("Invalid payload window " + payloadStart + "-" + payloadEnd + " for " + start);
                }
                if (start > 0L && rangeInfo == null) {
                    throw new IOException("Server ignored range request for offset " + start);
                }
                if (rangeInfo != null && rangeInfo.start > start) {
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
                    data = readUpTo(stream, bytesToRead);
                } catch (IOException bodyFailure) {
                    lastPayloadFailure = new IOException(
                            "Range payload ended early at " + start + "-" + desiredEnd,
                            bodyFailure);
                    data = null;
                }
                if (data != null && data.length > 0) {
                    long resolvedEnd = start + data.length - 1L;
                    return new WindowData(start, resolvedEnd, data, resolvedTotalSize,
                            payloadStart, payloadEnd, desiredEnd, response.code());
                }
                if (data != null) {
                    lastPayloadFailure = new IOException(
                            "Empty range payload before EOF at " + start + "/" + resolvedTotalSize);
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
                builder.header("Range", end >= start ? "bytes=" + start + "-" + end : "bytes=" + start + "-");
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

    private static byte[] readUpTo(InputStream stream, long maxBytes) throws IOException {
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
