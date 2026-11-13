package xyz.doikki.videoplayer.player;

import android.annotation.SuppressLint;
import android.media.MediaDataSource;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
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
    private static final long STREAMING_DEFAULT_WINDOW_SIZE = 24L * 1024L * 1024L;
    private static final long STREAMING_MAX_WINDOW_SIZE = 24L * 1024L * 1024L;
    private static final long STREAMING_PREFETCH_WINDOW_SIZE = 24L * 1024L * 1024L;
    // The 6677 loopback proxy may return a contiguous body up to eight bytes shorter than the
    // requested 24 MiB Range while still advertising the full entity length. Account for that
    // known per-window header defect in readiness; otherwise a full runway can remain at 99%.
    private static final long LOOPBACK_SHORT_RANGE_TOLERANCE_BYTES = 8L;
    private static final long STREAMING_BACK_BUFFER_SIZE = resolveStreamingBackBufferSize();
    private static final long PROBE_WINDOW_SIZE = 256L * 1024L;
    private static final int TAIL_METADATA_READ_MAX_BYTES = 16 * 1024;
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
    private static final long RETRY_DELAY_MS = 150L;
    // Bound recovery of one logical range. A proxy that repeats the same 416 or short body
    // must yield to the player-level retry path instead of holding the decoder at 99% forever.
    private static final long RANGE_RECOVERY_DEADLINE_MS = 30_000L;
    private static final String HEADER_PROBE_CONTAINER = "x-tvbox-probe-container";
    private static final String HEADER_PROBE_DOLBY_VISION = "x-tvbox-probe-dolbyvision";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            // Bound inactivity of one response. The range retry loop has no total elapsed-time
            // limit, so a continuously slow transfer remains valid while a dead connection can
            // recover instead of blocking the decoder forever.
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    // Keep one background fill-ahead task. Foreground decoder reads cancel that task when
    // they need a different range, so metadata parsing and seeks never wait behind preload.
    private static final ExecutorService PREFETCH_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final ScheduledExecutorService BUDGET_RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();

    private final String url;
    private final HashMap<String, String> headers = new HashMap<>();
    private final long defaultWindowSize;
    private final long maxWindowSize;
    private final long prefetchWindowSize;
    private volatile boolean prefetchEnabled;
    private final boolean interruptForegroundReadOnSeek;
    // Only the system ExoPlayer route may commit a verified contiguous short prefix. Legacy
    // MediaDataSource paths keep strict entity-length validation and retry incomplete bodies.
    private final boolean allowVerifiedShortPayload;
    /** Shared heap-aware Range cache limit; never exceeds 192 MiB. */
    private final long cacheLimitBytes;
    private final long startBufferBytes;
    private final RawRangeCacheBudget.Account rawBudget =
            RawRangeCacheBudget.PROCESS.openAccount();
    private boolean budgetRetryScheduled;
    private volatile IOException playbackCacheError;
    // A playback session can survive ExoPlayer DataSource reopen cycles. Keep the listener
    // replaceable so the current UI/controller receives bytes from the reused source without
    // creating a second Range source or resetting its initial position.
    private volatile RangeTransferListener rangeTransferListener;
    private final AtomicLong networkBytes = new AtomicLong();
    private volatile long speedSampleStartMs;
    private volatile long speedSampleStartBytes;
    private volatile long networkBytesPerSecond;
    private volatile long lastNetworkBytesAtMs;
    private volatile boolean closed;
    private volatile long totalSize = -1L;
    // Only readiness history is needed outside cachedWindows. Keeping the last window's
    // byte[] here retains an evicted 24 MiB response outside the accounted cache budget.
    private boolean hasLoadedWindow;
    private final NavigableMap<Long, WindowData> cachedWindows = new TreeMap<>();
    private long cachedBytes;
    private volatile long playbackAnchorPosition = -1L;
    // Hold the initial decoder position steady while its byte-gated startup runway fills.
    // ExoPlayer advances its read cursor before playback starts; moving the measured anchor
    // with that cursor can keep the bounded cache perpetually just short of its startup target.
    private long startupBufferAnchorPosition = -1L;
    private volatile boolean startupBufferGateSatisfied;
    // A decoder underrun gets one pinned runway. READY/loading callbacks can repeat before
    // output resumes, so they must not move that anchor or begin another byte-gating episode.
    private boolean playbackRebufferEpisodeActive;
    private long playbackRebufferEpisodeCount;
    private boolean playbackOutputObserved;
    private long lastReadPosition = -1L;
    private volatile boolean playbackPrebufferRequested;
    private volatile boolean playbackPrebufferActive;
    private volatile boolean playbackSeekAnchorPending;
    private volatile long initialDataSourcePosition;
    private volatile long playbackPrebufferAnchorPosition = -1L;
    private volatile long bufferedAheadBytesSnapshot;
    private volatile long playbackStartBufferTargetSnapshot;
    private volatile long cachedBytesSnapshot;
    // Preserve one bounded out-of-lane container-index window while trimming the forward cache.
    // Matroska can probe the Cue index near EOF before playback's byte anchor is established.
    private volatile long auxiliaryProbePosition = -1L;
    // The extractor's header/Cues pass is not a playback position. Keep one small, separately
    // accounted window while that pass resolves the actual byte seek. In particular, a tiny
    // EOF index read must not request a full playback runway or contribute to startup progress.
    private volatile boolean containerMetadataMode;
    private WindowData containerMetadataWindow;
    private long containerMetadataReservationBytes;
    // Bytes received for the currently open Range response are not committed to the cache
    // until the response is validated. Keep a separate progress-only snapshot so the loading
    // overlay can show movement while a 24 MiB window is still arriving; the byte gate uses
    // bufferedAheadBytesSnapshot and therefore never starts playback from unverified data.
    private volatile long inFlightRangeStart = -1L;
    private volatile long inFlightRangeBytes;
    private final ConcurrentHashMap<Long, InFlightRange> inFlightRanges = new ConcurrentHashMap<>();
    private final ThreadLocal<Long> inFlightRangeKey = new ThreadLocal<>();
    private int debugLoadCount;
    private int debugResponseCount;
    private int debugReadCount;
    private int debugPrefetchCount;
    private volatile Future<?> prefetchFuture;
    // Direct origins can sustain one adjacent sibling request while the primary fill is in
    // flight. Loopback/6677 remains single-lane because that proxy cancels concurrent ranges.
    private volatile Future<?> siblingPrefetchFuture;
    private long scheduledPrefetchStart = -1L;
    private volatile long activePrefetchId = -1L;
    private volatile long siblingPrefetchId = -1L;
    private long inFlightPrefetchStart = -1L;
    private long inFlightPrefetchEnd = -1L;
    private long siblingPrefetchStart = -1L;
    private long siblingInFlightStart = -1L;
    private long siblingInFlightEnd = -1L;
    private long reservedPrefetchBytes;
    private final Map<Long, PrefetchReservation> prefetchReservations = new HashMap<>();
    private ExecutorService prefetchExecutor = PREFETCH_EXECUTOR;
    private long foregroundReservationBytes;
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
    private final ReentrantLock rangeRequestLock;
    private final Semaphore directRangeSlots = new Semaphore(2, true);
    private static Method sRuntimeLogInfoMethod;
    private static Method sRuntimeLogErrorMethod;
    private static boolean sRuntimeLogLookupDone;
    private static volatile long sLastRangeErrorLogAtMs;
    private long lastCacheAdmissionLogAtMs;

    /** Receives bytes at the moment the HTTP body supplies them. */
    public interface RangeTransferListener {
        void onBytesTransferred(int bytes);

    }

    public static final class RangeMemoryBudgetException extends IOException {
        RangeMemoryBudgetException(String message) {
            super(message);
        }
    }

    public HttpRangeMediaDataSource(String url, Map<String, String> headers) {
        this(url, headers, DEFAULT_WINDOW_SIZE, MAX_WINDOW_SIZE, false, null);
    }

    public static HttpRangeMediaDataSource createForStreamingPlayback(String url, Map<String, String> headers) {
        return new HttpRangeMediaDataSource(url, headers,
                STREAMING_DEFAULT_WINDOW_SIZE, STREAMING_MAX_WINDOW_SIZE, false, true);
    }

    public static HttpRangeMediaDataSource createForSystemPlaybackBuffer(String url,
                                                                         Map<String, String> headers,
                                                                         boolean usePrefetch) {
        return new HttpRangeMediaDataSource(url, headers,
                STREAMING_DEFAULT_WINDOW_SIZE,
                STREAMING_MAX_WINDOW_SIZE,
                true,
                usePrefetch,
                false,
                null);
    }

    /**
     * Range-backed source for the ExoPlayer system-codec path. ExoPlayer already owns one
     * sequential loading loop; a second asynchronous prefetch lane only makes the 6677 proxy
     * cancel and reopen the same range. Use one bounded 24 MiB foreground window so each
     * request gets a useful contiguous runway without competing connections.
     */
    public static HttpRangeMediaDataSource createForSystemStreamingPlayback(
            String url, Map<String, String> headers) {
        return createForSystemStreamingPlayback(url, headers, null);
    }

    public static HttpRangeMediaDataSource createForSystemStreamingPlayback(
            String url, Map<String, String> headers, RangeTransferListener transferListener) {
        return createForSystemStreamingPlayback(url, headers, transferListener, false, null);
    }

    /** One system playback fill lane shared with its foreground Range reads. */
    public static HttpRangeMediaDataSource createForSystemStreamingPlayback(
            String url,
            Map<String, String> headers,
            RangeTransferListener transferListener,
            boolean usePrefetch,
            ReentrantLock sharedRangeLock) {
        return new HttpRangeMediaDataSource(url, headers,
                resolveSystemStreamingDefaultWindowSize(),
                resolveSystemStreamingMaxWindowSize(),
                true,
                Boolean.valueOf(usePrefetch),
                true,
                transferListener,
                sharedRangeLock);
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
        this(url, headers, defaultWindowSize, maxWindowSize, interruptForegroundReadOnSeek,
                prefetchEnabledOverride, allowVerifiedShortPayload, transferListener, null);
    }

    private HttpRangeMediaDataSource(String url,
                                     Map<String, String> headers,
                                     long defaultWindowSize,
                                     long maxWindowSize,
                                     boolean interruptForegroundReadOnSeek,
                                     Boolean prefetchEnabledOverride,
                                     boolean allowVerifiedShortPayload,
                                     RangeTransferListener transferListener,
                                     ReentrantLock sharedRangeLock) {
        this.url = url;
        this.defaultWindowSize = Math.max(DEFAULT_WINDOW_SIZE, defaultWindowSize);
        this.maxWindowSize = Math.max(this.defaultWindowSize, maxWindowSize);
        this.prefetchWindowSize = Math.max(this.defaultWindowSize, STREAMING_PREFETCH_WINDOW_SIZE);
        this.prefetchEnabled = prefetchEnabledOverride == null
                ? this.defaultWindowSize >= STREAMING_DEFAULT_WINDOW_SIZE
                : prefetchEnabledOverride;
        this.allowVerifiedShortPayload = allowVerifiedShortPayload;
        this.cacheLimitBytes = resolveStreamingCacheLimitBytes();
        this.startBufferBytes = resolveStreamingStartBufferSize();
        this.rangeTransferListener = transferListener;
        this.interruptForegroundReadOnSeek = interruptForegroundReadOnSeek;
        this.rangeRequestLock = sharedRangeLock == null ? new ReentrantLock() : sharedRangeLock;
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
                if (rawBudget.tryResize(window.payload.capacityBytes, 0L)) {
                    cachedWindows.put(0L, window);
                    cachedBytes = window.payload.capacityBytes;
                    applyWindowLocked(window);
                    refreshBufferSnapshotsLocked();
                }
            }
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        final long readGeneration = playbackSeekGeneration.get();
        blockingReadLock.lock();
        foregroundReadGeneration.set(readGeneration);
        try {
            if (containerMetadataMode) {
                return readContainerMetadataAt(position, buffer, offset, size);
            }
            if (size <= 0) {
                return 0;
            }
            if (position < 0L) {
                return -1;
            }
            final int requestedSize = size;
            int remaining = size;
            int totalCopied = 0;
            long cursor = position;
            synchronized (this) {
                if (closed) {
                    throw new IOException("MediaDataSource already closed");
                }
                if (totalSize >= 0L && position >= totalSize) {
                    return -1;
                }
                if (PlaybackPrebufferPolicy.shouldAnchorInitialNonZeroRead(
                        playbackSeekAnchorPending, playbackAnchorPosition >= 0L,
                        initialDataSourcePosition, position)) {
                    playbackSeekAnchorPending = true;
                }
                notePlaybackReadLocked(position, size);
            }
            while (remaining > 0) {
                WindowData active = ensureWindowLoaded(cursor, requestedSize);
                synchronized (this) {
                    if (closed) {
                        throw new IOException("MediaDataSource already closed");
                    }
                    if (totalSize >= 0L && cursor >= totalSize) {
                        break;
                    }
                    if (active == null || active.length == 0
                            || cursor < active.start || cursor > active.end) {
                        break;
                    }
                    int startIndex = (int) (cursor - active.start);
                    int available = active.length - startIndex;
                    if (available <= 0) {
                        break;
                    }
                    int copyLength = Math.min(remaining, available);
                    active.payload.copyTo(startIndex, buffer, offset, copyLength);
                    // Do not pin this response on the read stack while the next window waits
                    // on network I/O after the cache has already released it.
                    active = null;
                    offset += copyLength;
                    remaining -= copyLength;
                    totalCopied += copyLength;
                    cursor += copyLength;
                    notePlaybackReadLocked(cursor, requestedSize);
                    if (copyLength < available) {
                        break;
                    }
                    if (totalSize >= 0L && cursor >= totalSize) {
                        break;
                    }
                }
            }
            synchronized (this) {
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
            ensureSizeKnown();
            synchronized (this) {
                if (closed) {
                    throw new IOException("MediaDataSource already closed");
                }
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

    /**
     * Seeds the initial byte position declared by ExoPlayer's DataSpec. A non-zero initial open
     * is a resume/seek lane even when the extractor's first read is only a tiny tail probe.
     */
    public void setInitialDataSourcePosition(long position) {
        initialDataSourcePosition = Math.max(0L, position);
    }

    public long getInitialDataSourcePosition() {
        return Math.max(0L, initialDataSourcePosition);
    }

    /**
     * Isolates extractor metadata reads until its seek map has resolved the requested sample.
     * The caller must leave this mode before passing that sample to onExtractorSeekStarted.
     * The metadata window consumes the existing cache budget and is released on mode exit.
     */
    public void setContainerMetadataMode(boolean enabled) {
        synchronized (this) {
            if (closed || containerMetadataMode == enabled) {
                return;
            }
            containerMetadataMode = enabled;
            if (enabled) {
                playbackRebufferEpisodeActive = false;
                playbackOutputObserved = false;
                cancelActivePrefetchLocked();
            } else {
                releaseContainerMetadataWindowLocked();
            }
            refreshBufferSnapshotsLocked();
            notifyAll();
        }
    }

    private void releaseContainerMetadataWindowLocked() {
        containerMetadataWindow = null;
        foregroundReservationBytes = Math.max(0L,
                foregroundReservationBytes - containerMetadataReservationBytes);
        containerMetadataReservationBytes = 0L;
        reconcileRawBudgetLocked();
    }

    private int readContainerMetadataAt(long position, byte[] buffer, int offset, int size)
            throws IOException {
        if (size <= 0) {
            return 0;
        }
        if (position < 0L || (totalSize >= 0L && position >= totalSize)) {
            return -1;
        }
        int copied = 0;
        while (copied < size) {
            WindowData window;
            synchronized (this) {
                if (closed || !containerMetadataMode || isForegroundReadSuperseded(-1L)) {
                    throw new IOException("Container metadata read superseded");
                }
                window = findCachedWindowLocked(position);
                if (window == null && containerMetadataWindow != null
                        && position >= containerMetadataWindow.start
                        && position <= containerMetadataWindow.end) {
                    window = containerMetadataWindow;
                }
            }
            if (window == null) {
                window = loadContainerMetadataWindow(position);
            }
            if (window.length == 0 || position < window.start || position > window.end) {
                break;
            }
            int index = (int) (position - window.start);
            int count = Math.min(size - copied, window.length - index);
            window.payload.copyTo(index, buffer, offset + copied, count);
            copied += count;
            position += count;
            if (totalSize >= 0L && position >= totalSize) {
                break;
            }
        }
        return copied > 0 ? copied : -1;
    }

    private WindowData loadContainerMetadataWindow(long position) throws IOException {
        final long reservationBytes = reservationForRange(position,
                Math.min(PROBE_WINDOW_SIZE, cacheLimitBytes));
        synchronized (this) {
            releaseContainerMetadataWindowLocked();
            while (!makeCapacityForNextWindowLocked(effectiveStartupAnchorLocked(),
                    reservationBytes)) {
                if (closed || !containerMetadataMode || isForegroundReadSuperseded(-1L)) {
                    throw new IOException("Container metadata read superseded before admission");
                }
                // Metadata must be parsed before playback can use its forward cache. At a full
                // budget, release the farthest future window, preserving the active anchor.
                Map.Entry<Long, WindowData> last = cachedWindows.lastEntry();
                long anchor = effectiveStartupAnchorLocked();
                if (last != null && !(anchor >= last.getValue().start
                        && anchor <= last.getValue().end)) {
                    removeWindowLocked(last.getKey());
                    continue;
                }
                if (!hasCancelledPrefetchReservationsLocked()) {
                    throw new IOException("Range cache is full before container metadata read");
                }
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for metadata Range admission",
                            interrupted);
                }
            }
            reserveForegroundRawBytesLocked(reservationBytes);
        }
        boolean transferred = false;
        try {
            WindowData loaded = requestWindowData(position, reservationBytes);
            synchronized (this) {
                if (closed || !containerMetadataMode || isForegroundReadSuperseded(-1L)) {
                    throw new IOException("Container metadata response superseded");
                }
                foregroundReservationBytes -= reservationBytes;
                containerMetadataReservationBytes = loaded.payload.capacityBytes;
                foregroundReservationBytes += containerMetadataReservationBytes;
                containerMetadataWindow = loaded;
                reconcileRawBudgetLocked();
                if (loaded.totalSize > 0L) {
                    totalSize = loaded.totalSize;
                }
                transferred = true;
                refreshBufferSnapshotsLocked();
                return loaded;
            }
        } finally {
            if (!transferred) {
                synchronized (this) {
                    foregroundReservationBytes = Math.max(0L,
                            foregroundReservationBytes - reservationBytes);
                    reconcileRawBudgetLocked();
                    notifyAll();
                }
            }
        }
    }

    /**
     * Rebinds progress reporting when the owning playback session is reacquired by a new
     * ExoPlayer DataSource. The Range cache and playback anchor are intentionally untouched.
     */
    void setRangeTransferListener(RangeTransferListener listener) {
        rangeTransferListener = listener;
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            // Copying cached data is already serialized by this monitor. Do not wait for
            // the foreground network read lock before dropping committed arrays: a session
            // replacement can start a new cache while the cancelled old read is unwinding.
            hasLoadedWindow = false;
            cachedWindows.clear();
            cachedBytes = 0L;
            releaseContainerMetadataWindowLocked();
            rangeTransferListener = null;
            containerMetadataMode = false;
            auxiliaryProbePosition = -1L;
            bufferedAheadBytesSnapshot = 0L;
            playbackStartBufferTargetSnapshot = 0L;
            cachedBytesSnapshot = 0L;
            inFlightRangeStart = -1L;
            inFlightRangeBytes = 0L;
            inFlightRanges.clear();
            playbackAnchorPosition = -1L;
            startupBufferAnchorPosition = -1L;
            startupBufferGateSatisfied = false;
            playbackRebufferEpisodeActive = false;
            playbackOutputObserved = false;
            lastReadPosition = -1L;
            playbackPrebufferRequested = false;
            playbackPrebufferActive = false;
            playbackSeekAnchorPending = false;
            playbackPrebufferAnchorPosition = -1L;
            // Cancellation invalidates the workers, but their reservations remain charged
            // until their finally blocks drop the in-flight response arrays.
            cancelActivePrefetchLocked();
            notifyAll();
        }
        cancelActivePlaybackCall();
        cancelAllPrefetchCalls();
    }

    private void ensureSizeKnown() throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException("MediaDataSource already closed");
            }
            if (totalSize >= 0L) {
                if (prefetchEnabled && hasLoadedWindow) {
                    scheduleFillAheadLocked(0L);
                }
                return;
            }
        }
        if (containerMetadataMode) {
            loadContainerMetadataWindow(0L);
            if (totalSize < 0L) {
                throw new IOException("Container metadata response omitted content length");
            }
            return;
        }
        loadWindow(0L, PROBE_WINDOW_SIZE, 0L);
        synchronized (this) {
            if (totalSize >= 0L) {
                if (prefetchEnabled && hasLoadedWindow) {
                    scheduleFillAheadLocked(0L);
                }
                return;
            }
        }
        loadWindow(0L, Long.MAX_VALUE, 0L);
        synchronized (this) {
            if (totalSize < 0L) {
                throw new IOException("Unable to determine content length for " + url);
            }
            // Start filling as soon as length is known; network work itself stays outside this
            // monitor so a decoder cache hit or another source-state update can proceed.
            if (prefetchEnabled && hasLoadedWindow) {
                scheduleFillAheadLocked(0L);
            }
        }
    }

    private WindowData ensureWindowLoaded(long position, int requestedSize) throws IOException {
        long alignedStart;
        long windowSize;
        synchronized (this) {
            if (closed) {
                throw new IOException("MediaDataSource already closed");
            }
            WindowData cached = findCachedWindowLocked(position);
            if (cached != null) {
                applyWindowLocked(cached);
                maybeSchedulePrefetchLocked(position, requestedSize);
                return cached;
            }
            cached = awaitPrefetchWindowLocked(position);
            if (cached != null) {
                applyWindowLocked(cached);
                maybeSchedulePrefetchLocked(position, requestedSize);
                return cached;
            }
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
        }
        WindowData loaded = loadWindow(alignedStart, windowSize, position);
        synchronized (this) {
            maybeSchedulePrefetchLocked(position, requestedSize);
        }
        return loaded;
    }

    private WindowData loadWindow(long start, long minWindowSize, long requestedPosition) throws IOException {
        long reservationBytes = reservationForRange(start, Math.max(1L,
                Math.min(cacheLimitBytes, minWindowSize == Long.MAX_VALUE
                        ? maxWindowSize : minWindowSize)));
        boolean reservationHeld = false;
        synchronized (this) {
            if (closed) {
                throw new IOException("MediaDataSource already closed");
            }
            WindowData cached = findCachedWindowLocked(requestedPosition);
            if (cached != null) {
                applyWindowLocked(cached);
                return cached;
            }
            cached = awaitPrefetchWindowLocked(requestedPosition);
            if (cached != null) {
                applyWindowLocked(cached);
                return cached;
            }
            awaitStartupPrefetchBeforeForegroundReadLocked(requestedPosition);
            cancelInflightPrefetchForForegroundReadLocked(requestedPosition);
            while (true) {
                if (closed || isForegroundReadSuperseded(-1L)) {
                    throw new IOException("Range read superseded while waiting for cancelled prefetch");
                }
                // A cancelled worker may wake this read and schedule a valid replacement
                // before the reader reacquires the monitor. Reuse that response instead of
                // treating its reservation/cache bytes as a new capacity conflict.
                cached = awaitPrefetchWindowLocked(requestedPosition);
                if (cached != null) {
                    applyWindowLocked(cached);
                    return cached;
                }
                if (makeCapacityForNextWindowLocked(requestedPosition, reservationBytes)) {
                    break;
                }
                // A cancelled response can still own its byte array until its worker unwinds.
                // Let that worker release its token; admitting a replacement now would exceed
                // the cache budget, while failing the decoder read would lose a valid seek.
                if (!hasCancelledPrefetchReservationsLocked()) {
                    logCacheAdmissionBlockedLocked(resolveBufferAnchorLocked(requestedPosition),
                            start, minWindowSize);
                    throw new IOException("Range cache is full before foreground read at "
                            + requestedPosition);
                }
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for cancelled Range response", interrupted);
                }
            }
            if (closed || isForegroundReadSuperseded(-1L)) {
                throw new IOException("Range read superseded before foreground admission");
            }
            reserveForegroundRawBytesLocked(reservationBytes);
            reservationHeld = true;
        }
        try {
            WindowData loaded = requestWindowData(start, minWindowSize);
            WindowData active;
            synchronized (this) {
                if (closed) {
                    throw new IOException("MediaDataSource closed during range read");
                }
                if (isForegroundReadSuperseded(-1L)) {
                    throw new PlaybackReadSupersededException(requestedPosition,
                            playbackSeekGeneration.get());
                }
                // Atomically transfer the foreground reservation to committed cache bytes.
                // No background admission can observe the response as both reserved and cached.
                foregroundReservationBytes = Math.max(0L,
                        foregroundReservationBytes - reservationBytes);
                reservationHeld = false;
                active = findCachedWindowLocked(requestedPosition);
                if (active == null) {
                    storeWindowLocked(loaded);
                    active = findCachedWindowLocked(requestedPosition);
                }
                if (active == null && requestedPosition >= loaded.start
                        && requestedPosition <= loaded.end) {
                    active = loaded;
                }
                if (active == null) {
                    throw new IOException("Range response did not cover requested position "
                            + requestedPosition);
                }
                applyWindowLocked(active);
                reconcileRawBudgetLocked();
            }
            if (debugLoadCount < 12) {
                debugLoadCount++;
                logInfo("echo-range-source loadWindow start=" + loaded.start
                        + " end=" + loaded.requestedEnd
                        + " payload=" + loaded.payloadStart + "-" + loaded.payloadEnd
                        + " read=" + loaded.length
                        + " total=" + loaded.totalSize
                        + " code=" + loaded.responseCode);
            }
            return active;
        } finally {
            if (reservationHeld) {
                synchronized (this) {
                    foregroundReservationBytes = Math.max(0L,
                            foregroundReservationBytes - reservationBytes);
                    reconcileRawBudgetLocked();
                    notifyAll();
                }
            }
        }
    }

    private long alignWindowStart(long position) {
        if (!prefetchEnabled || position <= 0L) {
            return Math.max(0L, position);
        }
        long blockSize = Math.max(DEFAULT_WINDOW_SIZE, defaultWindowSize);
        return position - (position % blockSize);
    }

    private void notePlaybackReadLocked(long position, int requestedSize) {
        // Keep the playback anchor even when this source uses ExoPlayer's single foreground
        // loading lane (prefetchEnabled=false). Cache trimming must know which windows are
        // behind the decoder; otherwise it evicts the newest window and the next read fetches
        // the same Range again, producing the observed slow/zero-speed oscillation.
        if (position < 0L) {
            return;
        }
        if (isAuxiliaryTailProbeLocked(position, requestedSize)) {
            // Progressive extractors may inspect the Matroska Cues at EOF before reading the
            // resumed sample. Keep the seek pending until a non-metadata read establishes the
            // new playback anchor; otherwise the forward buffer is incorrectly measured from
            // the file tail instead of the saved playback position.
            auxiliaryProbePosition = position;
            return;
        }
        if (playbackSeekAnchorPending) {
            // A seek can move either direction by less than the normal discontinuity
            // threshold. Its first decoder read is still the new playback anchor.
            long previousAnchor = playbackAnchorPosition;
            playbackSeekAnchorPending = false;
            playbackAnchorPosition = PlaybackPrebufferPolicy.resolveSeekAnchor(
                    true, position, previousAnchor);
            startupBufferAnchorPosition = allowVerifiedShortPayload ? playbackAnchorPosition : -1L;
            startupBufferGateSatisfied = !allowVerifiedShortPayload;
            lastReadPosition = position;
            cancelActivePrefetchLocked();
            syncPlaybackPrebufferStateLocked(position);
            refreshBufferSnapshotsLocked();
            logInfo("echo-range-source seek-anchor position=" + position);
            return;
        }
        if (playbackAnchorPosition < 0L) {
            playbackAnchorPosition = position;
            startupBufferAnchorPosition = allowVerifiedShortPayload ? position : -1L;
            startupBufferGateSatisfied = !allowVerifiedShortPayload;
            auxiliaryProbePosition = -1L;
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
            if (startupBufferGateSatisfied || startupBufferAnchorPosition < 0L) {
                playbackAnchorPosition = position;
            }
        } else if (discontinuity) {
            long previousAnchor = playbackAnchorPosition;
            playbackAnchorPosition = position;
            if (isStartupRunwayPendingLocked()) {
                // Extractors can also issue a large metadata/sample read before the first
                // contiguous startup runway is ready. That read is not an explicit seek;
                // cancelling the active fill here would repeatedly reopen the same bridge
                // window and strand the decoder at 99%. Keep the startup fill alive until its
                // gate is satisfied; an explicit seek uses playbackSeekAnchorPending above.
                if (!playbackSeekInProgress) {
                    if (startupBufferAnchorPosition < 0L) {
                        startupBufferAnchorPosition = Math.max(0L, previousAnchor);
                    }
                    lastReadPosition = position;
                    syncPlaybackPrebufferStateLocked(position);
                    trimCacheLocked();
                    refreshBufferSnapshotsLocked();
                    return;
                }
                // An explicit seek before startup completes replaces the pinned lane.
                startupBufferAnchorPosition = position;
            }
            auxiliaryProbePosition = -1L;
            // A saved-position restore or a user seek has moved the decoder to a new
            // byte range. Do not let the old-position prefetch consume the connection
            // while the new playback range is still empty.
            cancelActivePrefetchLocked();
        }
        lastReadPosition = position;
        syncPlaybackPrebufferStateLocked(position);
        // Evict data that is safely behind the decoder before scheduling the next forward block.
        // Otherwise the cache stays full and the prefetch lane repeatedly downloads a window
        // that trimCacheLocked immediately drops.
        trimCacheLocked();
        refreshBufferSnapshotsLocked();
    }

    /** Starts or continues background prefetch around the current playback lane. */
    public void requestPlaybackPrebuffer() {
        if (containerMetadataMode) {
            return;
        }
        playbackPrebufferRequested = true;
        // A native AppFuse read may own the lock while fetching a missing range. Starting
        // playback takes priority, so this hint returns immediately instead of waiting.
        if (!blockingReadLock.tryLock()) {
            return;
        }
        try {
            synchronized (this) {
                if (closed || containerMetadataMode || playbackSeekAnchorPending) {
                    return;
                }
                if (playbackAnchorPosition < 0L) {
                    playbackAnchorPosition = 0L;
                    lastReadPosition = 0L;
                }
                syncPlaybackPrebufferStateLocked(playbackAnchorPosition);
                // A WebDAV/proxy response may omit the total in Content-Range. Start the
                // bounded first window anyway; runPrefetch() advances through unknown-length
                // windows until the normal cache limit or a verified EOF is reached.
                if (totalSize <= 0L && !hasLoadedWindow) {
                    scheduleInitialPrefetchLocked();
                } else {
                    scheduleFillAheadLocked(resolveBufferAnchorLocked(playbackAnchorPosition));
                }
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
     * Starts a new byte-buffering episode after a genuine decoder rebuffer.
     *
     * <p>The existing committed windows remain valid and an already active adjacent fill is
     * deliberately retained.  Rebuffering is different from a seek: resetting the seek
     * generation or clearing the cache here makes Exo reopen the same Range and can strand the
     * player at a false 99% boundary.  The current playback anchor becomes the pinned anchor for
     * the new startup runway, while the normal fill lane continues toward the bounded cache
     * target.</p>
     *
     * @return true only when this call begins a new, post-startup rebuffer episode
     */
    public boolean beginPlaybackRebuffer() {
        synchronized (this) {
            if (closed || containerMetadataMode || playbackSeekInProgress || playbackSeekAnchorPending
                    || !allowVerifiedShortPayload || playbackRebufferEpisodeActive
                    || !playbackOutputObserved || !startupBufferGateSatisfied) {
                // The first prepare/seek already owns a pending runway. A repeated BUFFERING
                // callback is part of that same episode until real output has resumed.
                return false;
            }
            long anchor = resolveBufferAnchorLocked(playbackAnchorPosition);
            if (anchor < 0L) {
                anchor = Math.max(0L, lastReadPosition);
            }
            // Rebuffering starts a new startup gate at the current decoder anchor. It is not a
            // seek and must not enter the native prebuffer lifecycle: pinning
            // playbackPrebufferActive here would keep the cache anchored forever after the gate
            // is satisfied, while waiting on blockingReadLock would miss the event during a
            // foreground network read.
            playbackRebufferEpisodeActive = true;
            playbackRebufferEpisodeCount++;
            playbackOutputObserved = false;
            startupBufferAnchorPosition = anchor;
            startupBufferGateSatisfied = false;
            refreshBufferSnapshotsLocked();
            // Do not cancel an active request. If it is the next contiguous window it is
            // precisely the data needed to satisfy this rebuffer episode.
            scheduleFillAheadLocked(anchor);
            notifyAll();
            logInfo("echo-range-source rebuffer-anchor begin anchor=" + anchor
                    + " cached=" + cachedBytesSnapshot
                    + " ahead=" + bufferedAheadBytesSnapshot);
            return true;
        }
    }

    /**
     * Ends a rebuffer episode only after its byte gate is satisfied and the owner observes
     * real playback output. Merely entering READY or completing a Range response is not enough:
     * Exo can reach READY while output is still paused behind the byte gate.
     */
    public void finishPlaybackRebuffer() {
        synchronized (this) {
            if (!closed && !containerMetadataMode && !playbackSeekInProgress
                    && !playbackSeekAnchorPending && startupBufferGateSatisfied) {
                playbackOutputObserved = true;
                playbackRebufferEpisodeActive = false;
            }
        }
    }

    /**
     * Gives a decoder seek priority over fill-ahead traffic without blocking the caller.
     */
    public void onPlaybackSeekStarted() {
        synchronized (this) {
            if (closed) {
                return;
            }
            playbackCacheError = null;
            playbackSeekInProgress = true;
            resetNetworkSpeed();
            playbackSeekAnchorPending = true;
            startupBufferAnchorPosition = -1L;
            startupBufferGateSatisfied = false;
            playbackRebufferEpisodeActive = false;
            playbackOutputObserved = false;
            auxiliaryProbePosition = -1L;
            playbackPrebufferRequested = false;
            playbackPrebufferActive = false;
            playbackPrebufferAnchorPosition = -1L;
            bufferedAheadBytesSnapshot = 0L;
            playbackStartBufferTargetSnapshot = startBufferBytes;
            inFlightRangeStart = -1L;
            inFlightRangeBytes = 0L;
            cancelActivePrefetchLocked();
            if (interruptForegroundReadOnSeek) {
                playbackSeekGeneration.incrementAndGet();
            }
            notifyAll();
        }
        if (interruptForegroundReadOnSeek) {
            cancelActivePlaybackCall();
        }
        cancelAllPrefetchCalls();
    }

    /**
     * Uses the extractor's resolved byte seek, rather than a header/Cue probe, as the new
     * playback runway. Called between extractor reads; cached data at this exact position
     * remains useful and can satisfy the new episode without another HTTP download.
     */
    public void onExtractorSeekStarted(long position) {
        setContainerMetadataMode(false);
        onPlaybackSeekStarted();
        synchronized (this) {
            if (closed) {
                return;
            }
            long anchor = Math.max(0L, position);
            playbackAnchorPosition = anchor;
            startupBufferAnchorPosition = anchor;
            lastReadPosition = anchor;
            playbackSeekAnchorPending = false;
            playbackSeekInProgress = false;
            refreshBufferSnapshotsLocked();
            // The extractor has resolved the actual sample position. It is valid to prefetch
            // there even if its header/Cues pass never populated the playback cache.
            if (totalSize > 0L) {
                scheduleFillAheadLocked(anchor);
            }
            notifyAll();
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

    /** True while the source is waiting for the first decoder read after a seek. */
    public boolean isPlaybackSeekInProgress() {
        return playbackSeekInProgress;
    }

    public long getBufferedAheadBytes() {
        return Math.max(0L, bufferedAheadBytesSnapshot);
    }

    /**
     * Returns contiguous cached bytes plus the verified prefix of the active HTTP response.
     * This is intended for the progress display only. It deliberately does not feed startup
     * readiness, because the response has not passed range validation until it is committed.
     */
    public long getBufferedAheadBytesForProgress() {
        synchronized (this) {
            if (containerMetadataMode || playbackSeekAnchorPending) {
                return 0L;
            }
            long anchor = effectiveStartupAnchorLocked();
            return getBufferedAheadForProgressLocked(anchor);
        }
    }

    /** Returns committed bytes plus only the contiguous prefix of active response bodies. */
    private long getBufferedAheadForProgressLocked(long anchor) {
        long safeAnchor = Math.max(0L, anchor);
        WindowData current = findCachedWindowLocked(safeAnchor);
        long end = current == null ? safeAnchor - 1L : current.end;
        if (current != null) {
            Map.Entry<Long, WindowData> next = cachedWindows.higherEntry(current.start);
            while (next != null && next.getValue().start <= end + 1L) {
                end = Math.max(end, next.getValue().end);
                next = cachedWindows.higherEntry(next.getKey());
            }
        }
        boolean extended;
        do {
            extended = false;
            // An in-flight prefix can close a gap before a later response that has already
            // committed. Revisit both kinds of intervals until their union stops growing.
            for (WindowData cached : cachedWindows.values()) {
                if (cached.start <= end + 1L && cached.end > end) {
                    end = cached.end;
                    extended = true;
                }
            }
            for (InFlightRange range : inFlightRanges.values()) {
                long bytes = range.bytes.get();
                if (bytes <= 0L || range.start > end + 1L) {
                    continue;
                }
                long candidateEnd = range.start > Long.MAX_VALUE - bytes
                        ? Long.MAX_VALUE : range.start + bytes - 1L;
                if (candidateEnd > end) {
                    end = candidateEnd;
                    extended = true;
                }
            }
        } while (extended);
        return end >= safeAnchor ? end - safeAnchor + 1L : 0L;
    }

    /** Bytes retained in the bounded cache, including a startup prefix before the decoder has
     * established its first playback anchor. This is progress evidence only; callers must not
     * use it across a pending seek because the old window then belongs to a different position.
     */
    public long getCachedBytesSnapshot() {
        return Math.max(0L, cachedBytesSnapshot);
    }

    /** Returns a stable debug snapshot of the committed cache for diagnostics. */
    public long getCachedBytesSnapshotForDiagnostics() {
        return Math.max(0L, cachedBytesSnapshot);
    }

    public boolean isPlaybackSeekAnchorPending() {
        return playbackSeekAnchorPending;
    }

    /** True after the verified startup runway has once reached its required byte target. */
    public boolean isStartupBufferGateSatisfied() {
        return !containerMetadataMode && startupBufferGateSatisfied;
    }

    public long getPlaybackStartBufferTargetBytes() {
        return Math.max(0L, playbackStartBufferTargetSnapshot);
    }

    /** A recoverable resource error; the UI may keep the player open and offer retry. */
    public IOException getPlaybackCacheError() {
        return playbackCacheError;
    }

    public long getPlaybackAnchorPositionSnapshot() {
        return Math.max(-1L, playbackAnchorPosition);
    }

    /** Enables the single background fill lane after the owning playback reader is identified. */
    public void setPlaybackFillAheadEnabled(boolean enabled) {
        synchronized (this) {
            if (closed || prefetchEnabled == enabled) {
                return;
            }
            prefetchEnabled = enabled;
            if (!enabled) {
                cancelActivePrefetchLocked();
                return;
            }
            long anchor = resolveBufferAnchorLocked(playbackAnchorPosition);
            if (totalSize > 0L && hasLoadedWindow) {
                scheduleFillAheadLocked(anchor);
            } else {
                scheduleInitialPrefetchLocked();
            }
        }
    }

    public long getKnownTotalSizeSnapshot() {
        return totalSize;
    }

    public long getCachedBytes() {
        return Math.max(0L, cachedBytesSnapshot);
    }

    /**
     * Builds a diagnostic-only snapshot of the resident Range intervals. This is intentionally
     * on-demand so normal playback does not allocate strings or add logging work.
     */
    public String getCacheDiagnosticsSnapshot() {
        synchronized (this) {
            StringBuilder intervals = new StringBuilder();
            for (WindowData window : cachedWindows.values()) {
                if (intervals.length() > 0) {
                    intervals.append(',');
                }
                intervals.append(window.start).append('-').append(window.end);
            }
            return "cached=" + cachedBytes
                    + ";limit=" + cacheLimitBytes
                    + ";anchor=" + effectiveStartupAnchorLocked()
                    + ";lastRead=" + lastReadPosition
                    + ";activePrefetch=" + activePrefetchId
                    + ";siblingPrefetch=" + siblingPrefetchId
                    + ";scheduled=" + scheduledPrefetchStart
                    + ";inflight=" + inFlightPrefetchStart + "-" + inFlightPrefetchEnd
                    + ";siblingInflight=" + siblingInFlightStart + "-" + siblingInFlightEnd
                    + ";reserved=" + reservedPrefetchBytes
                    + ";foregroundReserved=" + foregroundReservationBytes
                    + ";metadataMode=" + containerMetadataMode
                    + ";metadataBytes=" + containerMetadataReservationBytes
                    + ";generation=" + playbackSeekGeneration.get()
                    + ";rebufferEpisode=" + playbackRebufferEpisodeCount
                    + ";rebufferActive=" + playbackRebufferEpisodeActive
                    + ";intervals=" + intervals;
        }
    }

    /** Returns the measured HTTP body rate for this source, in bytes per second. */
    public long getNetworkBytesPerSecond() {
        long last = lastNetworkBytesAtMs;
        if (last <= 0L || monotonicTimeMillis() - last > 5_000L) {
            return 0L;
        }
        return Math.max(0L, networkBytesPerSecond);
    }

    private void resetNetworkSpeed() {
        long now = monotonicTimeMillis();
        long total = networkBytes.get();
        speedSampleStartMs = now;
        speedSampleStartBytes = total;
        networkBytesPerSecond = 0L;
        lastNetworkBytesAtMs = 0L;
    }

    private void recordNetworkBytes(int bytes) {
        if (bytes <= 0) {
            return;
        }
        long total = networkBytes.addAndGet(bytes);
        Long key = inFlightRangeKey.get();
        InFlightRange range = key == null ? null : inFlightRanges.get(key);
        if (range != null) {
            range.bytes.addAndGet(bytes);
            if (key == -1L) {
                inFlightRangeBytes = range.bytes.get();
            }
        }
        long now = monotonicTimeMillis();
        long start = speedSampleStartMs;
        if (start <= 0L) {
            speedSampleStartMs = now;
            speedSampleStartBytes = total - bytes;
            start = now;
        }
        lastNetworkBytesAtMs = now;
        long elapsed = now - start;
        if (elapsed < 250L) {
            long delta = Math.max(0L, total - speedSampleStartBytes);
            if (delta > 0L && networkBytesPerSecond <= 0L) {
                networkBytesPerSecond = (delta * 1_000L) / Math.max(100L, elapsed);
            }
            return;
        }
        if (elapsed >= 250L) {
            long delta = Math.max(0L, total - speedSampleStartBytes);
            networkBytesPerSecond = delta <= 0L ? 0L : (delta * 1_000L) / elapsed;
            speedSampleStartMs = now;
            speedSampleStartBytes = total;
        }
    }

    /**
     * Android's elapsedRealtime is the right clock for the live speed meter, but the player
     * module is also exercised by plain JVM tests where the framework clock is a throwing stub.
     * Keep byte reads independent from that optional metric and fall back to wall time there.
     */
    private static long monotonicTimeMillis() {
        try {
            return SystemClock.elapsedRealtime();
        } catch (RuntimeException ignored) {
            return System.currentTimeMillis();
        }
    }

    private boolean isAuxiliaryTailProbeLocked(long position, int requestedSize) {
        return isAuxiliaryTailProbe(playbackSeekInProgress, totalSize, position,
                defaultWindowSize, playbackAnchorPosition, playbackPrebufferRequested,
                lastReadPosition, requestedSize, resolveBufferAnchorLocked(playbackAnchorPosition));
    }

    static boolean isAuxiliaryTailProbe(boolean seekInProgress,
                                        long totalSize,
                                        long position,
                                        long defaultWindowSize,
                                        long playbackAnchorPosition,
                                        boolean playbackPrebufferRequested,
                                        long lastReadPosition,
                                        int requestedSize,
                                        long resolvedBufferAnchor) {
        if (totalSize <= 0L
                || position < totalSize - (defaultWindowSize * 4L)) {
            return false;
        }
        // Matroska parsers may inspect the tail before any playback read. Keep the
        // initial byte-zero fill-ahead lane intact after the first read. A resumed playback can
        // legitimately start with a non-zero tail read; treating that first read as auxiliary
        // leaves the anchor at -1 and makes every following one-byte Cue read reopen a shrinking
        // near-EOF Range forever. Only later tail reads, after a real read head exists, are
        // metadata probes.
        if (playbackAnchorPosition < 0L) {
            return !playbackPrebufferRequested && requestedSize <= TAIL_METADATA_READ_MAX_BYTES;
        }
        if (seekInProgress) {
            // An established playback source can be explicitly seeking to the tail. That read
            // belongs to the requested seek, while an unanchored source's small EOF reads above
            // are only container metadata.
            return false;
        }
        // Container parsing can read an index from the tail before it touches video data.
        // Do not let that metadata probe replace the actual playback anchor; its foreground
        // read remains free to cancel fill-ahead traffic through loadWindow().
        return resolvedBufferAnchor >= 0L
                && resolvedBufferAnchor < totalSize - (defaultWindowSize * 8L);
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
        if (loaded == null || loaded.length <= 0) {
            return;
        }
        removeOverlappingWindowsLocked(loaded.start, loaded.end);
        cachedWindows.put(loaded.start, loaded);
        cachedBytes += loaded.payload.capacityBytes;
        hasLoadedWindow = true;
        if (loaded.totalSize > 0L) {
            totalSize = loaded.totalSize;
        }
        trimCacheLocked();
        reconcileRawBudgetLocked();
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
            cachedBytes -= existing.payload.capacityBytes;
            floor = cachedWindows.floorEntry(end);
        }
    }

    private void trimCacheLocked() {
        long anchor = effectiveStartupAnchorLocked();
        long keepBehind = anchor >= 0L ? Math.max(0L, anchor - STREAMING_BACK_BUFFER_SIZE) : -1L;
        while (!cachedWindows.isEmpty()) {
            Map.Entry<Long, WindowData> first = cachedWindows.firstEntry();
            Map.Entry<Long, WindowData> last = cachedWindows.lastEntry();
            boolean removeBehind = anchor >= 0L && first.getValue().end < keepBehind;
            if (removeBehind) {
                removeWindowLocked(first.getKey());
                continue;
            }
            if (cachedBytes <= cacheLimitBytes) {
                break;
            }
            if (anchor >= 0L && first.getValue().end < anchor) {
                removeWindowLocked(first.getKey());
                continue;
            }
            // A Matroska Cue/index probe can add a small tail window after the forward cache
            // reaches its cap. Keep that auxiliary window regardless of whether the playback
            // anchor has already been established. Prefer evicting the forward window directly
            // before it; otherwise the default `remove last` branch would discard the tail and
            // every subsequent tiny Cue read would reopen the same near-EOF Range.
            Map.Entry<Long, WindowData> auxiliaryWindow = null;
            if (auxiliaryProbePosition >= 0L) {
                Map.Entry<Long, WindowData> candidate =
                        cachedWindows.floorEntry(auxiliaryProbePosition);
                if (candidate != null
                        && auxiliaryProbePosition <= candidate.getValue().end) {
                    auxiliaryWindow = candidate;
                }
            }
            if (auxiliaryWindow != null && !auxiliaryWindow.getKey().equals(first.getKey())) {
                if (auxiliaryWindow.getKey().equals(last.getKey())) {
                    Map.Entry<Long, WindowData> predecessor =
                            cachedWindows.lowerEntry(auxiliaryWindow.getKey());
                    removeWindowLocked(predecessor != null
                            && !predecessor.getKey().equals(first.getKey())
                            ? predecessor.getKey() : first.getKey());
                continue;
                }
                if (!last.getKey().equals(first.getKey())) {
                    removeWindowLocked(last.getKey());
                    continue;
                }
            }
            if (last != null
                    && anchor >= 0L
                    && last.getValue().start > anchor + cacheLimitBytes
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

    private boolean hasEvictableWindowBehindAnchorLocked(long anchor) {
        if (anchor < 0L || cachedWindows.isEmpty()) {
            return false;
        }
        Map.Entry<Long, WindowData> first = cachedWindows.firstEntry();
        long keepBehind = Math.max(0L, anchor - STREAMING_BACK_BUFFER_SIZE);
        return first != null && first.getValue().end < keepBehind;
    }

    /**
     * Make room for the next contiguous playback window before admitting a prefetch.
     *
     * The cache can contain a far-ahead window left by a container tail probe.  A simple
     * "cachedBytes < limit" check then refuses the missing bridge window forever, even though
     * evicting that far-ahead window would leave the active playback lane intact.  Keep the
     * window containing the anchor and every contiguous window after it; evict the farthest
     * non-auxiliary future window first, then stale data behind the back-buffer boundary.
     */
    private boolean makeCapacityForNextWindowLocked(long anchor, long nextWindowBytes) {
        long safeWindowBytes = Math.max(1L, Math.min(cacheLimitBytes, nextWindowBytes));
        while (cachedBytes + reservedPrefetchBytes + foregroundReservationBytes
                > cacheLimitBytes - safeWindowBytes) {
            Long candidate = findBridgeEvictionCandidateLocked(anchor);
            if (candidate == null) {
                reconcileRawBudgetLocked();
                return false;
            }
            removeWindowLocked(candidate);
        }
        reconcileRawBudgetLocked();
        return true;
    }

    private long rawReservedBytesLocked() {
        // Metadata is included in foregroundReservationBytes by the legacy local budget,
        // but its completed payload is committed ownership in the process budget.
        return Math.max(0L, reservedPrefetchBytes + foregroundReservationBytes
                - containerMetadataReservationBytes);
    }

    private void reconcileRawBudgetLocked() {
        if (!rawBudget.tryResize(cachedBytes + containerMetadataReservationBytes,
                rawReservedBytesLocked())) {
            throw new IllegalStateException("Range payload was not reserved before allocation");
        }
    }

    private boolean tryReserveRawBytesLocked(long bytes, boolean foreground) {
        if (playbackCacheError != null) {
            return false;
        }
        long anchor = effectiveStartupAnchorLocked();
        long minimumRunway = PlaybackPrebufferPolicy.resolveRequiredBytes(
                startBufferBytes, totalSize, Math.max(0L, anchor));
        boolean runwayNeeded = prefetchEnabled && !containerMetadataMode
                && getBufferedAheadBytesLocked(anchor) + rawReservedBytesLocked()
                < minimumRunway;
        boolean critical = foreground || runwayNeeded;
        reconcileRawBudgetLocked();
        if (rawBudget.tryResize(cachedBytes + containerMetadataReservationBytes,
                rawReservedBytesLocked() + bytes, critical)) {
            return true;
        }
        // Release decoded/consumed windows before spending more process heap. An outstanding
        // response remains charged until its worker exits, including after source.close().
        while (!cachedWindows.isEmpty() && anchor >= 0L
                && cachedWindows.firstEntry().getValue().end < anchor) {
            removeWindowLocked(cachedWindows.firstKey());
        }
        Long detached;
        while ((detached = findBridgeEvictionCandidateLocked(anchor)) != null) {
            removeWindowLocked(detached);
        }
        reconcileRawBudgetLocked();
        if (rawBudget.tryResize(cachedBytes + containerMetadataReservationBytes,
                rawReservedBytesLocked() + bytes, critical)) {
            return true;
        }
        if (foreground) {
            // Container/index reads and a decoder cache miss take precedence over an unused
            // future tail. Retain the anchor window; the startup gate will refill its runway.
            while (cachedWindows.size() > 1) {
                Map.Entry<Long, WindowData> tail = cachedWindows.lastEntry();
                if (anchor >= tail.getValue().start && anchor <= tail.getValue().end) {
                    break;
                }
                removeWindowLocked(tail.getKey());
                reconcileRawBudgetLocked();
                if (rawBudget.tryResize(cachedBytes + containerMetadataReservationBytes,
                        rawReservedBytesLocked() + bytes, critical)) {
                    refreshBufferSnapshotsLocked();
                    return true;
                }
            }
        }
        refreshBufferSnapshotsLocked();
        if (critical) {
            long requiredOwned = runwayNeeded ? minimumRunway
                    + Math.max(0L, cachedBytes - getBufferedAheadBytesLocked(anchor))
                    + containerMetadataReservationBytes
                    : Math.max(bytes, cachedBytes + containerMetadataReservationBytes + bytes);
            String reason = rawBudget.minimumRunwayFailure(requiredOwned);
            if (reason != null) {
                playbackCacheError = new RangeMemoryBudgetException(reason);
                logError("echo-range-source memory-budget-error " + reason
                        + " owned=" + rawBudget.chargedBytes()
                        + " required=" + requiredOwned);
            }
        }
        return false;
    }

    private long reservationForRange(long start, long requestedBytes) {
        long knownSize = totalSize;
        return Math.max(1L, knownSize > 0L && start < knownSize
                ? Math.min(requestedBytes, knownSize - Math.max(0L, start)) : requestedBytes);
    }

    private void reserveForegroundRawBytesLocked(long bytes) throws IOException {
        while (!tryReserveRawBytesLocked(bytes, true)) {
            if (playbackCacheError != null) {
                throw playbackCacheError;
            }
            if (closed || isForegroundReadSuperseded(-1L)) {
                throw new IOException("Range read cancelled while waiting for process memory");
            }
            try {
                // Cross-source cancellation can take a network read to unwind. There is no
                // download deadline: close/seek cancels this waiter, memory return admits it.
                wait(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for Range process memory", interrupted);
            }
        }
        foregroundReservationBytes += bytes;
    }

    private void scheduleBudgetRetryLocked() {
        if (closed || playbackCacheError != null || budgetRetryScheduled) {
            return;
        }
        budgetRetryScheduled = true;
        BUDGET_RETRY_EXECUTOR.schedule(() -> {
            synchronized (HttpRangeMediaDataSource.this) {
                budgetRetryScheduled = false;
                if (!closed && prefetchEnabled && !containerMetadataMode
                        && !playbackSeekInProgress && !playbackSeekAnchorPending) {
                    scheduleFillAheadLocked(resolvePrefetchAnchorLocked(0L));
                }
            }
        }, 250L, TimeUnit.MILLISECONDS);
    }

    private Long findBridgeEvictionCandidateLocked(long anchor) {
        if (cachedWindows.isEmpty()) {
            return null;
        }
        // A slower primary response can still bridge already committed sibling windows.
        // Those windows are part of the active runway, even before the bridge commits.
        // Evicting them to admit a sixth window repeatedly downloads valid future bytes.
        long protectedEnd = anchor >= 0L
                ? getNextMissingWindowStartLocked(anchor) - 1L : -1L;
        long keepBehind = anchor >= 0L
                ? Math.max(0L, anchor - STREAMING_BACK_BUFFER_SIZE) : Long.MIN_VALUE;
        Map.Entry<Long, WindowData> auxiliary = auxiliaryWindowLocked();

        // Prefer the farthest future range. It cannot be consumed before the missing bridge
        // is filled and is the common source of the observed 99% stall.
        for (Map.Entry<Long, WindowData> entry : cachedWindows.descendingMap().entrySet()) {
            if (auxiliary != null && auxiliary.getKey().equals(entry.getKey())) {
                continue;
            }
            WindowData data = entry.getValue();
            boolean activePrefix = anchor >= 0L
                    && data.start <= protectedEnd
                    && data.end >= anchor;
            if (!activePrefix && (anchor < 0L || data.start > protectedEnd)) {
                return entry.getKey();
            }
        }
        // If there is no detached future range, release only data that is safely behind the
        // decoder. Never evict the active contiguous prefix as a way to satisfy admission.
        if (anchor >= 0L) {
            for (Map.Entry<Long, WindowData> entry : cachedWindows.entrySet()) {
                if (auxiliary != null && auxiliary.getKey().equals(entry.getKey())) {
                    continue;
                }
                if (entry.getValue().end < keepBehind) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private Map.Entry<Long, WindowData> auxiliaryWindowLocked() {
        if (auxiliaryProbePosition < 0L) {
            return null;
        }
        Map.Entry<Long, WindowData> candidate =
                cachedWindows.floorEntry(auxiliaryProbePosition);
        return candidate != null && auxiliaryProbePosition <= candidate.getValue().end
                ? candidate : null;
    }

    private void logCacheAdmissionBlockedLocked(long anchor, long nextStart, long windowBytes) {
        if (!isStartupRunwayPendingLocked()) {
            return;
        }
        long now = monotonicTimeMillis();
        if (now - lastCacheAdmissionLogAtMs < 2_000L) {
            return;
        }
        lastCacheAdmissionLogAtMs = now;
        StringBuilder intervals = new StringBuilder();
        for (WindowData window : cachedWindows.values()) {
            if (intervals.length() > 0) {
                intervals.append(',');
            }
            intervals.append(window.start).append('-').append(window.end);
        }
        logError("echo-range-source cache-admission-blocked anchor=" + anchor
                + " next=" + nextStart
                + " window=" + windowBytes
                + " cached=" + cachedBytes
                + " limit=" + cacheLimitBytes
                + " activePrefetch=" + activePrefetchId
                + " lastRead=" + lastReadPosition
                + " intervals=" + intervals);
    }

    private void removeWindowLocked(Long key) {
        if (key == null) {
            return;
        }
        WindowData removed = cachedWindows.remove(key);
        if (removed != null) {
            cachedBytes -= removed.payload.capacityBytes;
        }
    }

    private void cancelInflightPrefetchForForegroundReadLocked(long requestedPosition) {
        // System ExoPlayer shares this source's Range lane with startup fill-ahead. A foreground
        // cache miss must cancel the active fill before taking that lane; otherwise the decoder
        // waits behind a full background 24 MiB request and appears to stall after the first
        // frame. Reads that hit the current window never call loadWindow, so the forward fill
        // remains active during ordinary playback. If the active Range already covers the
        // decoder's missing byte, its completed window is cheaper and safer to reuse than to
        // cancel and reopen the same offset.
        if (isActivePrefetchCoveringLocked(requestedPosition)
                || isActiveSiblingPrefetchCoveringLocked(requestedPosition)
                || isActivePrefetchAdjacentLocked(requestedPosition)) {
            return;
        }
        boolean prefetchActive = prefetchFuture != null && !prefetchFuture.isDone();
        if (!RangeWindowPolicy.shouldYieldPrefetchToForeground(prefetchActive)) {
            return;
        }
        cancelActivePrefetchLocked();
    }

    /**
     * A decoder read at the end of the resident window can be one request-size past the start of
     * the active fill. Keep that fill alive when the read is immediately adjacent; cancelling it
     * here repeatedly reopens the same 24 MiB Range and leaves only one window ahead of playback.
     */
    private boolean isActivePrefetchAdjacentLocked(long position) {
        if (prefetchFuture == null || prefetchFuture.isDone() || activePrefetchId < 0L) {
            return false;
        }
        long start = inFlightPrefetchStart >= 0L
                ? inFlightPrefetchStart : scheduledPrefetchStart;
        return start >= 0L
                && position < start
                && start - position <= Math.max(prefetchWindowSize, defaultWindowSize);
    }

    /**
     * During the initial byte-gated runway, an extractor may ask for a far metadata/sample
     * offset before the contiguous prefix is complete. Cancelling that fill here causes the
     * same missing bridge window to be reopened on every read and leaves playback at 99%.
     * Let the current fill reach the startup gate first; explicit seeks and close still bypass
     * this wait through the seek/closed guards.
     */
    private void awaitStartupPrefetchBeforeForegroundReadLocked(long requestedPosition)
            throws IOException {
        if (!isStartupRunwayPendingLocked()) {
            return;
        }
        while (!closed && isStartupRunwayPendingLocked()
                && prefetchFuture != null && !prefetchFuture.isDone()) {
            if (playbackSeekInProgress || playbackSeekAnchorPending
                    || isForegroundReadSuperseded(-1L)) {
                return;
            }
            try {
                wait(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for startup Range prefetch",
                        interrupted);
            }
            if (isActivePrefetchCoveringLocked(requestedPosition)
                    || isActiveSiblingPrefetchCoveringLocked(requestedPosition)) {
                WindowData cached = findCachedWindowLocked(requestedPosition);
                if (cached != null) {
                    return;
                }
            }
        }
    }

    private boolean isStartupRunwayPendingLocked() {
        if (playbackSeekInProgress || playbackSeekAnchorPending) {
            return false;
        }
        if (allowVerifiedShortPayload) {
            return !startupBufferGateSatisfied;
        }
        // MediaPlayer/AppFuse requests the same runway explicitly while its native decoder
        // is paused. Its startupBufferGateSatisfied flag is not a readiness measurement.
        long required = playbackStartBufferTargetSnapshot;
        return playbackPrebufferActive && required > 0L
                && getBufferedAheadBytesLocked(resolveBufferAnchorLocked(playbackAnchorPosition))
                < required;
    }

    private WindowData awaitPrefetchWindowLocked(long position) throws IOException {
        while (!closed && (isActivePrefetchCoveringLocked(position)
                || isActiveSiblingPrefetchCoveringLocked(position))) {
            if (isForegroundReadSuperseded(-1L)) {
                throw new PlaybackReadSupersededException(position,
                        playbackSeekGeneration.get());
            }
            try {
                wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for playback Range prefetch", interrupted);
            }
            WindowData cached = findCachedWindowLocked(position);
            if (cached != null) {
                return cached;
            }
        }
        if (isForegroundReadSuperseded(-1L)) {
            throw new PlaybackReadSupersededException(position,
                    playbackSeekGeneration.get());
        }
        if (closed) {
            throw new IOException("MediaDataSource closed while waiting for Range prefetch");
        }
        return findCachedWindowLocked(position);
    }

    private boolean isActivePrefetchCoveringLocked(long position) {
        if (prefetchFuture == null || prefetchFuture.isDone() || activePrefetchId < 0L) {
            return false;
        }
        long start = inFlightPrefetchStart >= 0L
                ? inFlightPrefetchStart : scheduledPrefetchStart;
        if (start < 0L) {
            return false;
        }
        long end = inFlightPrefetchEnd >= start
                ? inFlightPrefetchEnd
                : resolveRequestedPrefetchEndLocked(start, prefetchWindowSize);
        return position >= start && position <= end;
    }

    private boolean isActiveSiblingPrefetchCoveringLocked(long position) {
        if (siblingPrefetchFuture == null || siblingPrefetchFuture.isDone()
                || siblingPrefetchId < 0L || siblingInFlightStart < 0L) {
            return false;
        }
        long end = siblingInFlightEnd >= siblingInFlightStart
                ? siblingInFlightEnd
                : resolveRequestedPrefetchEndLocked(siblingInFlightStart, prefetchWindowSize);
        return position >= siblingInFlightStart && position <= end;
    }

    private void cancelActivePrefetchLocked() {
        long cancelledPrefetchId = activePrefetchId;
        prefetchFuture = null;
        scheduledPrefetchStart = -1L;
        activePrefetchId = -1L;
        inFlightPrefetchStart = -1L;
        inFlightPrefetchEnd = -1L;
        markPrefetchReservationCancelledLocked(cancelledPrefetchId);
        cancelPrefetchCall(cancelledPrefetchId);
        notifyAll();
        // Do not cancel the Future: a queued sibling already owns a reservation and must
        // execute its finally block even when cancelled before its worker starts.
        cancelSiblingPrefetchLocked();
    }

    private void cancelSiblingPrefetchLocked() {
        long cancelledId = siblingPrefetchId;
        siblingPrefetchFuture = null;
        siblingPrefetchId = -1L;
        siblingPrefetchStart = -1L;
        siblingInFlightStart = -1L;
        siblingInFlightEnd = -1L;
        markPrefetchReservationCancelledLocked(cancelledId);
        cancelPrefetchCall(cancelledId);
        notifyAll();
    }

    private PrefetchReservation reservePrefetchLocked(long id, long bytes) {
        PrefetchReservation reservation = new PrefetchReservation(id, Math.max(1L, bytes));
        if (!tryReserveRawBytesLocked(reservation.bytes, false)) {
            scheduleBudgetRetryLocked();
            return null;
        }
        prefetchReservations.put(id, reservation);
        reservedPrefetchBytes += reservation.bytes;
        return reservation;
    }

    private void markPrefetchReservationCancelledLocked(long id) {
        PrefetchReservation reservation = prefetchReservations.get(id);
        if (reservation != null) {
            reservation.cancelled = true;
        }
        // Cancelled bytes still consume memory, but cannot count toward a new seek's progress.
        inFlightRanges.remove(id);
    }

    private boolean hasCancelledPrefetchReservationsLocked() {
        for (PrefetchReservation reservation : prefetchReservations.values()) {
            if (reservation.cancelled) {
                return true;
            }
        }
        return false;
    }

    private boolean releasePrefetchReservationLocked(PrefetchReservation reservation) {
        return releasePrefetchReservationLocked(reservation, true);
    }

    private boolean releasePrefetchReservationLocked(PrefetchReservation reservation,
                                                      boolean releaseProcessCharge) {
        if (reservation == null || reservation.released) {
            return false;
        }
        reservation.released = true;
        prefetchReservations.remove(reservation.id, reservation);
        reservedPrefetchBytes -= reservation.bytes;
        if (releaseProcessCharge) {
            reconcileRawBudgetLocked();
        }
        notifyAll();
        return true;
    }

    private static final class PrefetchReservation {
        final long id;
        final long bytes;
        boolean cancelled;
        boolean released;

        PrefetchReservation(long id, long bytes) {
            this.id = id;
            this.bytes = bytes;
        }
    }

    private long resolvePrefetchAnchorLocked(long fallbackPosition) {
        return effectiveStartupAnchorLocked();
    }

    private long effectiveStartupAnchorLocked() {
        return PlaybackPrebufferPolicy.resolveStartupBufferAnchor(
                !allowVerifiedShortPayload || startupBufferGateSatisfied, startupBufferAnchorPosition,
                resolveBufferAnchorLocked(playbackAnchorPosition));
    }

    /** Keep fill-ahead bounded by this device's cache budget and the remaining media bytes. */
    private long resolveTargetBufferBytesLocked(long anchor) {
        long configured = cacheLimitBytes;
        if (allowVerifiedShortPayload && isLoopbackProxyPlayUrl(url)) {
            return PlaybackPrebufferPolicy.resolveRangeAwareRequiredBytes(
                    configured, totalSize, anchor, prefetchWindowSize,
                    LOOPBACK_SHORT_RANGE_TOLERANCE_BYTES);
        }
        return PlaybackPrebufferPolicy.resolveRequiredBytes(configured, totalSize, anchor);
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
        // Find the first hole in the union of committed windows and reservations. This must
        // also work before the first window commits: a primary request already reserves the
        // anchor, and its sibling must start after it rather than reopen that same offset.
        long safePosition = Math.max(0L, position);
        long contiguousEnd = safePosition - 1L;
        boolean extended;
        do {
            extended = false;
            for (WindowData cached : cachedWindows.values()) {
                if (cached.start <= contiguousEnd + 1L && cached.end > contiguousEnd) {
                    contiguousEnd = cached.end;
                    extended = true;
                }
            }
            long reservedEnd = reservedPrefetchEndLocked(contiguousEnd);
            if (reservedEnd > contiguousEnd) {
                contiguousEnd = reservedEnd;
                extended = true;
            }
        } while (extended);
        return contiguousEnd + 1L;
    }

    private long reservedPrefetchEndLocked(long contiguousEnd) {
        long end = contiguousEnd;
        if (inFlightPrefetchStart >= 0L && inFlightPrefetchStart <= end + 1L) {
            end = Math.max(end, inFlightPrefetchEnd);
        }
        if (siblingInFlightStart >= 0L && siblingInFlightStart <= end + 1L) {
            end = Math.max(end, siblingInFlightEnd);
        }
        return end;
    }

    private void scheduleFillAheadLocked(long position) {
        if (closed || playbackCacheError != null || budgetRetryScheduled || containerMetadataMode
                || playbackSeekAnchorPending || playbackSeekInProgress) {
            return;
        }
        long anchor = resolvePrefetchAnchorLocked(position);
        long targetAhead = resolveTargetBufferBytesLocked(anchor);
        if (!prefetchEnabled
                || getBufferedAheadBytesLocked(anchor) >= targetAhead) {
            return;
        }
        if (totalSize <= 0L || !hasLoadedWindow) {
            scheduleInitialPrefetchLocked();
            return;
        }
        if (getBufferedAheadBytesLocked(anchor) >= targetAhead) {
            return;
        }
        long nextStart = getNextMissingWindowStartLocked(anchor);
        if (nextStart >= totalSize) {
            return;
        }
        if (!makeCapacityForNextWindowLocked(anchor, prefetchWindowSize)) {
            logCacheAdmissionBlockedLocked(anchor, nextStart, prefetchWindowSize);
            return;
        }
        if (prefetchFuture != null && !prefetchFuture.isDone()) {
            long activeStart = scheduledPrefetchStart;
            boolean sameLane = activeStart >= anchor - prefetchWindowSize
                    && activeStart <= nextStart + prefetchWindowSize;
            if (sameLane) {
                scheduleSiblingPrefetchLocked(anchor);
                return;
            }
            cancelActivePrefetchLocked();
        }
        final long prefetchId = ++nextPrefetchId;
        activePrefetchId = prefetchId;
        scheduledPrefetchStart = nextStart;
        final long prefetchStart = nextStart;
        prefetchFuture = prefetchExecutor.submit(() -> runPrefetch(prefetchId, prefetchStart));
    }

    /** Starts one adjacent direct-origin Range request while the primary fill is in flight. */
    private void scheduleSiblingPrefetchLocked(long anchor) {
        if (isLoopbackProxyPlayUrl(url)
                || siblingPrefetchFuture != null && !siblingPrefetchFuture.isDone()
                || inFlightPrefetchStart < 0L) {
            return;
        }
        long siblingStart = getNextMissingWindowStartLocked(anchor);
        if (siblingStart <= inFlightPrefetchEnd) {
            return;
        }
        if (totalSize > 0L && siblingStart >= totalSize) {
            return;
        }
        long target = resolveTargetBufferBytesLocked(anchor);
        long contiguous = getBufferedAheadBytesLocked(anchor);
        if (contiguous >= target || !makeCapacityForNextWindowLocked(anchor, prefetchWindowSize)) {
            return;
        }
        final long id = ++nextPrefetchId;
        siblingPrefetchId = id;
        siblingPrefetchStart = siblingStart;
        siblingInFlightStart = siblingStart;
        siblingInFlightEnd = resolveRequestedPrefetchEndLocked(siblingStart, prefetchWindowSize);
        PrefetchReservation reservation = reservePrefetchLocked(id,
                reservationForRange(siblingStart, prefetchWindowSize));
        if (reservation == null) {
            siblingPrefetchId = -1L;
            siblingPrefetchStart = -1L;
            siblingInFlightStart = -1L;
            siblingInFlightEnd = -1L;
            return;
        }
        try {
            siblingPrefetchFuture = prefetchExecutor.submit(
                    () -> runSiblingPrefetch(id, siblingStart, reservation));
        } catch (RuntimeException rejected) {
            releasePrefetchReservationLocked(reservation);
            siblingPrefetchId = -1L;
            siblingPrefetchStart = -1L;
            siblingInFlightStart = -1L;
            siblingInFlightEnd = -1L;
            throw rejected;
        }
    }

    private void runSiblingPrefetch(long id, long start, PrefetchReservation reservation) {
        Throwable failure = null;
        WindowData loaded = null;
        long retryGeneration = -1L;
        try {
            synchronized (this) {
                if (closed || id != siblingPrefetchId) {
                    return;
                }
            }
            loaded = requestWindowData(start, prefetchWindowSize, id);
            synchronized (this) {
                if (closed || id != siblingPrefetchId) {
                    return;
                }
                if (loaded != null && loaded.length > 0) {
                    releasePrefetchReservationLocked(reservation, false);
                    storeWindowLocked(loaded);
                    loaded = null;
                    notifyAll();
                }
            }
        } catch (Throwable th) {
            failure = th;
        } finally {
            // Drop a cancelled/completed response before making its reserved bytes reusable.
            loaded = null;
            synchronized (this) {
                boolean releasedReservation = releasePrefetchReservationLocked(reservation);
                if (id == siblingPrefetchId) {
                    siblingPrefetchFuture = null;
                    siblingPrefetchId = -1L;
                    siblingPrefetchStart = -1L;
                    siblingInFlightStart = -1L;
                    siblingInFlightEnd = -1L;
                    if (failure != null && !closed && !playbackSeekInProgress) {
                        logError("echo-range-source sibling-prefetch-failed start=" + start
                                + " err=" + failure.getClass().getSimpleName());
                    }
                    if (!closed && !playbackSeekInProgress && failure == null) {
                        scheduleFillAheadLocked(resolvePrefetchAnchorLocked(start));
                    } else if (!closed && failure instanceof IOException
                            && prefetchEnabled && !containerMetadataMode
                            && !playbackSeekInProgress && !playbackSeekAnchorPending) {
                        // The primary may have stopped at a full budget while this sibling
                        // still owned the final reservation. Once that failed reservation is
                        // released, it is this worker's responsibility to refill the hole.
                        retryGeneration = playbackSeekGeneration.get();
                    }
                    notifyAll();
                } else if (releasedReservation && !closed && prefetchEnabled
                        && !playbackSeekInProgress && !playbackSeekAnchorPending) {
                    scheduleFillAheadLocked(resolvePrefetchAnchorLocked(start));
                }
            }
            if (retryGeneration >= 0L) {
                retryFillAfterFailure(start, retryGeneration, "sibling");
            }
        }
    }

    private void scheduleInitialPrefetchLocked() {
        if (playbackCacheError != null || budgetRetryScheduled || containerMetadataMode
                || prefetchFuture != null && !prefetchFuture.isDone()) {
            return;
        }
        final long prefetchId = ++nextPrefetchId;
        activePrefetchId = prefetchId;
        final long initialStart = playbackAnchorPosition >= 0L
                ? Math.max(0L, effectiveStartupAnchorLocked()) : 0L;
        scheduledPrefetchStart = initialStart;
        prefetchFuture = prefetchExecutor.submit(() -> runPrefetch(prefetchId, initialStart));
    }

    private void maybeSchedulePrefetchLocked(long nextPosition, int requestedSize) {
        if (isAuxiliaryTailProbeLocked(nextPosition, requestedSize)) {
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
        PrefetchReservation reservation = null;
        WindowData loaded = null;
        long retryGeneration = -1L;
        try {
            while (true) {
                synchronized (this) {
                    if (closed || prefetchId != activePrefetchId) {
                        return;
                    }
                    if (playbackSeekInProgress) {
                        return;
                    }
                    if (!hasLoadedWindow) {
                        nextStart = playbackAnchorPosition >= 0L
                                ? Math.max(0L, effectiveStartupAnchorLocked()) : 0L;
                    } else if (totalSize <= 0L) {
                        // Some proxy responses omit Content-Range's total ("bytes a-b").
                        // Do not stop after the first window in that case: continue from the
                        // next missing byte until the bounded cache is full. The old branch
                        // reset to byte zero and returned after one window, which caused a
                        // first-frame-then-stall loop on large WebDAV/6677 files.
                        nextStart = getNextMissingWindowStartLocked(
                                resolvePrefetchAnchorLocked(nextStart));
                    } else {
                        long anchor = resolvePrefetchAnchorLocked(nextStart);
                        if (getBufferedAheadBytesLocked(anchor)
                                >= resolveTargetBufferBytesLocked(anchor)) {
                            return;
                        }
                        nextStart = getNextMissingWindowStartLocked(anchor);
                        if (nextStart >= totalSize) {
                            return;
                        }
                    }
                    long admissionAnchor = resolvePrefetchAnchorLocked(nextStart);
                    if (!makeCapacityForNextWindowLocked(admissionAnchor, prefetchWindowSize)) {
                        logCacheAdmissionBlockedLocked(admissionAnchor, nextStart,
                                prefetchWindowSize);
                        return;
                    }
                }
                // Unknown-length responses are still normal VOD windows. Using the tiny
                // metadata probe size here made a large file refill in 256 KiB requests and
                // could not keep up with playback. Keep the same 24 MiB lane until EOF/length
                // evidence is learned.
                long requestSize = prefetchWindowSize > 0L
                        ? prefetchWindowSize : PROBE_WINDOW_SIZE;
                synchronized (this) {
                    if (closed || prefetchId != activePrefetchId) {
                        return;
                    }
                    long admissionAnchor = resolvePrefetchAnchorLocked(nextStart);
                    if (!makeCapacityForNextWindowLocked(admissionAnchor, requestSize)) {
                        logCacheAdmissionBlockedLocked(admissionAnchor, nextStart, requestSize);
                        return;
                    }
                    inFlightPrefetchStart = nextStart;
                    inFlightPrefetchEnd = resolveRequestedPrefetchEndLocked(
                            nextStart, requestSize);
                    reservation = reservePrefetchLocked(prefetchId,
                            reservationForRange(nextStart, requestSize));
                    if (reservation == null) {
                        return;
                    }
                    // Direct HTTP/WebDAV origins get one adjacent sibling as soon as the
                    // primary request has a concrete range. Loopback remains serial inside
                    // scheduleSiblingPrefetchLocked().
                    scheduleSiblingPrefetchLocked(resolvePrefetchAnchorLocked(nextStart));
                }
                loaded = requestWindowData(nextStart, requestSize, prefetchId);
                synchronized (this) {
                    if (closed || prefetchId != activePrefetchId) {
                        return;
                    }
                    if (loaded == null || loaded.length <= 0 || loaded.end < loaded.start) {
                        // A bounded response with no total length has reached EOF (or the
                        // provider returned an empty range). Stop this fill lane instead of
                        // retrying the same offset forever.
                        logInfo("echo-range-source prefetch-eof start=" + nextStart
                                + " totalKnown=" + (totalSize > 0L));
                        return;
                    }
                    releasePrefetchReservationLocked(reservation, false);
                    reservation = null;
                    storeWindowLocked(loaded);
                    inFlightPrefetchStart = -1L;
                    inFlightPrefetchEnd = -1L;
                    scheduledPrefetchStart = loaded.end + 1L;
                    notifyAll();
                    if (debugPrefetchCount < 16) {
                        debugPrefetchCount++;
                        logInfo("echo-range-source prefetched start=" + loaded.start
                                + " end=" + loaded.end
                                + " total=" + loaded.totalSize
                                + " cache=" + (cachedBytes / (1024L * 1024L)) + "MB"
                                + " totalKnown=" + (totalSize > 0L));
                    }
                    loaded = null;
                    // A short/empty response with no known total is allowed to terminate the
                    // fill lane naturally on the next request. The bounded cache and the
                    // request retry policy prevent an unknown-length source from growing
                    // without limit.
                }
            }
        } catch (Throwable th) {
            failure = th;
        } finally {
            loaded = null;
            synchronized (this) {
                boolean releasedReservation = releasePrefetchReservationLocked(reservation);
                if (failure != null && prefetchId == activePrefetchId && debugPrefetchCount < 16) {
                    debugPrefetchCount++;
                            logError("echo-range-source prefetch-failed start=" + nextStart
                            + " err=" + failure.getClass().getSimpleName()
                            + ":" + failure.getMessage());
                }
                if (prefetchId == activePrefetchId) {
                    scheduledPrefetchStart = -1L;
                    prefetchFuture = null;
                    activePrefetchId = -1L;
                    inFlightPrefetchStart = -1L;
                    inFlightPrefetchEnd = -1L;
                    // Reads can advance the playback head between the last prefetch check and
                    // this cleanup. Re-evaluate once so the cache never stops refilling merely
                    // because the previous task reached its old target first.
                    if (!closed && failure == null) {
                        scheduleFillAheadLocked(resolvePrefetchAnchorLocked(nextStart));
                    } else if (!closed && failure instanceof IOException
                            && prefetchEnabled && !containerMetadataMode
                            && !playbackSeekInProgress && !playbackSeekAnchorPending) {
                        // A transient proxy disconnect must not leave the forward-fill lane
                        // permanently disabled. Retry from the next missing contiguous window;
                        // foreground reads still take priority and can cancel this task.
                        retryGeneration = playbackSeekGeneration.get();
                    }
                } else if (releasedReservation && !closed && prefetchEnabled
                        && !playbackSeekInProgress && !playbackSeekAnchorPending) {
                    scheduleFillAheadLocked(resolvePrefetchAnchorLocked(nextStart));
                }
                notifyAll();
            }
            if (retryGeneration >= 0L) {
                retryFillAfterFailure(nextStart, retryGeneration, "primary");
            }
        }
    }

    /** Retry failed fill workers after releasing memory and without holding the source monitor. */
    private void retryFillAfterFailure(long position, long generation, String lane) {
        try {
            // A server returning an immediate HTTP error must not create a tight submit/error
            // loop. This is a retry delay, not a deadline on a slow valid media transfer.
            Thread.sleep(RangePayloadRetryPolicy.retryDelayMs(1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            if (closed || !prefetchEnabled || containerMetadataMode
                    || playbackSeekInProgress || playbackSeekAnchorPending
                    || generation != playbackSeekGeneration.get()) {
                return;
            }
            logError("echo-range-source prefetch-retry-scheduled lane=" + lane
                    + " start=" + position);
            scheduleFillAheadLocked(resolvePrefetchAnchorLocked(position));
        }
    }

    private long resolveRequestedPrefetchEndLocked(long start, long requestSize) {
        long end = start > Long.MAX_VALUE - Math.max(0L, requestSize - 1L)
                ? Long.MAX_VALUE : start + Math.max(0L, requestSize - 1L);
        return totalSize > 0L ? Math.min(end, totalSize - 1L) : end;
    }

    private void applyWindowLocked(WindowData data) {
        if (data.totalSize > 0L) {
            totalSize = data.totalSize;
        }
        hasLoadedWindow = data.length > 0;
    }

    private WindowData requestWindowData(long start, long minWindowSize) throws IOException {
        return requestWindowData(start, minWindowSize, -1L);
    }

    private WindowData requestWindowData(long start,
                                         long minWindowSize,
                                         long prefetchId) throws IOException {
        if (isForegroundReadSuperseded(prefetchId)) {
            throw new PlaybackReadSupersededException(start, playbackSeekGeneration.get());
        }
        boolean locked = false;
        boolean directSlot = false;
        try {
            // The Huawei/6677 loopback proxy must stay on one HTTP lane. Ordinary WebDAV/HTTP
            // origins can safely use two bounded lanes (primary + adjacent sibling) so a
            // decoder does not drain one 24 MiB window before the next one arrives.
            if (isLoopbackProxyPlayUrl(url)) {
                rangeRequestLock.lockInterruptibly();
                locked = true;
            } else {
                directRangeSlots.acquire();
                directSlot = true;
            }
            return requestWindowDataLocked(start, minWindowSize, prefetchId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Range request interrupted before network read", interrupted);
        } finally {
            if (locked) {
                rangeRequestLock.unlock();
            }
            if (directSlot) {
                directRangeSlots.release();
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
                    } else if (response.code() == 200
                            && rangeInfo == null
                            && start == 0L
                            && responseBodyLength > 0L) {
                        // A 206 response can legitimately omit the entity total (for
                        // example `Content-Range: bytes 0-.../*`). Its body length is only
                        // this window, never proof that the file ends here. Inferring a total
                        // from that partial body made prefetch stop after its first 24 MiB.
                        // Only an unambiguous 200 response without range metadata represents
                        // the complete entity and can safely establish its length.
                        resolvedTotalSize = responseBodyLength;
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
                        ReadResult payload;
                        beginInFlightRange(start, prefetchId);
                        try {
                            discardFully(stream, skipBytes);
                            payload = readUpTo(stream, bytesToRead, this);
                        } catch (IOException bodyFailure) {
                            lastPayloadFailure = new IOException(
                                    "Range payload ended early at " + start + "-" + desiredEnd,
                                    bodyFailure);
                            payload = null;
                        } finally {
                            endInFlightRange(start, prefetchId);
                        }
                        if (payload != null && payload.length > 0
                                && isCorruptLoopbackPayload(url, start, payload.data,
                                Math.min(payload.length, payload.data.length))) {
                            // A 6677 overrun request can return eight bytes from an image
                            // response followed by the video's EBML header. The Content-Range
                            // metadata still looks usable, so accepting this body would feed a
                            // PNG prefix into Matroska and leave ExoPlayer permanently buffering
                            // at zero. Discard the whole response and retry the same logical
                            // range; never trim the prefix because the following bytes are from a
                            // different entity, not a shifted video window.
                            lastPayloadFailure = new IOException(
                                    "Loopback range returned image-prefixed payload at " + start);
                            logError("echo-range-source reject-corrupt-payload start=" + start
                                    + " bytes=" + payload.length
                                    + " prefix=" + hexPrefix(payload.data));
                            payload = null;
                        }
                        if (payload != null && payload.length > 0) {
                            long expectedBytes = bytesToRead;
                            boolean completePayload = payload.length >= expectedBytes;
                            boolean reachedKnownEnd = resolvedTotalSize > 0L
                                    && start <= Long.MAX_VALUE - payload.length
                                    && start + payload.length >= resolvedTotalSize;
                            boolean toleratedBareLoopbackShortPayload = bareLoopbackRangeStart
                                    && isExpectedLoopbackShortPayload(expectedBytes, payload.length);
                            // Some range proxies omit the entity total (`bytes a-b/*`) while
                            // still returning a complete, bounded window. The body may be
                            // shorter than the requested 24 MiB because the proxy chose its
                            // own window, but it is valid when it covers the advertised range.
                            // Accept that window and continue from its exact end; do not infer
                            // that the entity ends here.
                            boolean completeUnknownTotalWindow = rangeInfo != null
                                    && rangeInfo.total <= 0L
                                    && payload.length >= Math.max(1L, payloadEnd - start + 1L);
                            // The loopback endpoint can keep a valid Range connection open while
                            // it fills the remote-drive entity. For the ExoPlayer route, commit a
                            // verified contiguous prefix immediately and let the next decoder
                            // read continue at its exact end. This exception is limited to the
                            // local proxy's exact +8 header signature; ordinary origins and
                            // ordinary short responses still require the advertised window.
                            boolean verifiedShortPrefix = allowVerifiedShortPayload
                                    && loopbackProxy
                                    && !completePayload
                                    && payload.length > 0
                                    && shiftedLoopbackRange;
                            if (completePayload || reachedKnownEnd || toleratedBareLoopbackShortPayload
                                    || completeUnknownTotalWindow
                                    || verifiedShortPrefix) {
                                if (debugLoadCount < 12) {
                                    logInfo("echo-range-source body start=" + start
                                            + " bytes=" + payload.length
                                            + " prefix=" + hexPrefix(payload.data));
                                }
                                long resolvedEnd = start + payload.length - 1L;
                                return new WindowData(start, resolvedEnd, payload,
                                        resolvedTotalSize,
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
                                            + expectedBytes + " bytes, received " + payload.length);
                        }
                        if (payload != null && payload.length == 0) {
                            lastPayloadFailure = new IOException(
                                    "Empty range payload before EOF at " + start + "/" + resolvedTotalSize);
                        }
                        // Drop rejected backing arrays before another response is opened or a
                        // retry sleeps. Only the admitted response may keep its reservation alive.
                        payload = null;
                    }
                }
            } finally {
                response.close();
                clearRangeCall(prefetchId);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - recoveryStartedAtNanos);
            if (elapsedMs >= RANGE_RECOVERY_DEADLINE_MS
                    || !RangePayloadRetryPolicy.shouldRetry(payloadAttempt, elapsedMs, prefetch)) {
                throw lastPayloadFailure == null
                        ? new IOException("Unable to recover range payload at " + start
                        + " after " + elapsedMs + "ms")
                        : lastPayloadFailure;
            }
            logError("echo-range-source payload-retry attempt=" + payloadAttempt
                    + " start=" + start + " end=" + desiredEnd
                    + " total=" + knownTotalSize + " prefetch=" + prefetch
                    + " elapsedMs=" + elapsedMs
                    + " reason=" + (lastPayloadFailure == null
                            ? "unresolved-payload" : lastPayloadFailure.getMessage()));
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
        if (containerMetadataMode || playbackSeekAnchorPending) {
            bufferedAheadBytesSnapshot = 0L;
            playbackStartBufferTargetSnapshot = startBufferBytes;
            cachedBytesSnapshot = Math.max(0L, cachedBytes + containerMetadataReservationBytes);
            return;
        }
        long anchor = PlaybackPrebufferPolicy.resolveStartupBufferAnchor(
                !allowVerifiedShortPayload || startupBufferGateSatisfied, startupBufferAnchorPosition,
                resolveBufferAnchorLocked(0L));
        bufferedAheadBytesSnapshot = getBufferedAheadBytesLocked(anchor);
        playbackStartBufferTargetSnapshot = allowVerifiedShortPayload && isLoopbackProxyPlayUrl(url)
                ? PlaybackPrebufferPolicy.resolveRangeAwareRequiredBytes(
                startBufferBytes, totalSize, anchor, prefetchWindowSize,
                LOOPBACK_SHORT_RANGE_TOLERANCE_BYTES)
                : PlaybackPrebufferPolicy.resolveRequiredBytes(
                startBufferBytes, totalSize, anchor);
        startupBufferGateSatisfied = PlaybackPrebufferPolicy.isStartupBufferGateSatisfied(
                startupBufferGateSatisfied, bufferedAheadBytesSnapshot,
                playbackStartBufferTargetSnapshot);
        cachedBytesSnapshot = Math.max(0L, cachedBytes);
    }

    private void beginInFlightRange(long start, long prefetchId) {
        long key = prefetchId >= 0L ? prefetchId : -1L;
        InFlightRange range = new InFlightRange(Math.max(0L, start));
        inFlightRanges.put(key, range);
        inFlightRangeKey.set(key);
        if (key == -1L) {
            inFlightRangeStart = range.start;
            inFlightRangeBytes = 0L;
        }
    }

    private void endInFlightRange(long start, long prefetchId) {
        long key = prefetchId >= 0L ? prefetchId : -1L;
        inFlightRanges.remove(key);
        inFlightRangeKey.remove();
        if (key == -1L && inFlightRangeStart == start) {
            inFlightRangeStart = -1L;
            inFlightRangeBytes = 0L;
        }
    }

    private static final class InFlightRange {
        final long start;
        final AtomicLong bytes = new AtomicLong();

        InFlightRange(long start) {
            this.start = start;
        }
    }

    private Response openRangeResponse(long start, long end, long prefetchId) throws IOException {
        IOException lastError = null;
        int attempt = 0;
        while (true) {
            attempt++;
            if (isRangeRequestCancelled(prefetchId)) {
                throw new IOException("Range request cancelled before retry");
            }
            Call call = null;
            try {
                Request.Builder builder = new Request.Builder()
                        .url(url)
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
                if (isRangeRequestCancelled(prefetchId)) {
                    throw e;
                }
                logError("echo-range-source retry " + attempt + "/unlimited"
                        + " start=" + start + " end=" + end + " err=" + e.getMessage());
                try {
                    Thread.sleep(RangePayloadRetryPolicy.retryDelayMs(attempt));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private boolean isRangeRequestCancelled(long prefetchId) {
        return closed
                || Thread.currentThread().isInterrupted()
                || (prefetchId >= 0L && !isPrefetchActive(prefetchId))
                || isForegroundReadSuperseded(prefetchId);
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
        if (!isPrefetchActive(prefetchId) || Thread.currentThread().isInterrupted()) {
            call.cancel();
            return false;
        }
        activePrefetchCalls.put(prefetchId, call);
        if (!isPrefetchActive(prefetchId) || Thread.currentThread().isInterrupted()) {
            activePrefetchCalls.remove(prefetchId, call);
            call.cancel();
            return false;
        }
        return true;
    }

    private boolean isPrefetchActive(long prefetchId) {
        return !closed && prefetchId >= 0L
                && (prefetchId == activePrefetchId || prefetchId == siblingPrefetchId);
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
        // Normal Range state is intentionally silent. This path can be called for every
        // decoder read and logging here materially reduces throughput on low-end devices.
    }

    private static void logError(String message) {
        long now = System.currentTimeMillis();
        long previous = sLastRangeErrorLogAtMs;
        if (previous > 0L && now - previous < 1_000L) {
            return;
        }
        sLastRangeErrorLogAtMs = now;
        try {
            Log.e(TAG, message);
        } catch (Throwable ignored) {
        }
        writeRuntimeErrorLog(message);
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

    private static void writeRuntimeErrorLog(String message) {
        try {
            Method method = getRuntimeLogErrorMethod();
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

    private static Method getRuntimeLogErrorMethod() {
        if (sRuntimeLogErrorMethod != null) {
            return sRuntimeLogErrorMethod;
        }
        synchronized (HttpRangeMediaDataSource.class) {
            if (sRuntimeLogErrorMethod != null) {
                return sRuntimeLogErrorMethod;
            }
            try {
                Class<?> logClass = Class.forName("com.github.tvbox.osc.util.LOG");
                sRuntimeLogErrorMethod = logClass.getMethod("e", String.class);
            } catch (Throwable ignored) {
                sRuntimeLogErrorMethod = null;
            }
            return sRuntimeLogErrorMethod;
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

    private static long resolveSystemStreamingDefaultWindowSize() {
        return STREAMING_DEFAULT_WINDOW_SIZE;
    }

    private static long resolveSystemStreamingMaxWindowSize() {
        return STREAMING_MAX_WINDOW_SIZE;
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
        // Four complete 24 MiB windows for every APK. Small entities and tails only require
        // the remaining bytes; loopback tolerance is local to its verified header defect.
        return PlaybackPrebufferPolicy.resolveStartBufferBytes(Runtime.getRuntime().maxMemory());
    }

    private static long resolveStreamingCacheLimitBytes() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        return PlaybackPrebufferPolicy.resolveCacheLimitBytes(maxMemory, is32BitProcess());
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

    static final class ReadResult {
        static final int SEGMENT_BYTES = 64 * 1024;
        // The first segment is sufficient for signature validation and diagnostic prefixes.
        final byte[] data;
        final int length;
        final long capacityBytes;
        final byte[][] segments;

        ReadResult(byte[] data, int length) {
            this(new byte[][]{data}, length);
        }

        ReadResult(byte[][] segments, int length) {
            this.segments = segments;
            this.data = segments.length == 0 ? new byte[0] : segments[0];
            this.length = length;
            long capacity = 0L;
            for (byte[] segment : segments) {
                capacity += segment.length;
            }
            if (length < 0 || length > capacity) {
                throw new IllegalArgumentException("Invalid Range payload length " + length);
            }
            this.capacityBytes = capacity;
        }

        void copyTo(int sourceOffset, byte[] target, int targetOffset, int count) {
            if (sourceOffset < 0 || count < 0 || sourceOffset > length - count) {
                throw new IndexOutOfBoundsException("Read past valid Range payload");
            }
            if (segments.length == 1) {
                System.arraycopy(data, sourceOffset, target, targetOffset, count);
                return;
            }
            while (count > 0) {
                byte[] segment = segments[sourceOffset / SEGMENT_BYTES];
                int inSegment = sourceOffset % SEGMENT_BYTES;
                int copied = Math.min(count, segment.length - inSegment);
                System.arraycopy(segment, inSegment, target, targetOffset, copied);
                sourceOffset += copied;
                targetOffset += copied;
                count -= copied;
            }
        }
    }

    static ReadResult readUpTo(InputStream stream,
                              long maxBytes,
                              HttpRangeMediaDataSource owner) throws IOException {
        if (maxBytes <= 0L) {
            return new ReadResult(new byte[0], 0);
        }
        if (maxBytes > Integer.MAX_VALUE) {
            throw new IOException("Requested window too large: " + maxBytes);
        }
        if (maxBytes > ReadResult.SEGMENT_BYTES) {
            return readSegmentedPayload(stream, (int) maxBytes, owner);
        }
        byte[] buffer = new byte[(int) maxBytes];
        int total = 0;
        while (total < buffer.length) {
            int read;
            try {
                read = stream.read(buffer, total, buffer.length - total);
            } catch (IOException error) {
                // A transport exception is not an EOF. Returning a partial body here lets
                // the system route commit an incomplete Range window as a "verified short
                // prefix"; playback then reaches that false boundary and stalls. Clean
                // short responses still return naturally when read() yields -1 below, while
                // actual transport failures must reopen the same Range through the retry path.
                throw error;
            }
            if (read < 0) {
                break;
            }
            if (read > 0 && owner != null) {
                owner.recordNetworkBytes(read);
                RangeTransferListener listener = owner.rangeTransferListener;
                if (listener != null) {
                    listener.onBytesTransferred(read);
                }
            }
            total += read;
        }
        // Avoid a second nearly 24 MiB allocation for the proxy's known eight-byte-short
        // responses. Retain at most 16 KiB of padding; substantially shorter clean bodies
        // still need compact storage, otherwise repeated short prefixes exhaust the physical
        // cache budget before their valid bytes can satisfy the startup runway. That fallback
        // copy is bounded by the short body's length, rather than the complete Range capacity.
        return new ReadResult(buffer.length - total <= 16 * 1024
                ? buffer : Arrays.copyOf(buffer, total), total);
    }

    private static ReadResult readSegmentedPayload(InputStream stream, int maxBytes,
                                                   HttpRangeMediaDataSource owner)
            throws IOException {
        ArrayList<byte[]> segments = new ArrayList<>();
        int total = 0;
        long capacity = 0L;
        boolean eof = false;
        while (total < maxBytes && !eof) {
            byte[] segment = new byte[Math.min(ReadResult.SEGMENT_BYTES, maxBytes - total)];
            int filled = 0;
            while (filled < segment.length) {
                // IOException is a transport failure, never a publishable clean short body.
                int read = stream.read(segment, filled, segment.length - filled);
                if (read < 0) {
                    eof = true;
                    break;
                }
                if (read == 0) {
                    int value = stream.read();
                    if (value < 0) {
                        eof = true;
                        break;
                    }
                    segment[filled] = (byte) value;
                    read = 1;
                }
                filled += read;
                total += read;
                if (owner != null) {
                    owner.recordNetworkBytes(read);
                    RangeTransferListener listener = owner.rangeTransferListener;
                    if (listener != null) {
                        listener.onBytesTransferred(read);
                    }
                }
            }
            if (filled == 0) {
                break;
            }
            // Never duplicate a whole 24 MiB response. A final short segment is compacted
            // only when both arrays fit inside the original response reservation. Near-full
            // responses retain <=64 KiB padding, counted as real cache capacity.
            if (filled < segment.length && capacity + segment.length + filled <= maxBytes) {
                segment = Arrays.copyOf(segment, filled);
            }
            segments.add(segment);
            capacity += segment.length;
        }
        return new ReadResult(segments.toArray(new byte[segments.size()][]), total);
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
        return isCorruptLoopbackPayload(responseUrl, requestedStart, data,
                data == null ? 0 : data.length);
    }

    private static boolean isCorruptLoopbackPayload(String responseUrl,
                                                    long requestedStart,
                                                    byte[] data,
                                                    int length) {
        if (requestedStart <= 0L
                || !isLoopbackProxyPlayUrl(responseUrl)
                || data == null
                || length < PNG_SIGNATURE.length + EBML_SIGNATURE.length) {
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
        private final ReadResult payload;
        // Only these bytes came from the entity. payload.capacityBytes accounts for all
        // backing segments, including any padding that cannot be read as video.
        private final int length;
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
            this(start, end, data, data.length, totalSize, payloadStart, payloadEnd,
                    requestedEnd, responseCode);
        }

        private WindowData(long start,
                           long end,
                           byte[] data,
                           int length,
                           long totalSize,
                           long payloadStart,
                           long payloadEnd,
                           long requestedEnd,
                           int responseCode) {
            this(start, end, new ReadResult(data, length), totalSize, payloadStart, payloadEnd,
                    requestedEnd, responseCode);
        }

        private WindowData(long start,
                           long end,
                           ReadResult payload,
                           long totalSize,
                           long payloadStart,
                           long payloadEnd,
                           long requestedEnd,
                           int responseCode) {
            this.start = start;
            this.end = end;
            this.payload = payload;
            this.data = payload.data;
            this.length = payload.length;
            this.totalSize = totalSize;
            this.payloadStart = payloadStart;
            this.payloadEnd = payloadEnd;
            this.requestedEnd = requestedEnd;
            this.responseCode = responseCode;
        }
    }
}
