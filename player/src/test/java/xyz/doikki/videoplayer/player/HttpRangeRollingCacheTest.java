package xyz.doikki.videoplayer.player;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Real HTTP coverage for a rolling cache at byte offsets above 200 GiB. Most cases scale the
 * window/start/cap to 2/8/20 MiB; the cache-hit case also exercises real 24/96/192 MiB sizes.
 * The real 4/8 MiB back-buffer is retained, so after consumption the total cap may include read
 * windows as well as the continuous forward runway.
 */
public class HttpRangeRollingCacheTest {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final int WINDOW = (int) (2L * MIB);
    private static final long START = 4L * WINDOW;
    private static final long CAP = 10L * WINDOW;
    private static final long TOTAL = 450L * GIB + 765_432L;
    private static final long FIRST = 220L * GIB + 12_345L;

    @Test
    public void rebufferEpisodeIsArmedOnceAndReusesContiguousCachedRunway() throws Exception {
        try (RangeOrigin origin = new RangeOrigin()) {
            ExecutorService fills = Executors.newFixedThreadPool(2);
            HttpRangeMediaDataSource source = createSource(origin, fills);
            try {
                assertTrue("initial prepare must not arm a rebuffer episode",
                        !source.beginPlaybackRebuffer());
                assertEquals(0L, longField(source, "playbackRebufferEpisodeCount"));

                source.onExtractorSeekStarted(FIRST);
                awaitFullCache(source, origin, CAP,
                        "initial startup must fill the bounded contiguous runway");
                assertTrue("a full initial cache without actual output is not a rebuffer",
                        !source.beginPlaybackRebuffer());
                source.finishPlaybackRebuffer();
                long seekGeneration = ((AtomicLong) objectField(source,
                        "playbackSeekGeneration")).get();
                source.setPlaybackFillAheadEnabled(false);

                byte[] block = new byte[WINDOW];
                for (long offset = 0L; offset < 6L * WINDOW; offset += WINDOW) {
                    assertEquals(block.length,
                            source.readAt(FIRST + offset, block, 0, block.length));
                    assertPayload(FIRST + offset, block);
                }
                byte[] oneByte = new byte[1];
                long anchor = FIRST + 6L * WINDOW;
                assertEquals(1, source.readAt(anchor, oneByte, 0, 1));
                assertPayload(anchor, oneByte);
                anchor++;
                assertTrue("ordinary reads must retain the completed initial gate",
                        source.isStartupBufferGateSatisfied());
                assertTrue("test position must leave just under one startup runway",
                        source.getBufferedAheadBytes() < START);
                long backBuffer = staticLong("STREAMING_BACK_BUFFER_SIZE");
                int retiredWindows = (int) ((anchor - FIRST - backBuffer) / WINDOW);

                assertTrue("a true post-startup underrun must start one episode",
                        source.beginPlaybackRebuffer());
                assertTrue("rebuffering must keep its pinned position until real output resumes",
                        source.getPlaybackAnchorPositionSnapshot() <= anchor);
                assertTrue("the short cached prefix must require more bytes",
                        !source.isStartupBufferGateSatisfied());
                source.finishPlaybackRebuffer();
                assertTrue("premature READY/output notification cannot finish a pending gate",
                        booleanField(source, "playbackRebufferEpisodeActive"));
                long pinnedAnchor = source.getPlaybackAnchorPositionSnapshot();
                assertTrue("duplicate BUFFERING callbacks must not re-arm/re-pin",
                        !source.beginPlaybackRebuffer());
                assertEquals(pinnedAnchor, source.getPlaybackAnchorPositionSnapshot());
                assertEquals(1L, longField(source, "playbackRebufferEpisodeCount"));

                source.setPlaybackFillAheadEnabled(true);
                awaitFullCache(source, origin, START,
                        "rebuffering must preserve valid bytes and refill the bounded runway");
                assertTrue("the retained range plus one adjacent response must restore the gate",
                        source.isStartupBufferGateSatisfied());
                assertEquals("only retired slots may be downloaded", 10 + retiredWindows,
                        origin.requestCount.get());
                assertTrue("a full gate before actual output is still the same episode",
                        !source.beginPlaybackRebuffer());
                assertEquals("rebuffering must never mutate the seek generation", seekGeneration,
                        ((AtomicLong) objectField(source, "playbackSeekGeneration")).get());
                source.finishPlaybackRebuffer();
                assertTrue("finish after verified output permits a later episode",
                        source.beginPlaybackRebuffer());
                assertEquals(2L, longField(source, "playbackRebufferEpisodeCount"));
                source.onPlaybackSeekStarted();
                assertTrue("a real seek replaces and clears the current episode",
                        !booleanField(source, "playbackRebufferEpisodeActive"));
                assertBudget(source);
                origin.assertNoDuplicateRanges();
                origin.assertNoFailure();
            } finally {
                source.close();
                fills.shutdownNow();
            }
        }
    }

