package xyz.doikki.videoplayer.player;

import android.media.MediaDataSource;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class HttpRangeMediaDataSource extends MediaDataSource {
    private static final String TAG = "HttpRangeDataSource";
    private static final long DEFAULT_WINDOW_SIZE = 512L * 1024L;
    private static final long MAX_WINDOW_SIZE = 2L * 1024L * 1024L;
    private static final long STREAMING_DEFAULT_WINDOW_SIZE = resolveStreamingDefaultWindowSize();
    private static final long STREAMING_MAX_WINDOW_SIZE = resolveStreamingMaxWindowSize();
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
    private static final ExecutorService PREFETCH_EXECUTOR = Executors.newFixedThreadPool(2);

    private final String url;
    private final HashMap<String, String> headers = new HashMap<>();
    private final long defaultWindowSize;
    private final long maxWindowSize;
    private final boolean prefetchEnabled;
    private boolean closed;
    private long totalSize = -1L;
    private long windowStart = -1L;
    private long windowEnd = -1L;
    private byte[] windowData = new byte[0];
    private final NavigableMap<Long, WindowData> cachedWindows = new TreeMap<>();
    private long cachedBytes;
    private long playbackAnchorPosition = -1L;
    private long lastReadPosition = -1L;
    private int debugLoadCount;
    private int debugReadCount;
    private int debugPrefetchCount;
    private Future<?> prefetchFuture;
    private long scheduledPrefetchStart = -1L;
    private long activePrefetchId = -1L;
    private long nextPrefetchId;
    private static Method sRuntimeLogInfoMethod;
    private static boolean sRuntimeLogLookupDone;

    public HttpRangeMediaDataSource(String url, Map<String, String> headers) {
        this(url, headers, DEFAULT_WINDOW_SIZE, MAX_WINDOW_SIZE);
    }

    public static HttpRangeMediaDataSource createForStreamingPlayback(String url, Map<String, String> headers) {
        return new HttpRangeMediaDataSource(url, headers, STREAMING_DEFAULT_WINDOW_SIZE, STREAMING_MAX_WINDOW_SIZE);
    }

    private HttpRangeMediaDataSource(String url,
                                     Map<String, String> headers,
                                     long defaultWindowSize,
                                     long maxWindowSize) {
        this.url = url;
        this.defaultWindowSize = Math.max(DEFAULT_WINDOW_SIZE, defaultWindowSize);
        this.maxWindowSize = Math.max(this.defaultWindowSize, maxWindowSize);
        this.prefetchEnabled = this.defaultWindowSize >= STREAMING_DEFAULT_WINDOW_SIZE;
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
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
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
        return totalCopied > 0 ? totalCopied : -1;
    }

    @Override
    public synchronized long getSize() throws IOException {
        if (closed) {
            throw new IOException("MediaDataSource already closed");
        }
        ensureSizeKnown();
        return totalSize;
    }

    @Override
    public synchronized void close() {
        closed = true;
        windowData = new byte[0];
        windowStart = -1L;
        windowEnd = -1L;
        cachedWindows.clear();
        cachedBytes = 0L;
        playbackAnchorPosition = -1L;
        lastReadPosition = -1L;
        cancelActivePrefetchLocked();
    }

    private void ensureSizeKnown() throws IOException {
        if (totalSize >= 0L) {
            return;
        }
        loadWindow(0L, PROBE_WINDOW_SIZE, 0L);
        if (totalSize < 0L) {
            loadWindow(0L, Long.MAX_VALUE, 0L);
        }
        if (totalSize < 0L) {
            throw new IOException("Unable to determine content length for " + url);
        }
    }

    private void ensureWindowLoaded(long position, int requestedSize) throws IOException {
        WindowData cached = findCachedWindowLocked(position);
        if (cached != null) {
            applyWindowLocked(cached);
            maybeSchedulePrefetchLocked(position, requestedSize);
            return;
        }
        long windowSize = Math.max(defaultWindowSize, (long) requestedSize * 2L);
        windowSize = Math.min(windowSize, maxWindowSize);
        long alignedStart = alignWindowStart(position);
        loadWindow(alignedStart, windowSize, position);
        maybeSchedulePrefetchLocked(position, requestedSize);
    }

    private void loadWindow(long start, long minWindowSize, long requestedPosition) throws IOException {
        cancelInflightPrefetchForWindowLocked(start);
        WindowData loaded = requestWindowData(start, minWindowSize);
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

    private synchronized long getKnownTotalSizeSnapshot() {
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
            return;
        }
        long delta = lastReadPosition < 0L ? 0L : position - lastReadPosition;
        if (delta >= 0L && delta <= defaultWindowSize * 4L) {
            playbackAnchorPosition = position;
        } else if (Math.abs(delta) >= defaultWindowSize * 4L) {
            playbackAnchorPosition = position;
        }
        lastReadPosition = position;
    }

    private boolean isAuxiliaryTailProbeLocked(long position) {
        return totalSize > 0L
                && playbackAnchorPosition >= 0L
                && position >= totalSize - (defaultWindowSize * 4L)
                && playbackAnchorPosition < totalSize - (defaultWindowSize * 8L);
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
        long anchor = playbackAnchorPosition;
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

    private void cancelInflightPrefetchForWindowLocked(long start) {
        if (scheduledPrefetchStart != start
                || prefetchFuture == null
                || prefetchFuture.isDone()) {
            return;
        }
        cancelActivePrefetchLocked();
    }

    private void cancelActivePrefetchLocked() {
        Future<?> future = prefetchFuture;
        prefetchFuture = null;
        scheduledPrefetchStart = -1L;
        activePrefetchId = -1L;
        if (future != null) {
            future.cancel(true);
        }
    }

    private long resolvePrefetchAnchorLocked(long fallbackPosition) {
        return playbackAnchorPosition >= 0L ? playbackAnchorPosition : fallbackPosition;
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
                || totalSize <= 0L
                || windowData.length <= 0) {
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
            boolean sameLane = activeStart >= anchor - defaultWindowSize
                    && activeStart <= nextStart + defaultWindowSize;
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

    private void maybeSchedulePrefetchLocked(long nextPosition, int requestedSize) {
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
                WindowData loaded = requestWindowData(nextStart, defaultWindowSize);
                synchronized (this) {
                    if (closed || prefetchId != activePrefetchId) {
                        return;
                    }
                    storeWindowLocked(loaded);
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
        Response response = openRangeResponse(start, desiredEnd);
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
            discardFully(stream, skipBytes);
            byte[] data = readUpTo(stream, bytesToRead);
            if (data.length == 0 && resolvedTotalSize >= 0L && start < resolvedTotalSize) {
                throw new IOException("Empty range payload before EOF at " + start + "/" + resolvedTotalSize);
            }
            long resolvedEnd = data.length > 0 ? start + data.length - 1L : start - 1L;
            return new WindowData(start, resolvedEnd, data, resolvedTotalSize,
                    payloadStart, payloadEnd, desiredEnd, response.code());
        } finally {
            response.close();
        }
    }

    private Response openRangeResponse(long start, long end) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .header("Connection", "close")
                        .header("Accept-Encoding", "identity");
                builder.header("Range", end >= start ? "bytes=" + start + "-" + end : "bytes=" + start + "-");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!TextUtils.isEmpty(entry.getKey())
                            && entry.getValue() != null
                            && !isManagedRequestHeader(entry.getKey())) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
                return CLIENT.newCall(builder.build()).execute();
            } catch (IOException e) {
                lastError = e;
                logInfo("echo-range-source retry " + attempt + "/" + MAX_RETRIES
                        + " start=" + start + " end=" + end + " err=" + e.getMessage());
                if (attempt >= MAX_RETRIES) {
                    break;
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

    private static void logInfo(String message) {
        Log.i(TAG, message);
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
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 4L * 1024L * 1024L;
        }
        return 8L * 1024L * 1024L;
    }

    private static long resolveStreamingMaxWindowSize() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 12L * 1024L * 1024L;
        }
        return 24L * 1024L * 1024L;
    }

    private static long resolveStreamingTargetBufferSize() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 16L * 1024L * 1024L;
        }
        return 32L * 1024L * 1024L;
    }

    private static long resolveStreamingMaxCacheSize() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 32L * 1024L * 1024L;
        }
        return 64L * 1024L * 1024L;
    }

    private static long resolveStreamingBackBufferSize() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0L && maxMemory <= 640L * 1024L * 1024L) {
            return 4L * 1024L * 1024L;
        }
        return 8L * 1024L * 1024L;
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
            int read = stream.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total == buffer.length ? buffer : Arrays.copyOf(buffer, total);
    }

    private static boolean isManagedRequestHeader(String key) {
        if (TextUtils.isEmpty(key)) {
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
            if (TextUtils.isEmpty(headerValue)) {
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
            if (TextUtils.isEmpty(headerValue)) {
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