    @Test
    public void failedReservedSiblingRefillsMissingBridgeAfterThePrimaryStoppedAtCapacity()
            throws Exception {
        try (RangeOrigin origin = new RangeOrigin()) {
            origin.failedFirstRange = FIRST + WINDOW;
            ExecutorService fills = Executors.newFixedThreadPool(2);
            HttpRangeMediaDataSource source = createSource(origin, fills);
            try {
                source.onExtractorSeekStarted(FIRST);
                assertTrue("second window must be held by the sibling response",
                        origin.failedRequestOpened.await(5L, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8L);
                boolean primaryStopped = false;
                while (System.nanoTime() < deadline) {
                    origin.assertNoFailure();
                    assertBudget(source);
                    synchronized (source) {
                        primaryStopped = longField(source, "cachedBytes") == CAP - WINDOW
                                && longField(source, "reservedPrefetchBytes") == WINDOW
                                && longField(source, "activePrefetchId") < 0L
                                && longField(source, "siblingPrefetchId") >= 0L;
                    }
                    if (primaryStopped) break;
                    Thread.sleep(5L);
                }
                assertTrue("primary must stop while the stalled sibling owns the final slot: "
                        + source.getCacheDiagnosticsSnapshot(), primaryStopped);
                assertEquals("detached future blocks cannot hide the missing second window",
                        WINDOW, source.getBufferedAheadBytes());
                assertTrue("three or more detached blocks are not startup proof",
                        !source.isStartupBufferGateSatisfied());

                origin.releaseFailedRequest.countDown();
                awaitFullCache(source, origin, CAP,
                        "failed sibling must refill its bridge without another decoder read");
                assertTrue(source.isStartupBufferGateSatisfied());
                assertEquals("ten successful windows plus two failed bridge requests", 12,
                        origin.requestCount.get());
                assertEquals("only the failed bridge may be retried", 3,
                        origin.requestStarts.get(origin.failedFirstRange).get());
                for (Map.Entry<Long, AtomicInteger> entry : origin.requestStarts.entrySet()) {
                    if (entry.getKey() != origin.failedFirstRange) {
                        assertEquals("a valid future window must not be discarded and downloaded again",
                                1, entry.getValue().get());
                    }
                }
                assertTrue("HTTP errors must back off outside the monitor before retrying",
                        origin.failedRangeRetriedAtNanos.get() - origin.failureSentAtNanos.get()
                                >= TimeUnit.MILLISECONDS.toNanos(200L));
                assertTrue("the primary that inherits a failed bridge must also back off",
                        origin.failedRangeSucceededAtNanos.get()
                                - origin.secondFailureSentAtNanos.get()
                                >= TimeUnit.MILLISECONDS.toNanos(200L));
                assertBudget(source);
                origin.assertNoFailure();
            } finally {
                origin.releaseFailedRequest.countDown();
                source.close();
                fills.shutdownNow();
            }
        }
    }

    @Test
    public void pausedSourceFillsTenWindowsAndRollingConsumptionRefillsWithoutDuplicateRanges()
            throws Exception {
        try (RangeOrigin origin = new RangeOrigin()) {
            ExecutorService fills = Executors.newFixedThreadPool(2);
            ExecutorService reads = Executors.newSingleThreadExecutor();
            HttpRangeMediaDataSource source = createSource(origin, fills);
            try {
                source.onExtractorSeekStarted(FIRST);
                awaitFullCache(source, origin, CAP,
                        "paused source must fill ten windows without any decoder read");
                assertEquals(CAP, source.getBufferedAheadBytes());
                assertTrue(source.isStartupBufferGateSatisfied());
                assertEquals(10, origin.requestCount.get());
                long backBuffer = staticLong("STREAMING_BACK_BUFFER_SIZE");
                assertTrue("test cap must leave a runway beyond the actual back buffer",
                        CAP - backBuffer >= MIB);
                byte[] actual = new byte[WINDOW];
                for (int block = 0; block < 36; block++) {
                    final long position = FIRST + (long) block * WINDOW;
                    Future<Integer> read = reads.submit(
                            () -> source.readAt(position, actual, 0, actual.length));
                    assertEquals("decoder read must finish at block " + block,
                            WINDOW, (int) read.get(5L, TimeUnit.SECONDS));
                    assertPayload(position, actual);
                    // Completed blocks beyond the back-buffer are retired, then the next
                    // contiguous HTTP window fills the resulting slot. Do not require the
                    // already-read part of the first retained window to count as forward data.
                    long minimumAhead = Math.max(MIB, CAP - backBuffer - WINDOW);
                    awaitFullCache(source, origin, minimumAhead,
                            "forward cache must refill after consuming block " + block);
                    assertTrue("startup proof must remain latched during ordinary reads",
                            source.isStartupBufferGateSatisfied());
                    assertBudget(source);
                }
                assertTrue("test must exercise several complete ten-window rotations",
                        origin.requestCount.get() >= 35);
                origin.assertNoDuplicateRanges();
                origin.assertAllRequestsAtOrAfter(FIRST);
                origin.assertNoFailure();
            } finally {
                source.close();
                reads.shutdownNow();
                fills.shutdownNow();
            }
        }
    }

    @Test
    public void smallDecoderReadsCrossReal192MiBCacheWhileReplacementHttpBodiesAreHeld()
            throws Exception {
        final int windowBytes = (int) (24L * MIB);
        final long capacityBytes = 8L * windowBytes;
        try (RangeOrigin origin = new RangeOrigin(windowBytes)) {
            origin.holdFromRange = FIRST + capacityBytes;
            ExecutorService fills = Executors.newFixedThreadPool(2);
            ExecutorService reads = Executors.newSingleThreadExecutor();
            HttpRangeMediaDataSource source = createSource(origin, fills, windowBytes,
                    4L * windowBytes, capacityBytes);
            // This test verifies real 192 MiB allocation, replacement and bytes. Give its
            // ownership ledger a deterministic roomy heap; separate process-budget tests
            // exercise pressure, including arrays left for GC by earlier tests in this JVM.
            RawRangeCacheBudget budget = new RawRangeCacheBudget(capacityBytes, 96L * MIB,
                    new RawRangeCacheBudget.HeapState() {
                        @Override public long maxBytes() { return 1024L * MIB; }
                        @Override public long usedBytes() { return 32L * MIB; }
                    });
            setField(source, "rawBudget", budget.openAccount());
            try {
                source.onExtractorSeekStarted(FIRST);
                awaitFullCache(source, origin, capacityBytes, capacityBytes,
                        "the paused source must commit all eight real 24 MiB windows");
                assertEquals(8, origin.requestCount.get());
                assertEquals("the process ledger must account every real committed window",
                        capacityBytes, budget.chargedBytes());

                // A one-byte offset makes 16 KiB decoder reads straddle every 24 MiB window
                // boundary. Replacement HTTP responses remain held until every cache-hit read
                // finishes: taking a network lock on a resident read would deadlock this task.
                Future<Long> consumed = reads.submit(() -> {
                    byte[] actual = new byte[16 * 1024];
                    long cursor = FIRST + 1L;
                    long end = cursor + 4L * windowBytes;
                    int readCount = 0;
                    while (cursor < end) {
                        assertEquals(actual.length,
                                source.readAt(cursor, actual, 0, actual.length));
                        assertPayload(cursor, actual);
                        cursor += actual.length;
                        if (++readCount % 64 == 0) {
                            assertBudget(source, capacityBytes);
                        }
                    }
                    return cursor;
                });
                assertEquals(FIRST + 1L + 4L * windowBytes,
                        (long) consumed.get(15L, TimeUnit.SECONDS));
                assertTrue("consumed windows must start replacement HTTP requests",
                        origin.heldRequestOpened.await(5L, TimeUnit.SECONDS));
                assertEquals("resident reads finished before any replacement body was released",
                        1L, origin.releaseHeldRequests.getCount());
                assertTrue("ordinary small reads must retain their verified startup gate",
                        source.isStartupBufferGateSatisfied());
                synchronized (source) {
                    Map<?, ?> windows = (Map<?, ?>) objectField(source, "cachedWindows");
                    assertTrue("the consumed first window must be retired", !windows.containsKey(FIRST));
                    assertTrue("replacement bytes remain reserved while the HTTP bodies wait",
                            longField(source, "reservedPrefetchBytes") > 0L);
                }
                assertBudget(source, capacityBytes);

                origin.releaseHeldRequests.countDown();
                long backBuffer = staticLong("STREAMING_BACK_BUFFER_SIZE");
                awaitFullCache(source, origin, capacityBytes - backBuffer - windowBytes,
                        capacityBytes, "releasing HTTP bodies must refill the rolled cache");
                origin.assertNoDuplicateRanges();
                origin.assertNoFailure();
            } finally {
                origin.releaseHeldRequests.countDown();
                source.close();
                reads.shutdownNow();
                fills.shutdownNow();
            }
        }
    }

    @Test
    public void nonzeroInitialForwardAndBackwardSeeksFillTheirOwnFourToTenWindowRunways()
            throws Exception {
        try (RangeOrigin origin = new RangeOrigin()) {
            ExecutorService fills = Executors.newFixedThreadPool(2);
            ExecutorService reads = Executors.newSingleThreadExecutor();
            HttpRangeMediaDataSource source = createSource(origin, fills);
            try {
                long[] positions = {FIRST, FIRST + 256L * MIB, FIRST + 128L * MIB};
                int priorRequests = 0;
                for (long position : positions) {
                    source.onExtractorSeekStarted(position);
                    awaitFullCache(source, origin, CAP,
                            "seek must fill from the resolved byte without another decoder read: "
                                    + position);
                    assertEquals("seek anchor must preserve a long offset above 200 GiB", position,
                            source.getPlaybackAnchorPositionSnapshot());
                    assertEquals("each resolved position needs its own contiguous ten windows", CAP,
                            source.getBufferedAheadBytes());
                    assertEquals(START, source.getPlaybackStartBufferTargetBytes());
                    assertTrue("four-window startup proof must be satisfied at the new position",
                            source.isStartupBufferGateSatisfied());
                    assertEquals("old windows must not substitute for the new seek episode",
                            priorRequests + 10, origin.requestCount.get());
                    assertBudget(source);
                    byte[] actual = new byte[16 * 1024];
                    Future<Integer> read = reads.submit(
                            () -> source.readAt(position, actual, 0, actual.length));
                    assertEquals(actual.length, (int) read.get(5L, TimeUnit.SECONDS));
                    assertPayload(position, actual);
                    priorRequests = origin.requestCount.get();
                }
                origin.assertNoDuplicateRanges();
                origin.assertAllRequestsAtOrAfter(FIRST);
                origin.assertNoFailure();
            } finally {
                source.close();
                reads.shutdownNow();
                fills.shutdownNow();
            }
        }
    }

    private static HttpRangeMediaDataSource createSource(RangeOrigin origin,
                                                         ExecutorService fills) throws Exception {
        return createSource(origin, fills, WINDOW, START, CAP);
    }

    private static HttpRangeMediaDataSource createSource(RangeOrigin origin,
                                                         ExecutorService fills,
                                                         long windowBytes,
                                                         long startBytes,
                                                         long capacityBytes) throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                origin.url(), null, null, true, null);
        setField(source, "prefetchExecutor", fills);
        setLong(source, "defaultWindowSize", windowBytes);
        setLong(source, "maxWindowSize", windowBytes);
        setLong(source, "prefetchWindowSize", windowBytes);
        setLong(source, "startBufferBytes", startBytes);
        setLong(source, "cacheLimitBytes", capacityBytes);
        source.primeKnownTotalSize(TOTAL);
        return source;
    }

    private static void awaitFullCache(HttpRangeMediaDataSource source, RangeOrigin origin,
                                       long requiredAhead, String message) throws Exception {
        awaitFullCache(source, origin, requiredAhead, CAP, message);
    }

    private static void awaitFullCache(HttpRangeMediaDataSource source, RangeOrigin origin,
                                       long requiredAhead, long capacityBytes, String message)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8L);
        while (System.nanoTime() < deadline) {
            origin.assertNoFailure();
            assertBudget(source, capacityBytes);
            synchronized (source) {
                if (longField(source, "cachedBytes") == capacityBytes
                        && longField(source, "reservedPrefetchBytes") == 0L
                        && source.getBufferedAheadBytes() >= requiredAhead) {
                    return;
                }
            }
            Thread.sleep(5L);
        }
        throw new AssertionError(message + "; " + source.getCacheDiagnosticsSnapshot()
                + "; ahead=" + source.getBufferedAheadBytes()
                + "; requests=" + origin.requestCount.get());
    }

    private static void assertBudget(HttpRangeMediaDataSource source) throws Exception {
        assertBudget(source, CAP);
    }

    private static void assertBudget(HttpRangeMediaDataSource source, long capacityBytes)
            throws Exception {
        synchronized (source) {
            long cached = longField(source, "cachedBytes");
            long reserved = longField(source, "reservedPrefetchBytes");
            long foreground = longField(source, "foregroundReservationBytes");
            assertTrue("cache/reservations must never become negative", cached >= 0L
                    && reserved >= 0L && foreground >= 0L);
            assertTrue("committed plus active responses exceed the cache budget: "
                    + cached + "+" + reserved + "+" + foreground,
                    cached + reserved + foreground <= capacityBytes);
        }
    }

    private static byte payloadByte(long position) {
        return (byte) (position * 31L + (position >>> 32) * 17L);
    }

    private static void assertPayload(long position, byte[] actual) {
        for (int index = 0; index < actual.length; index++) {
            if (actual[index] != payloadByte(position + index)) {
                throw new AssertionError("wrong media byte at long offset " + (position + index));
            }
        }
    }

    private static long longField(Object object, String name) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(object);
    }

    private static boolean booleanField(Object object, String name) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(object);
    }

    private static Object objectField(Object object, String name) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static long staticLong(String name) throws Exception {
        return longField(null, name);
    }

    private static void setLong(Object object, String name, long value) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(object, value);
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static final class RangeOrigin implements Closeable {
        final AtomicInteger requestCount = new AtomicInteger();
        volatile long failedFirstRange = -1L;
        volatile long holdFromRange = -1L;
        final CountDownLatch heldRequestOpened = new CountDownLatch(1);
        final CountDownLatch releaseHeldRequests = new CountDownLatch(1);
        final CountDownLatch failedRequestOpened = new CountDownLatch(1);
        final CountDownLatch releaseFailedRequest = new CountDownLatch(1);
        final AtomicLong failureSentAtNanos = new AtomicLong();
        final AtomicLong failedRangeRetriedAtNanos = new AtomicLong();
        final AtomicLong secondFailureSentAtNanos = new AtomicLong();
        final AtomicLong failedRangeSucceededAtNanos = new AtomicLong();
        private final ConcurrentHashMap<Long, AtomicInteger> requestStarts = new ConcurrentHashMap<>();
        private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ServerSocket server;
        private final int windowBytes;
        private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
        private final ExecutorService handlers = Executors.newFixedThreadPool(3);
        private volatile boolean closed;

        RangeOrigin() throws IOException {
            this(WINDOW);
        }

        RangeOrigin(int windowBytes) throws IOException {
            this.windowBytes = windowBytes;
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            acceptor.submit(() -> {
                try {
                    while (!closed) {
                        Socket socket = server.accept();
                        activeSockets.add(socket);
                        handlers.submit(() -> serve(socket));
                    }
                } catch (IOException error) {
                    if (!closed) failure.compareAndSet(null, error);
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/large-movie.mkv";
        }

        private void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(3_000);
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.US_ASCII));
                String range = null;
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                        range = line.substring(6).trim();
                    }
                }
                if (range == null || !range.startsWith("bytes=")) {
                    throw new AssertionError("expected a physical byte Range, got " + range);
                }
                String[] bounds = range.substring(6).split("-", 2);
                long start = Long.parseLong(bounds[0]);
                long end = Long.parseLong(bounds[1]);
                if (start < 200L * GIB || end >= TOTAL || end - start + 1L != windowBytes) {
                    throw new AssertionError("wrong long/window coordinates: " + range);
                }
                int rangeAttempt = requestStarts.computeIfAbsent(start,
                        ignored -> new AtomicInteger()).incrementAndGet();
                requestCount.incrementAndGet();
                OutputStream output = connection.getOutputStream();
                if (holdFromRange >= 0L && start >= holdFromRange) {
                    heldRequestOpened.countDown();
                    if (!releaseHeldRequests.await(25L, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release replacement HTTP responses");
                    }
                }
                if (start == failedFirstRange && rangeAttempt == 1) {
                    failedRequestOpened.countDown();
                    if (!releaseFailedRequest.await(10L, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the held sibling request");
                    }
                    failureSentAtNanos.set(System.nanoTime());
                    output.write(("HTTP/1.1 503 Service Unavailable\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    return;
                }
                if (start == failedFirstRange) {
                    if (rangeAttempt == 2) {
                        failedRangeRetriedAtNanos.set(System.nanoTime());
                        secondFailureSentAtNanos.set(System.nanoTime());
                        output.write(("HTTP/1.1 503 Service Unavailable\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        return;
                    }
                    failedRangeSucceededAtNanos.set(System.nanoTime());
                }
                String response = "HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes " + start + "-" + end + "/" + TOTAL + "\r\n"
                        + "Content-Length: " + windowBytes + "\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                byte[] data = new byte[64 * 1024];
                for (long position = start; position <= end; position += data.length) {
                    int count = (int) Math.min(data.length, end - position + 1L);
                    for (int index = 0; index < count; index++) {
                        data[index] = payloadByte(position + index);
                    }
                    output.write(data, 0, count);
                }
                output.flush();
            } catch (Throwable error) {
                if (!closed) failure.compareAndSet(null, error);
            } finally {
                activeSockets.remove(socket);
            }
        }

        void assertNoDuplicateRanges() {
            for (Map.Entry<Long, AtomicInteger> entry : requestStarts.entrySet()) {
                assertEquals("cached media was downloaded repeatedly at " + entry.getKey(),
                        1, entry.getValue().get());
            }
        }

        void assertAllRequestsAtOrAfter(long minimum) {
            for (Long start : requestStarts.keySet()) {
                assertTrue("a resumed source requested stale bytes at " + start, start >= minimum);
            }
        }

        void assertNoFailure() {
            Throwable error = failure.get();
            if (error != null) throw new AssertionError("HTTP origin failure", error);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
            for (Socket socket : activeSockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            acceptor.shutdownNow();
            handlers.shutdownNow();
        }
    }
}
