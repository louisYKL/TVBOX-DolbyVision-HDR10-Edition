package xyz.doikki.videoplayer.player;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpRangeMediaDataSourceTest {
    private static final int ADVERTISED_LENGTH = 512 * 1024;

    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;

    @Before
    public void setUp() throws IOException {
        serverSocket = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        serverSocket.setSoTimeout(5_000);
        serverExecutor = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    public void transientEmptyRangeIsReopenedInsteadOfReportedAsEof() throws Exception {
        byte[] expected = new byte[]{0x11, 0x22, 0x33, 0x44};
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveRangeResponse(requestCount, new byte[0]);
                serveRangeResponse(requestCount, expected);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = new HttpRangeMediaDataSource(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] actual = new byte[expected.length];
        try {
            assertEquals(expected.length, source.readAt(0L, actual, 0, actual.length));
        } finally {
            source.close();
        }
        server.get(5, TimeUnit.SECONDS);

        assertArrayEquals(expected, actual);
        assertEquals(2, requestCount.get());
    }

    @Test
    public void partialRangeWithoutEntityTotalDoesNotBecomeFalseEof() throws Exception {
        byte[] expected = new byte[]{0x21, 0x22, 0x23, 0x24};
        Future<?> server = serverExecutor.submit(() -> {
            try (Socket socket = serverSocket.accept()) {
                assertTrue(readRangeHeader(socket).startsWith("bytes=0-"));
                OutputStream output = socket.getOutputStream();
                String headers = "HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes 0-3/*\r\n"
                        + "Content-Length: 4\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                output.write(expected);
                output.flush();
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = new HttpRangeMediaDataSource(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] actual = new byte[expected.length];
        try {
            assertEquals(expected.length, source.readAt(0L, actual, 0, actual.length));
            assertEquals("a partial 206 body is not the complete entity", -1L,
                    source.getKnownTotalSize());
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void malformedInitialRangeResetIsRetriedAtTheRequestedOffset() throws Exception {
        long offset = 1024L;
        byte[] expected = new byte[]{0x55, 0x66, 0x77, 0x21};
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                // The local spider sometimes resets a non-zero read to byte zero on the first
                // connection, with a 200 response and a large, incorrect Content-Length.
                serveResetRangeResponse(requestCount, offset);
                serveExactRangeResponse(requestCount, offset, expected);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = new HttpRangeMediaDataSource(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] actual = new byte[expected.length];
        try {
            assertEquals(expected.length, source.readAt(offset, actual, 0, actual.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertArrayEquals(expected, actual);
        assertEquals(2, requestCount.get());
    }

    @Test
    public void shortRangeEntityIsRetriedBeforeReturningAWindow() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveShortThenFullRangeResponse(requestCount);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = new HttpRangeMediaDataSource(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] actual = new byte[ADVERTISED_LENGTH];
        try {
            assertEquals(ADVERTISED_LENGTH,
                    source.readAt(0L, actual, 0, actual.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertEquals(2, requestCount.get());
        assertEquals(0x31, actual[0]);
        assertEquals(0x32, actual[1]);
        assertEquals(0x33, actual[2]);
        assertEquals(0x34, actual[3]);
    }

    @Test
    public void systemStreamingRetriesAnOrdinaryShortBodyWithoutCommittingItsPrefix() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveShortThenFullRangeResponse(requestCount);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] actual = new byte[ADVERTISED_LENGTH];
        try {
            assertEquals(actual.length, source.readAt(0L, actual, 0, actual.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertEquals(2, requestCount.get());
        assertEquals(0x31, actual[0]);
        assertEquals(0x32, actual[1]);
    }

    @Test
    public void unknownLengthLoopback416AtNonZeroOffsetIsRetriedNotTreatedAsEof() {
        assertFalse(HttpRangeMediaDataSource.shouldTreatUnknown416AsEof(
                "http://127.0.0.1:6677/proxy/play/disk/movie.mkv", 8_388_608L, 1_048_576L));
        assertFalse(HttpRangeMediaDataSource.shouldTreatUnknown416AsEof(
                "http://localhost:6677/proxy/play/disk/movie.mkv", 8_388_608L, 1_048_576L));
        assertTrue(HttpRangeMediaDataSource.shouldTreatUnknown416AsEof(
                "https://media.example.com/video.mkv", 8_388_608L, 1_048_576L));
        assertFalse(HttpRangeMediaDataSource.shouldTreatUnknown416AsEof(
                "http://127.0.0.1:6677/proxy/play/disk/movie.mkv", 0L, 0L));
    }

    @Test
    public void trimRetainsCurrentAndAuxiliaryTailWindowsWithinCacheLimit() throws Exception {
        HttpRangeMediaDataSource source = new HttpRangeMediaDataSource(
                "https://media.example/video.mkv", null);
        Field cacheLimit = HttpRangeMediaDataSource.class.getDeclaredField("cacheLimitBytes");
        cacheLimit.setAccessible(true);
        cacheLimit.setLong(source, 5L * 1024L);

        Field cacheField = HttpRangeMediaDataSource.class.getDeclaredField("cachedWindows");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        NavigableMap<Long, Object> cache =
                (NavigableMap<Long, Object>) cacheField.get(source);

        Class<?> windowClass = Class.forName(
                "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$WindowData");
        Constructor<?> windowConstructor = windowClass.getDeclaredConstructor(
                long.class, long.class, byte[].class, long.class,
                long.class, long.class, long.class, int.class);
        windowConstructor.setAccessible(true);
        long totalSize = 20_000L;
        long bytesCached = 0L;
        for (int index = 0; index < 5; index++) {
            long start = index * 1_024L;
            Object window = windowConstructor.newInstance(start, start + 1_023L,
                    new byte[1_024], totalSize, start, start + 1_023L,
                    start + 1_023L, 206);
            cache.put(start, window);
            bytesCached += 1_024L;
        }
        long tailStart = 10_000L;
        Object tailWindow = windowConstructor.newInstance(tailStart, tailStart + 99L,
                new byte[100], totalSize, tailStart, tailStart + 99L,
                tailStart + 99L, 206);
        cache.put(tailStart, tailWindow);
        bytesCached += 100L;

        Field cachedBytes = HttpRangeMediaDataSource.class.getDeclaredField("cachedBytes");
        cachedBytes.setAccessible(true);
        cachedBytes.setLong(source, bytesCached);
        Field totalSizeField = HttpRangeMediaDataSource.class.getDeclaredField("totalSize");
        totalSizeField.setAccessible(true);
        totalSizeField.setLong(source, totalSize);
        Field anchor = HttpRangeMediaDataSource.class.getDeclaredField("playbackAnchorPosition");
        anchor.setAccessible(true);
        anchor.setLong(source, 0L);
        Field auxiliaryPosition = HttpRangeMediaDataSource.class
                .getDeclaredField("auxiliaryProbePosition");
        auxiliaryPosition.setAccessible(true);
        auxiliaryPosition.setLong(source, tailStart);

        java.lang.reflect.Method trim = HttpRangeMediaDataSource.class
                .getDeclaredMethod("trimCacheLocked");
        trim.setAccessible(true);
        synchronized (source) {
            trim.invoke(source);
        }

        assertTrue("keep the active first playback window", cache.containsKey(0L));
        assertTrue("keep the active auxiliary Cue window", cache.containsKey(tailStart));
        assertFalse("evict the forward window before the auxiliary tail", cache.containsKey(4_096L));
        assertTrue("trim must respect the configured cache cap",
                cachedBytes.getLong(source) <= cacheLimit.getLong(source));
        source.close();
    }

    @Test
    public void bridgeAdmissionEvictsDetachedFutureWindowBeforeRejectingContiguousFill() throws Exception {
        HttpRangeMediaDataSource source = new HttpRangeMediaDataSource(
                "https://media.example/large.mkv", null);
        Field cacheLimit = HttpRangeMediaDataSource.class.getDeclaredField("cacheLimitBytes");
        cacheLimit.setAccessible(true);
        cacheLimit.setLong(source, 5L * 1024L);

        Field cacheField = HttpRangeMediaDataSource.class.getDeclaredField("cachedWindows");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        NavigableMap<Long, Object> cache =
                (NavigableMap<Long, Object>) cacheField.get(source);
        Class<?> windowClass = Class.forName(
                "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$WindowData");
        Constructor<?> windowConstructor = windowClass.getDeclaredConstructor(
                long.class, long.class, byte[].class, long.class,
                long.class, long.class, long.class, int.class);
        windowConstructor.setAccessible(true);

        long totalSize = 100_000L;
        long cachedBytesValue = 0L;
        // The active prefix is 0..1023. The 1024..2047 bridge is missing while detached
        // future windows and an auxiliary tail consume the cache budget.
        for (int index : new int[]{0, 2, 3, 4}) {
            long start = index * 1_024L;
            Object window = windowConstructor.newInstance(start, start + 1_023L,
                    new byte[1_024], totalSize, start, start + 1_023L,
                    start + 1_023L, 206);
            cache.put(start, window);
            cachedBytesValue += 1_024L;
        }
        long tailStart = 90_000L;
        Object tail = windowConstructor.newInstance(tailStart, tailStart + 99L,
                new byte[100], totalSize, tailStart, tailStart + 99L,
                tailStart + 99L, 206);
        cache.put(tailStart, tail);
        cachedBytesValue += 100L;

        Field cachedBytes = HttpRangeMediaDataSource.class.getDeclaredField("cachedBytes");
        cachedBytes.setAccessible(true);
        cachedBytes.setLong(source, cachedBytesValue);
        Field totalSizeField = HttpRangeMediaDataSource.class.getDeclaredField("totalSize");
        totalSizeField.setAccessible(true);
        totalSizeField.setLong(source, totalSize);
        Field anchor = HttpRangeMediaDataSource.class.getDeclaredField("playbackAnchorPosition");
        anchor.setAccessible(true);
        anchor.setLong(source, 0L);
        Field auxiliaryPosition = HttpRangeMediaDataSource.class
                .getDeclaredField("auxiliaryProbePosition");
        auxiliaryPosition.setAccessible(true);
        auxiliaryPosition.setLong(source, tailStart);

        java.lang.reflect.Method admission = HttpRangeMediaDataSource.class
                .getDeclaredMethod("makeCapacityForNextWindowLocked", long.class, long.class);
        admission.setAccessible(true);
        boolean admitted;
        synchronized (source) {
            admitted = (Boolean) admission.invoke(source, 0L, 1_024L);
        }

        assertTrue("bridge window must be admitted after evicting detached future data", admitted);
        assertTrue("active prefix must remain resident", cache.containsKey(0L));
        assertTrue("auxiliary tail must remain resident", cache.containsKey(tailStart));
        assertFalse("farthest detached future window should be evicted", cache.containsKey(4_096L));
        assertTrue("cache must fit the next window", cachedBytes.getLong(source)
                <= cacheLimit.getLong(source) - 1_024L);
        source.close();
    }

    @Test
    public void startupPrefetchUsesHttpToFillBridgeAfterFragmentedCache() throws Exception {
        final AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                servePrefetchRanges(requestCount, 100_000L, 8);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        Class<HttpRangeMediaDataSource> sourceClass = HttpRangeMediaDataSource.class;
        Constructor<HttpRangeMediaDataSource> sourceConstructor = sourceClass
                .getDeclaredConstructor(String.class, java.util.Map.class,
                        long.class, long.class, boolean.class, Boolean.class,
                        boolean.class, HttpRangeMediaDataSource.RangeTransferListener.class,
                        ReentrantLock.class);
        sourceConstructor.setAccessible(true);
        HttpRangeMediaDataSource source = sourceConstructor.newInstance(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video",
                null, 1_024L, 1_024L, true, Boolean.TRUE, true, null, null);

        Field cacheLimit = sourceClass.getDeclaredField("cacheLimitBytes");
        cacheLimit.setAccessible(true);
        cacheLimit.setLong(source, 5L * 1_024L);
        Field prefetchWindow = sourceClass.getDeclaredField("prefetchWindowSize");
        prefetchWindow.setAccessible(true);
        prefetchWindow.setLong(source, 1_024L);
        Field cacheField = sourceClass.getDeclaredField("cachedWindows");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        NavigableMap<Long, Object> cache = (NavigableMap<Long, Object>) cacheField.get(source);
        Class<?> windowClass = Class.forName(
                "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$WindowData");
        Constructor<?> windowConstructor = windowClass.getDeclaredConstructor(
                long.class, long.class, byte[].class, long.class,
                long.class, long.class, long.class, int.class);
        windowConstructor.setAccessible(true);
        long totalSize = 100_000L;
        long cachedBytesValue = 0L;
        for (int index : new int[]{0, 2, 3, 4}) {
            long start = index * 1_024L;
            Object window = windowConstructor.newInstance(start, start + 1_023L,
                    new byte[1_024], totalSize, start, start + 1_023L,
                    start + 1_023L, 206);
            cache.put(start, window);
            cachedBytesValue += 1_024L;
        }
        long tailStart = 90_000L;
        Object tail = windowConstructor.newInstance(tailStart, tailStart + 99L,
                new byte[100], totalSize, tailStart, tailStart + 99L,
                tailStart + 99L, 206);
        cache.put(tailStart, tail);
        cachedBytesValue += 100L;
        Field cachedBytes = sourceClass.getDeclaredField("cachedBytes");
        cachedBytes.setAccessible(true);
        cachedBytes.setLong(source, cachedBytesValue);
        Field totalSizeField = sourceClass.getDeclaredField("totalSize");
        totalSizeField.setAccessible(true);
        totalSizeField.setLong(source, totalSize);
        Field anchor = sourceClass.getDeclaredField("playbackAnchorPosition");
        anchor.setAccessible(true);
        anchor.setLong(source, 0L);
        Field auxiliaryPosition = sourceClass.getDeclaredField("auxiliaryProbePosition");
        auxiliaryPosition.setAccessible(true);
        auxiliaryPosition.setLong(source, tailStart);
        setBooleanField(source, "hasLoadedWindow", true);

        java.lang.reflect.Method schedule = sourceClass.getDeclaredMethod(
                "scheduleFillAheadLocked", long.class);
        schedule.setAccessible(true);
        synchronized (source) {
            schedule.invoke(source, 0L);
        }

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (System.nanoTime() < deadline) {
                synchronized (source) {
                    if (cache.containsKey(1_024L)
                            && source.getBufferedAheadBytes() >= 2_048L) {
                        break;
                    }
                }
                Thread.sleep(20L);
            }
            assertTrue("the real HTTP prefetch must fill the missing bridge window",
                    cache.containsKey(1_024L));
            assertTrue("buffered-ahead snapshot must include the contiguous bridge",
                    source.getBufferedAheadBytes() >= 2_048L);
            assertTrue("prefetch must have issued a Range request", requestCount.get() > 0);
            assertTrue("resident cache must remain bounded",
                    cachedBytes.getLong(source) <= cacheLimit.getLong(source));
        } finally {
            source.close();
            server.cancel(true);
        }
    }

    @Test
    public void startupGateRequiresFourContiguousTwentyFourMiBWindows() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        try {
            long windowBytes = 24L * 1024L * 1024L;
            setLongField(source, "cacheLimitBytes",
                    PlaybackPrebufferPolicy.resolveCacheLimitBytes(192L * 1024L * 1024L, true));
            setLongField(source, "startBufferBytes",
                    PlaybackPrebufferPolicy.resolveStartBufferBytes(192L * 1024L * 1024L));
            installContiguousWindows(source, 3, windowBytes, 512L * 1024L * 1024L);
            assertFalse("three 24 MiB windows must not satisfy the 96 MiB gate",
                    source.isStartupBufferGateSatisfied());

            installContiguousWindows(source, 4, windowBytes, 512L * 1024L * 1024L);
            assertTrue("four contiguous 24 MiB windows satisfy the 96 MiB gate",
                    source.isStartupBufferGateSatisfied());
            assertEquals(96L * 1024L * 1024L, source.getPlaybackStartBufferTargetBytes());
            assertTrue(source.getBufferedAheadBytes() >= 96L * 1024L * 1024L);
            assertTrue(source.getCachedBytesSnapshotForDiagnostics()
                    <= 120L * 1024L * 1024L);
        } finally {
            source.close();
        }
    }

    @Test
    public void ordinaryHttpHasNoLoopbackShortfallToleranceAtTheNinetySixMiBBoundary()
            throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        try {
            long mb = 1024L * 1024L;
            setLongField(source, "cacheLimitBytes",
                    PlaybackPrebufferPolicy.resolveCacheLimitBytes(512L * mb, false));
            setLongField(source, "startBufferBytes", PlaybackPrebufferPolicy.resolveStartBufferBytes(512L * mb));
            installSmallWindows(source, new long[][]{{0L, 96L * mb - 33L}}, 512L * mb);
            source.onExtractorSeekStarted(0L);
            assertEquals("ordinary HTTP must retain the exact 96 MiB requirement",
                    96L * mb, source.getPlaybackStartBufferTargetBytes());
            assertFalse("the loopback-only 32-byte allowance must not release an ordinary origin",
                    source.isStartupBufferGateSatisfied());
        } finally {
            source.close();
        }
    }

    @Test
    public void largeHeapCapacityCountsAllEightWindowsIncludingPendingRequests() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        java.util.List<Object> pending = new java.util.ArrayList<>();
        long mb = 1024L * 1024L;
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * mb, 96L * mb,
                new RawRangeCacheBudget.HeapState() {
                    @Override public long maxBytes() { return 512L * 1024L * 1024L; }
                    @Override public long usedBytes() { return 32L * 1024L * 1024L; }
                });
        Field account = HttpRangeMediaDataSource.class.getDeclaredField("rawBudget");
        account.setAccessible(true);
        account.set(source, budget.openAccount());
        Method reserve = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "reservePrefetchLocked", long.class, long.class);
        reserve.setAccessible(true);
        Method foreground = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "reserveForegroundRawBytesLocked", long.class);
        foreground.setAccessible(true);
        try {
            long window = 24L * mb;
            long limit = PlaybackPrebufferPolicy.resolveCacheLimitBytes(512L * mb, true);
            setLongField(source, "cacheLimitBytes", limit);
            Method admission = HttpRangeMediaDataSource.class.getDeclaredMethod(
                    "makeCapacityForNextWindowLocked", long.class, long.class);
            admission.setAccessible(true);
            synchronized (source) {
                for (int i = 0; i < 7; i++) {
                    Object reservation = reserve.invoke(source, (long) i, window);
                    assertTrue("each pending worker needs a real process-budget token",
                            reservation != null);
                    pending.add(reservation);
                }
                assertTrue("the eighth 24 MiB window fits inside 192 MiB",
                        (Boolean) admission.invoke(source, 0L, window));
                foreground.invoke(source, window);
                assertFalse("seven pending plus one foreground window leave no room for a ninth",
                        (Boolean) admission.invoke(source, 0L, window));
                assertEquals(192L * mb, limit);
                assertEquals("in-flight ownership is shared with the process budget",
                        limit, budget.chargedBytes());
            }
        } finally {
            synchronized (source) {
                // No foreground worker was launched by this admission-only test.
                setLongField(source, "foregroundReservationBytes", 0L);
                for (Object reservation : pending) {
                    Method release = HttpRangeMediaDataSource.class.getDeclaredMethod(
                            "releasePrefetchReservationLocked", reservation.getClass());
                    release.setAccessible(true);
                    release.invoke(source, reservation);
                }
            }
            source.close();
            assertEquals(0L, budget.chargedBytes());
        }
    }

    @Test
    public void initialBackgroundFillAdvancesWithoutAnyForegroundRead() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        long totalSize = 3L * 1_024L;
        Future<?> server = serverExecutor.submit(() -> {
            try {
                // A pure background startup has never applied a foreground window. It must
                // still advance after the first response instead of repeatedly requesting zero.
                serveExactRangeResponse(requestCount, 0L, new byte[1_024], totalSize);
                serveExactRangeResponse(requestCount, 1_024L, new byte[1_024], totalSize);
                serveExactRangeResponse(requestCount, 2_048L, new byte[1_024], totalSize);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback(
                        "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/play/video",
                        null, null, true, null);
        try {
            setLongField(source, "prefetchWindowSize", 1_024L);
            setLongField(source, "cacheLimitBytes", 4L * 1_024L);
            source.requestPlaybackPrebuffer();
            server.get(5L, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (source.getBufferedAheadBytes() < totalSize && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals("three sequential background windows must cover the whole short file",
                    totalSize, source.getBufferedAheadBytes());
            assertEquals("each pure-prefetch window must be downloaded exactly once",
                    3, requestCount.get());
            assertTrue(source.isStartupBufferGateSatisfied());
        } finally {
            source.close();
            server.cancel(true);
        }
    }

    @Test
    public void evictedWindowDoesNotRemainRetainedByTheSource() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        try {
            long anchor = 585_061_073L;
            installSmallWindows(source, new long[][]{{anchor, anchor + 9L}}, anchor + 1_000L);
            setLongField(source, "playbackAnchorPosition", anchor);
            Field windows = HttpRangeMediaDataSource.class.getDeclaredField("cachedWindows");
            windows.setAccessible(true);
            @SuppressWarnings("unchecked")
            NavigableMap<Long, Object> cache = (NavigableMap<Long, Object>) windows.get(source);
            Object window = cache.get(anchor);
            Field data = window.getClass().getDeclaredField("data");
            data.setAccessible(true);
            Object retiredBytes = data.get(window);
            Method apply = HttpRangeMediaDataSource.class.getDeclaredMethod(
                    "applyWindowLocked", window.getClass());
            apply.setAccessible(true);
            Method remove = HttpRangeMediaDataSource.class.getDeclaredMethod(
                    "removeWindowLocked", Long.class);
            remove.setAccessible(true);
            synchronized (source) {
                apply.invoke(source, window);
                remove.invoke(source, anchor);
                assertTrue("the removed response must leave the accounted cache", cache.isEmpty());
                for (Field field : HttpRangeMediaDataSource.class.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        assertFalse("an evicted byte array must not be retained by " + field.getName(),
                                field.get(source) == retiredBytes);
                    }
                }
                Field loaded = HttpRangeMediaDataSource.class.getDeclaredField("hasLoadedWindow");
                loaded.setAccessible(true);
                assertTrue("eviction must preserve loaded history instead of resetting prefetch to byte zero",
                        loaded.getBoolean(source));
                Method nextMissing = HttpRangeMediaDataSource.class.getDeclaredMethod(
                        "getNextMissingWindowStartLocked", long.class);
                nextMissing.setAccessible(true);
                assertEquals("a cache miss stays anchored at the actual resumed byte",
                        anchor, ((Long) nextMissing.invoke(source, anchor)).longValue());
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void admissionRetainsSiblingWindowsBehindAnInFlightBridge() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        Object reservation = null;
        try {
            isolateProcessBudget(source);
            installSmallWindows(source, new long[][]{
                    {1_024, 2_047}, {2_048, 3_071},
                    {3_072, 4_095}, {4_096, 5_119}}, 100_000L);
            setLongField(source, "cacheLimitBytes", 5L * 1_024L);
            setLongField(source, "playbackAnchorPosition", 0L);
            setLongField(source, "inFlightPrefetchStart", 0L);
            setLongField(source, "inFlightPrefetchEnd", 1_023L);
            reservation = reserveTestWorker(source, 1_024L);
            Method admission = HttpRangeMediaDataSource.class.getDeclaredMethod(
                    "makeCapacityForNextWindowLocked", long.class, long.class);
            admission.setAccessible(true);
            synchronized (source) {
                assertFalse("a full valid runway must not admit a sixth window by discarding a sibling",
                        (Boolean) admission.invoke(source, 0L, 1_024L));
                Field bytes = HttpRangeMediaDataSource.class.getDeclaredField("cachedBytes");
                bytes.setAccessible(true);
                assertEquals("every committed sibling must remain for the slow primary bridge",
                        4L * 1_024L, bytes.getLong(source));
                Method nextMissing = HttpRangeMediaDataSource.class.getDeclaredMethod(
                        "getNextMissingWindowStartLocked", long.class);
                nextMissing.setAccessible(true);
                assertEquals("the retained runway must still contain all five windows",
                        5L * 1_024L, ((Long) nextMissing.invoke(source, 0L)).longValue());
            }
        } finally {
            releaseTestWorker(source, reservation);
            source.close();
        }
    }

    @Test
    public void distantReservationDoesNotProtectDetachedFutureWindows() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        Object reservation = null;
        try {
            isolateProcessBudget(source);
            installSmallWindows(source, new long[][]{{0, 9}, {40, 49}}, 1_000L);
            setLongField(source, "cacheLimitBytes", 30L);
            setLongField(source, "playbackAnchorPosition", 0L);
            setLongField(source, "inFlightPrefetchStart", 100L);
            setLongField(source, "inFlightPrefetchEnd", 109L);
            reservation = reserveTestWorker(source, 10L);
            Method admission = HttpRangeMediaDataSource.class.getDeclaredMethod(
                    "makeCapacityForNextWindowLocked", long.class, long.class);
            admission.setAccessible(true);
            synchronized (source) {
                assertTrue("a distant metadata reservation cannot bridge the missing playback bytes",
                        (Boolean) admission.invoke(source, 0L, 10L));
                Field bytes = HttpRangeMediaDataSource.class.getDeclaredField("cachedBytes");
                bytes.setAccessible(true);
                assertEquals("only the detached future window should be evicted",
                        10L, bytes.getLong(source));
                Method nextMissing = HttpRangeMediaDataSource.class.getDeclaredMethod(
                        "getNextMissingWindowStartLocked", long.class);
                nextMissing.setAccessible(true);
                assertEquals("the active playback gap stays at byte ten",
                        10L, ((Long) nextMissing.invoke(source, 0L)).longValue());
            }
        } finally {
            releaseTestWorker(source, reservation);
            source.close();
        }
    }

    @Test
    public void reservedBridgeIsSkippedWhenFindingNextMissingWindow() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        try {
            installSmallWindows(source, new long[][]{{0, 9}, {20, 29}, {30, 39}}, 100);
            setLongField(source, "playbackAnchorPosition", 0L);
            setLongField(source, "inFlightPrefetchStart", 10L);
            setLongField(source, "inFlightPrefetchEnd", 19L);
            Method nextMissing = HttpRangeMediaDataSource.class
                    .getDeclaredMethod("getNextMissingWindowStartLocked", long.class);
            nextMissing.setAccessible(true);
            synchronized (source) {
                assertEquals("an in-flight bridge plus later committed windows must not be downloaded again",
                        40L,
                        ((Long) nextMissing.invoke(source, 0L)).longValue());
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void missingResumeWindowStartsAtTheExactByteInsteadOfRoundingBackwards() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        try {
            Method nextMissing = HttpRangeMediaDataSource.class
                    .getDeclaredMethod("getNextMissingWindowStartLocked", long.class);
            nextMissing.setAccessible(true);
            synchronized (source) {
                assertEquals(585_061_073L,
                        ((Long) nextMissing.invoke(source, 585_061_073L)).longValue());
                setLongField(source, "inFlightPrefetchStart", 585_061_073L);
                setLongField(source, "inFlightPrefetchEnd", 585_061_073L + 24L * 1024L * 1024L - 1L);
                assertEquals("an initial primary reservation must not be reopened by its sibling",
                        585_061_073L + 24L * 1024L * 1024L,
                        ((Long) nextMissing.invoke(source, 585_061_073L)).longValue());
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void progressCountsOnlyContiguousInFlightPrefix() throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.example/video.mkv",
                        null, null, false, null);
        try {
            installSmallWindows(source, new long[][]{{0, 9}, {20, 29}}, 100);
            setLongField(source, "playbackAnchorPosition", 0L);
            setLongField(source, "startupBufferAnchorPosition", 0L);
            setBooleanField(source, "startupBufferGateSatisfied", true);
            Class<?> inFlightClass = Class.forName(
                    "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$InFlightRange");
            Constructor<?> constructor = inFlightClass.getDeclaredConstructor(long.class);
            constructor.setAccessible(true);
            Object bridge = constructor.newInstance(10L);
            Field bytes = inFlightClass.getDeclaredField("bytes");
            bytes.setAccessible(true);
            ((AtomicLong) bytes.get(bridge)).set(10L);
            Field ranges = HttpRangeMediaDataSource.class.getDeclaredField("inFlightRanges");
            ranges.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Long, Object> map =
                    (ConcurrentHashMap<Long, Object>) ranges.get(source);
            map.put(7L, bridge);
            assertEquals("bridge bytes and the committed window after it are contiguous",
                    30L, source.getBufferedAheadBytesForProgress());
            ((AtomicLong) bytes.get(bridge)).set(5L);
            assertEquals("a short bridge must not count the later window past its hole",
                    15L, source.getBufferedAheadBytesForProgress());
        } finally {
            source.close();
        }
    }

    private static void isolateProcessBudget(HttpRangeMediaDataSource source) throws Exception {
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * 1024L * 1024L,
                96L * 1024L * 1024L, new RawRangeCacheBudget.HeapState() {
                    @Override public long maxBytes() { return 512L * 1024L * 1024L; }
                    @Override public long usedBytes() { return 32L * 1024L * 1024L; }
                });
        Field account = HttpRangeMediaDataSource.class.getDeclaredField("rawBudget");
        account.setAccessible(true);
        account.set(source, budget.openAccount());
    }

    private static Object reserveTestWorker(HttpRangeMediaDataSource source, long bytes)
            throws Exception {
        Method reserve = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "reservePrefetchLocked", long.class, long.class);
        reserve.setAccessible(true);
        synchronized (source) {
            Object reservation = reserve.invoke(source, 1L, bytes);
            assertTrue("a test worker must own an actual reservation", reservation != null);
            return reservation;
        }
    }

    private static void releaseTestWorker(HttpRangeMediaDataSource source, Object reservation)
            throws Exception {
        if (reservation == null) return;
        Method release = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "releasePrefetchReservationLocked", reservation.getClass());
        release.setAccessible(true);
        synchronized (source) {
            release.invoke(source, reservation);
        }
    }

    private static void installSmallWindows(HttpRangeMediaDataSource source,
                                             long[][] intervals,
                                             long totalSize) throws Exception {
        Field cacheField = HttpRangeMediaDataSource.class.getDeclaredField("cachedWindows");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        NavigableMap<Long, Object> cache = (NavigableMap<Long, Object>) cacheField.get(source);
        Class<?> windowClass = Class.forName(
                "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$WindowData");
        Constructor<?> constructor = windowClass.getDeclaredConstructor(
                long.class, long.class, byte[].class, long.class,
                long.class, long.class, long.class, int.class);
        constructor.setAccessible(true);
        long cached = 0L;
        synchronized (source) {
            cache.clear();
            for (long[] interval : intervals) {
                int length = (int) (interval[1] - interval[0] + 1L);
                Object window = constructor.newInstance(interval[0], interval[1],
                        new byte[length], totalSize, interval[0], interval[1], interval[1], 206);
                cache.put(interval[0], window);
                cached += length;
            }
            setLongField(source, "cachedBytes", cached);
            setLongField(source, "totalSize", totalSize);
        }
    }

    private static void setLongField(HttpRangeMediaDataSource source,
                                     String name,
                                     long value) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(source, value);
    }

    private static void setBooleanField(HttpRangeMediaDataSource source,
                                        String name,
                                        boolean value) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(source, value);
    }

    private static void installContiguousWindows(HttpRangeMediaDataSource source,
                                                  int count,
                                                  long windowBytes,
                                                  long totalSize) throws Exception {
        Field cacheField = HttpRangeMediaDataSource.class.getDeclaredField("cachedWindows");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        NavigableMap<Long, Object> cache = (NavigableMap<Long, Object>) cacheField.get(source);
        Class<?> windowClass = Class.forName(
                "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$WindowData");
        Constructor<?> constructor = windowClass.getDeclaredConstructor(
                long.class, long.class, byte[].class, long.class,
                long.class, long.class, long.class, int.class);
        constructor.setAccessible(true);
        long bytes = 0L;
        synchronized (source) {
            cache.clear();
            for (int index = 0; index < count; index++) {
                long start = index * windowBytes;
                long end = start + windowBytes - 1L;
                byte[] data = new byte[(int) windowBytes];
                Object window = constructor.newInstance(start, end, data, totalSize,
                        start, end, end, 206);
                cache.put(start, window);
                bytes += data.length;
            }
            Field cachedBytes = HttpRangeMediaDataSource.class.getDeclaredField("cachedBytes");
            cachedBytes.setAccessible(true);
            cachedBytes.setLong(source, bytes);
            Field total = HttpRangeMediaDataSource.class.getDeclaredField("totalSize");
            total.setAccessible(true);
            total.setLong(source, totalSize);
            Field anchor = HttpRangeMediaDataSource.class
                    .getDeclaredField("playbackAnchorPosition");
            anchor.setAccessible(true);
            anchor.setLong(source, 0L);
            Field startupAnchor = HttpRangeMediaDataSource.class
                    .getDeclaredField("startupBufferAnchorPosition");
            startupAnchor.setAccessible(true);
            startupAnchor.setLong(source, 0L);
            Field startupGate = HttpRangeMediaDataSource.class
                    .getDeclaredField("startupBufferGateSatisfied");
            startupGate.setAccessible(true);
            startupGate.setBoolean(source, false);
            Method refresh = HttpRangeMediaDataSource.class
                    .getDeclaredMethod("refreshBufferSnapshotsLocked");
            refresh.setAccessible(true);
            refresh.invoke(source);
        }
    }

    @Test
    public void farForegroundReadWaitsForStartupBridgeInsteadOfCancellingPrefetch() throws Exception {
        assertFarForegroundReadWaitsForStartupBridge(true);
    }

    @Test
    public void nativePrebufferFarReadAlsoRetainsTheStartupBridge() throws Exception {
        assertFarForegroundReadWaitsForStartupBridge(false);
    }

    private void assertFarForegroundReadWaitsForStartupBridge(boolean systemExtractor) throws Exception {
        final long windowBytes = 24L * 1024L * 1024L;
        final long totalSize = 256L * 1024L * 1024L;
        CountDownLatch secondRangeOpened = new CountDownLatch(1);
        CountDownLatch releaseSecondRange = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        AtomicInteger farRequestCount = new AtomicInteger();
        ExecutorService farReads = Executors.newSingleThreadExecutor();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                while (farRequestCount.get() == 0) {
                    try (Socket socket = serverSocket.accept()) {
                        String range = readRangeHeader(socket);
                        assertTrue(range != null && range.startsWith("bytes="));
                        long start = Long.parseLong(range.substring("bytes=".length())
                                .split("-", 2)[0]);
                        requestCount.incrementAndGet();
                        if (start == windowBytes) {
                            secondRangeOpened.countDown();
                            if (!releaseSecondRange.await(5L, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out waiting to release startup fill");
                            }
                        }
                        boolean farRequest = start >= 173L * 1024L * 1024L;
                        if (farRequest) {
                            farRequestCount.incrementAndGet();
                        }
                        int length = farRequest ? 4 : (int) windowBytes;
                        long end = start + length - 1L;
                        OutputStream output = socket.getOutputStream();
                        String headers = "HTTP/1.1 206 Partial Content\r\n"
                                + "Content-Range: bytes " + start + "-" + end + "/"
                                + totalSize + "\r\n"
                                + "Content-Length: " + length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        try {
                            output.write(headers.getBytes(StandardCharsets.US_ASCII));
                            writeZeroBytes(output, length);
                            output.flush();
                        } catch (java.net.SocketException cancelledAfterStartup) {
                            if (start == windowBytes || farRequest) {
                                throw cancelledAfterStartup;
                            }
                            // After the bridge commits, foreground priority may cancel the
                            // following speculative window. That is not a cancelled bridge.
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        Constructor<HttpRangeMediaDataSource> sourceConstructor = HttpRangeMediaDataSource.class
                .getDeclaredConstructor(String.class, java.util.Map.class,
                        long.class, long.class, boolean.class, Boolean.class,
                        boolean.class, HttpRangeMediaDataSource.RangeTransferListener.class,
                        ReentrantLock.class);
        sourceConstructor.setAccessible(true);
        HttpRangeMediaDataSource source = sourceConstructor.newInstance(
                "http://127.0.0.1:" + serverSocket.getLocalPort()
                        + "/proxy/play/test.mkv",
                null, windowBytes, windowBytes, true, Boolean.TRUE, systemExtractor, null, null);
        try {
            // Exercise the conservative device admission policy regardless of the host JVM heap.
            setLongField(source, "cacheLimitBytes",
                    PlaybackPrebufferPolicy.resolveCacheLimitBytes(192L * 1024L * 1024L, true));
            setLongField(source, "startBufferBytes", PlaybackPrebufferPolicy.resolveStartBufferBytes(192L * 1024L * 1024L));
            // The first read commits the 24 MiB prefix and starts the second prefetch window.
            byte[] first = new byte[4];
            assertEquals(4, source.readAt(0L, first, 0, first.length));
            if (!systemExtractor) {
                source.requestPlaybackPrebuffer();
            }
            assertTrue("second startup Range should be in flight",
                    secondRangeOpened.await(5L, TimeUnit.SECONDS));

            Future<Integer> farRead = farReads.submit(() -> {
                byte[] bytes = new byte[4];
                return source.readAt(173L * 1024L * 1024L, bytes, 0, bytes.length);
            });

            Thread.sleep(350L);
            assertFalse("far extractor read must wait for startup bridge", farRead.isDone());
            assertEquals("far read must not cancel or reopen the active second Range", 2,
                    requestCount.get());

            releaseSecondRange.countDown();
            assertEquals(4, (int) farRead.get(20L, TimeUnit.SECONDS));
            assertTrue("startup gate must be satisfied after four contiguous windows",
                    source.isStartupBufferGateSatisfied());
            assertTrue("far Range must eventually be served", farRequestCount.get() > 0);
            server.get(20L, TimeUnit.SECONDS);
        } finally {
            releaseSecondRange.countDown();
            source.close();
            farReads.shutdownNow();
            server.cancel(true);
        }
    }

    @Test
    public void onlySmallUnanchoredTailReadsAreTreatedAsMetadataProbes() {
        long total = 88_246_893_816L;
        long tail = total - 854_332L;
        long window = 24L * 1024L * 1024L;

        assertTrue(HttpRangeMediaDataSource.isAuxiliaryTailProbe(
                false, total, tail, window, -1L, false, -1L, 1, -1L));
        assertFalse(HttpRangeMediaDataSource.isAuxiliaryTailProbe(
                false, total, tail, window, -1L, false, -1L, 64 * 1024, -1L));
        assertTrue(HttpRangeMediaDataSource.isAuxiliaryTailProbe(
                true, total, tail, window, -1L, false, -1L, 1, -1L));
        assertFalse(HttpRangeMediaDataSource.isAuxiliaryTailProbe(
                true, total, tail, window, 4L * 1024L * 1024L, false, -1L, 1,
                4L * 1024L * 1024L));
        assertFalse(HttpRangeMediaDataSource.isAuxiliaryTailProbe(
                false, total, tail, window, -1L, true, -1L, 1, -1L));
    }

    @Test
    public void explicitMetadataReadsUseSmallWindowsWithoutStartingPlaybackFill() throws Exception {
        final long total = 2L * 1024L * 1024L * 1024L;
        final long tail = total - 1_024L * 1_024L;
        final int probeWindow = 256 * 1024;
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                for (int index = 0; index < 4; index++) {
                    try (Socket socket = serverSocket.accept()) {
                        long expectedStart = tail + (long) index * probeWindow;
                        assertEquals("metadata reads must not request a 24 MiB playback block",
                                "bytes=" + expectedStart + "-" + (expectedStart + probeWindow - 1),
                                readRangeHeader(socket));
                        requestCount.incrementAndGet();
                        OutputStream output = socket.getOutputStream();
                        String response = "HTTP/1.1 206 Partial Content\r\n"
                                + "Content-Range: bytes " + expectedStart + "-"
                                + (expectedStart + probeWindow - 1) + "/" + total + "\r\n"
                                + "Content-Length: " + probeWindow + "\r\n"
                                + "Connection: close\r\n\r\n";
                        output.write(response.getBytes(StandardCharsets.US_ASCII));
                        byte[] data = new byte[probeWindow];
                        java.util.Arrays.fill(data, (byte) (index + 1));
                        output.write(data);
                        output.flush();
                    }
                }
                serverSocket.setSoTimeout(300);
                try (Socket unexpected = serverSocket.accept()) {
                    throw new AssertionError("container metadata triggered playback prefetch");
                } catch (SocketTimeoutException expected) {
                    // No fill should start until the extractor resolves the actual sample.
                }
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/movie.mkv", null,
                null, true, null);
        source.primeKnownTotalSize(total);
        source.setContainerMetadataMode(true);
        byte[] actual = new byte[64 * 1024];
        try {
            for (int chunk = 0; chunk < 16; chunk++) {
                assertEquals(actual.length,
                        source.readAt(tail + (long) chunk * actual.length,
                                actual, 0, actual.length));
                assertEquals(chunk / 4 + 1, actual[0]);
                assertEquals("retain only one bounded metadata window", probeWindow,
                        source.getCachedBytesSnapshotForDiagnostics());
                assertEquals("metadata must not establish a playback anchor", -1L,
                        source.getPlaybackAnchorPositionSnapshot());
                assertEquals(0L, source.getBufferedAheadBytes());
                assertEquals(0L, source.getBufferedAheadBytesForProgress());
                assertFalse(source.isStartupBufferGateSatisfied());
            }
            server.get(5L, TimeUnit.SECONDS);
            assertEquals(4, requestCount.get());
            source.setContainerMetadataMode(false);
            assertEquals("release metadata memory before admitting real playback", 0L,
                    source.getCachedBytes());
            source.onExtractorSeekStarted(123_456_789L);
            assertEquals("the real seek may also target the file tail", 123_456_789L,
                    source.getPlaybackAnchorPositionSnapshot());
        } finally {
            source.close();
        }
    }

    @Test
    public void resolvedSeekAfterMetadataStartsAtRequestedPositionWithoutAnotherDecoderRead()
            throws Exception {
        final long total = 400L * 1024L * 1024L;
        final long metadataPosition = total - 512L * 1024L;
        final long[] seekPositions = new long[]{
                140L * 1024L * 1024L,
                260L * 1024L * 1024L,
                60L * 1024L * 1024L
        };
        final int playbackWindow = 1_024;
        final long startupBytes = 4L * playbackWindow;
        final long cacheBytes = 10L * playbackWindow;
        AtomicLong observedSeekPosition = new AtomicLong(-1L);
        AtomicInteger metadataRequests = new AtomicInteger();
        AtomicInteger playbackRequests = new AtomicInteger();
        AtomicInteger unexpectedRequests = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket socket = serverSocket.accept()) {
                        String range = readRangeHeader(socket);
                        String[] bounds = range.substring("bytes=".length()).split("-", 2);
                        long start = Long.parseLong(bounds[0]);
                        long end = Long.parseLong(bounds[1]);
                        if (start == metadataPosition) {
                            metadataRequests.incrementAndGet();
                            assertEquals(metadataPosition + 256L * 1024L - 1L, end);
                        } else {
                            playbackRequests.incrementAndGet();
                            long expected = observedSeekPosition.get();
                            if (start < expected || start >= expected + cacheBytes) {
                                unexpectedRequests.incrementAndGet();
                            }
                            assertEquals("resume/seek fills use the normal playback block",
                                    playbackWindow, end - start + 1L);
                        }
                        int length = (int) (end - start + 1L);
                        OutputStream output = socket.getOutputStream();
                        String response = "HTTP/1.1 206 Partial Content\r\n"
                                + "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n"
                                + "Content-Length: " + length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        output.write(response.getBytes(StandardCharsets.US_ASCII));
                        output.write(new byte[length]);
                        output.flush();
                    } catch (SocketTimeoutException idle) {
                        // The test may still be asserting the completed playback episode.
                    }
                }
            } catch (IOException stopped) {
                if (!serverSocket.isClosed()) {
                    throw new AssertionError(stopped);
                }
            }
        });
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/movie.mkv", null,
                null, true, null);
        ExecutorService fills = Executors.newSingleThreadExecutor();
        Field executorField = HttpRangeMediaDataSource.class.getDeclaredField("prefetchExecutor");
        executorField.setAccessible(true);
        executorField.set(source, fills);
        setLongField(source, "prefetchWindowSize", playbackWindow);
        setLongField(source, "startBufferBytes", startupBytes);
        // The metadata window shares the normal budget. Use the production-size budget for
        // that pass, then reduce all playback sizes by the same factor for deterministic I/O.
        source.primeKnownTotalSize(total);
        source.setContainerMetadataMode(true);
        try {
            byte[] first = new byte[1];
            assertEquals(1, source.readAt(metadataPosition, first, 0, first.length));
            source.setContainerMetadataMode(false);
            setLongField(source, "cacheLimitBytes", cacheBytes);
            for (long position : seekPositions) {
                observedSeekPosition.set(position);
                source.onExtractorSeekStarted(position);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
                while (source.getBufferedAheadBytes() < cacheBytes
                        && System.nanoTime() < deadline) {
                    Thread.sleep(5L);
                }
                assertEquals("fill must start at the resolved nonzero position", position,
                        source.getPlaybackAnchorPositionSnapshot());
                assertEquals("the complete target runway fills without a decoder read", cacheBytes,
                        source.getBufferedAheadBytes());
                assertTrue(source.isStartupBufferGateSatisfied());
            }
            assertEquals(1, metadataRequests.get());
            assertEquals("never prefetch byte zero or the prior seek", 0, unexpectedRequests.get());
            assertEquals("each target fills exactly ten blocks", 30, playbackRequests.get());
        } finally {
            source.close();
            fills.shutdownNow();
            server.cancel(true);
        }
    }

    @Test
    public void loopbackPhysicalRangeKeepsLogicalCursor() {
        String loopback = "http://127.0.0.1:6677/proxy/play/disk/movie.mkv";
        String ordinary = "https://media.example.com/movie.mkv";
        assertEquals(1_024L,
                HttpRangeMediaDataSource.resolveLoopbackWireStart(loopback, 1_024L));
        assertEquals(8_192L,
                HttpRangeMediaDataSource.resolveLoopbackWireEnd(loopback, 8_192L));
        assertEquals(0L,
                HttpRangeMediaDataSource.resolveLoopbackWireStart(loopback, 0L));
        assertEquals(1_024L,
                HttpRangeMediaDataSource.resolveLoopbackWireStart(ordinary, 1_024L));
        assertEquals(8_192L,
                HttpRangeMediaDataSource.resolveLoopbackWireEnd(ordinary, 8_192L));
    }

    @Test
    public void responseLengthClampsAnOverstatedTailRangeToTheVerifiedFileEnd() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveOverstatedTailRangeResponse(requestCount);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] actual = new byte[4_096];
        try {
            assertEquals(1_024, source.readAt(1_024L, actual, 0, actual.length));
            assertEquals(2_048L, source.getKnownTotalSize());
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertEquals(1, requestCount.get());
        assertEquals(0x71, actual[0]);
        assertEquals(0x72, actual[1]);
    }

    @Test
    public void unknownLengthLoopbackSeekUsesOpenEndedRange() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try (Socket socket = serverSocket.accept()) {
                String range = readRangeHeader(socket);
                assertEquals("bytes=1024-", range);
                requestCount.incrementAndGet();
                byte[] body = new byte[]{0x21, 0x22, 0x23, 0x24};
                OutputStream output = socket.getOutputStream();
                String headers = "HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes 1032-1035/2048\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/play/video", null);
        byte[] actual = new byte[4];
        try {
            assertEquals(actual.length, source.readAt(1024L, actual, 0, actual.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertEquals(1, requestCount.get());
        assertArrayEquals(new byte[]{0x21, 0x22, 0x23, 0x24}, actual);
        assertEquals(2048L, source.getKnownTotalSize());
    }

    @Test
    public void imagePrefixedLoopbackPayloadIsDiscardedAndRetried() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                for (int request = 0; request < 2; request++) {
                    try (Socket socket = serverSocket.accept()) {
                        String range = readRangeHeader(socket);
                        assertEquals("bytes=1024-", range);
                        requestCount.incrementAndGet();
                        byte[] body = request == 0
                                ? new byte[]{
                                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                                0x1A, 0x45, (byte) 0xDF, (byte) 0xA3
                        }
                                : new byte[]{0x31, 0x32, 0x33, 0x34};
                        OutputStream output = socket.getOutputStream();
                        String headers = "HTTP/1.1 206 Partial Content\r\n"
                                + "Content-Range: bytes 1032-1035/2048\r\n"
                                + "Content-Length: " + body.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        output.write(headers.getBytes(StandardCharsets.US_ASCII));
                        output.write(body);
                        output.flush();
                    }
                }
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/play/video", null);
        byte[] actual = new byte[4];
        try {
            assertEquals(actual.length, source.readAt(1024L, actual, 0, actual.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertEquals(2, requestCount.get());
        assertArrayEquals(new byte[]{0x31, 0x32, 0x33, 0x34}, actual);
    }

    @Test
    public void knownLoopbackEightByteRangeHeaderBugStillDeliversContiguousWindows() throws Exception {
        final int windowSize = 24 * 1024 * 1024;
        final int deliveredWindowSize = windowSize - 8;
        final long secondStart = deliveredWindowSize;
        final long totalLength = windowSize * 4L;
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveBareThenShiftedLoopbackRangeResponses(requestCount, secondStart,
                        windowSize, deliveredWindowSize, totalLength);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/play/video", null);
        byte[] first = new byte[4];
        byte[] second = new byte[4];
        try {
            assertEquals(first.length, source.readAt(0L, first, 0, first.length));
            assertEquals(second.length, source.readAt(secondStart, second, 0, second.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertArrayEquals(new byte[]{0x41, 0x41, 0x41, 0x41}, first);
        assertArrayEquals(new byte[]{0x42, 0x42, 0x42, 0x42}, second);
        assertEquals("the known eight-byte header bug must not trigger a retry loop", 2,
                requestCount.get());
    }

    @Test
    public void loopbackShiftedStartWithOverstatedEndStillUsesContiguousEntity() throws Exception {
        final int windowSize = 24 * 1024 * 1024;
        final int deliveredWindowSize = windowSize - 8;
        final long secondStart = deliveredWindowSize;
        final long totalLength = windowSize * 4L;
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveBareThenShiftedLoopbackRangeResponses(requestCount, secondStart,
                        windowSize, deliveredWindowSize, totalLength, 8L);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/play/video", null);
        byte[] first = new byte[4];
        byte[] second = new byte[4];
        try {
            assertEquals(first.length, source.readAt(0L, first, 0, first.length));
            assertEquals(second.length, source.readAt(secondStart, second, 0, second.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertArrayEquals(new byte[]{0x41, 0x41, 0x41, 0x41}, first);
        assertArrayEquals(new byte[]{0x42, 0x42, 0x42, 0x42}, second);
        assertEquals("an overstated malformed end must not reject the contiguous body", 2,
                requestCount.get());
    }

    @Test
    public void temporary416DoesNotReplaceTheVerifiedFileLength() throws Exception {
        final long totalLength = ADVERTISED_LENGTH * 2L;
        final long secondWindowStart = ADVERTISED_LENGTH;
        AtomicInteger requestCount = new AtomicInteger();
        byte[] firstWindow = new byte[ADVERTISED_LENGTH];
        byte[] secondWindow = new byte[ADVERTISED_LENGTH];
        secondWindow[0] = 0x61;
        secondWindow[1] = 0x62;
        secondWindow[2] = 0x63;
        secondWindow[3] = 0x64;
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveExactRangeResponse(requestCount, 0L, firstWindow, totalLength);
                // 6677 can temporarily expose the current cache length here. The reported
                // length is below the requested position even though the first response has
                // already established the real, larger file size.
                serveUnsatisfiedRangeResponse(requestCount, secondWindowStart,
                        ADVERTISED_LENGTH / 2L);
                serveExactRangeResponse(requestCount, secondWindowStart, secondWindow, totalLength);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = new HttpRangeMediaDataSource(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] firstRead = new byte[4];
        byte[] resumedRead = new byte[4];
        try {
            assertEquals(firstRead.length, source.readAt(0L, firstRead, 0, firstRead.length));
            assertEquals(resumedRead.length,
                    source.readAt(secondWindowStart, resumedRead, 0, resumedRead.length));
            assertEquals(totalLength, source.getKnownTotalSize());
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertArrayEquals(new byte[]{0x61, 0x62, 0x63, 0x64}, resumedRead);
        assertEquals("temporary 416 must reopen the same range", 3, requestCount.get());
    }

    @Test
    public void systemRangeReportsBytesWhenTheHttpBodyArrives() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicInteger receivedBytes = new AtomicInteger();
        byte[] expected = new byte[]{0x41, 0x42, 0x43, 0x44};
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveRangeResponse(requestCount, expected);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null,
                receivedBytes::addAndGet);
        byte[] actual = new byte[expected.length];
        try {
            assertEquals(expected.length, source.readAt(0L, actual, 0, actual.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertArrayEquals(expected, actual);
        assertEquals(expected.length, receivedBytes.get());
        assertEquals(1, requestCount.get());
    }

    @Test
    public void systemRangeDoesNotStartCompetingBackgroundPrefetch() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        byte[] expected = new byte[]{0x51, 0x52, 0x53, 0x54};
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveExactRangeResponse(requestCount, 0L, expected);
                // The system-codec path already has ExoPlayer's foreground loader. A second
                // fill-ahead request here would compete with its Range chain and cause the
                // slow/zero-speed oscillation seen on the 6677 endpoint.
                serverSocket.setSoTimeout(600);
                try (Socket unexpected = serverSocket.accept()) {
                    requestCount.incrementAndGet();
                } catch (SocketTimeoutException expectedTimeout) {
                    // Expected: system playback never opens an asynchronous prefetch request.
                }
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] actual = new byte[expected.length];
        try {
            assertEquals(expected.length, source.readAt(0L, actual, 0, actual.length));
            server.get(3L, TimeUnit.SECONDS);
        } finally {
            source.close();
        }

        assertArrayEquals(expected, actual);
        assertEquals("system playback must keep a single Range loader", 1, requestCount.get());
    }

    @Test
    public void foregroundRangeReadsDoNotWaitBehindAnotherSource() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch firstRequestOpened = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        ExecutorService clients = Executors.newCachedThreadPool();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                for (int request = 0; request < 2; request++) {
                    Socket socket = serverSocket.accept();
                    clients.submit(() -> serveGatedRangeResponse(socket, requestCount,
                            firstRequestOpened, releaseFirstRequest));
                }
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        ExecutorService reads = Executors.newFixedThreadPool(2);
        HttpRangeMediaDataSource first = new HttpRangeMediaDataSource(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/first", null);
        HttpRangeMediaDataSource second = new HttpRangeMediaDataSource(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/second", null);
        try {
            Future<Integer> firstRead = reads.submit(() -> readFirstBytes(first));
            assertTrue(firstRequestOpened.await(5L, TimeUnit.SECONDS));
            Future<Integer> secondRead = reads.submit(() -> readFirstBytes(second));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (requestCount.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals("a decoder read must not wait behind an unrelated range request",
                    2, requestCount.get());

            releaseFirstRequest.countDown();
            assertEquals(4, (int) firstRead.get(5L, TimeUnit.SECONDS));
            assertEquals(4, (int) secondRead.get(5L, TimeUnit.SECONDS));
        } finally {
            releaseFirstRequest.countDown();
            first.close();
            second.close();
            reads.shutdownNow();
            clients.shutdownNow();
        }
        server.get(5L, TimeUnit.SECONDS);
        assertEquals(2, requestCount.get());
    }

    @Test
    public void foregroundRangeMissDoesNotHoldSourceMonitorWhileWaitingForRequestLock() throws Exception {
        byte[] expected = new byte[]{0x21, 0x22, 0x23, 0x24};
        AtomicInteger requestCount = new AtomicInteger();
        ReentrantLock requestLock = new ReentrantLock();
        ExecutorService reads = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveExactRangeResponse(requestCount, 0L, expected);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/play/test.mkv", null,
                null, false, requestLock);
        requestLock.lock();
        boolean stateUpdateCompletedWhileRequestLocked = false;
        try {
            Future<Integer> foregroundRead = reads.submit(() -> {
                readerThread.set(Thread.currentThread());
                return readFirstBytes(source);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while ((readerThread.get() == null
                    || !requestLock.hasQueuedThread(readerThread.get()))
                    && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertTrue("foreground miss should be waiting on the injected request lock",
                    readerThread.get() != null && requestLock.hasQueuedThread(readerThread.get()));

            Future<?> sourceStateUpdate = reads.submit(() ->
                    source.primeKnownTotalSize(ADVERTISED_LENGTH));
            try {
                sourceStateUpdate.get(2L, TimeUnit.SECONDS);
                stateUpdateCompletedWhileRequestLocked = true;
            } catch (java.util.concurrent.TimeoutException expectedMonitorContention) {
                // The lock is released below so a failing regression test does not strand reads.
            }

            requestLock.unlock();
            assertEquals(expected.length,
                    (int) foregroundRead.get(5L, TimeUnit.SECONDS));
            sourceStateUpdate.get(5L, TimeUnit.SECONDS);
            server.get(5L, TimeUnit.SECONDS);
        } finally {
            if (requestLock.isHeldByCurrentThread()) {
                requestLock.unlock();
            }
            source.close();
            reads.shutdownNow();
        }

        assertTrue("a blocked network miss must not hold the source monitor",
                stateUpdateCompletedWhileRequestLocked);
        assertEquals(1, requestCount.get());
    }

    @Test
    public void foregroundCacheHitDoesNotWaitForRangeRequestLock() throws Exception {
        byte[] expected = new byte[]{0x31, 0x32, 0x33, 0x34};
        AtomicInteger requestCount = new AtomicInteger();
        ReentrantLock requestLock = new ReentrantLock();
        ExecutorService reads = Executors.newSingleThreadExecutor();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveExactRangeResponse(requestCount, 0L, expected);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/play/test.mkv", null,
                null, false, requestLock);
        try {
            assertEquals(expected.length, readFirstBytes(source));
            server.get(5L, TimeUnit.SECONDS);

            requestLock.lock();
            try {
                Future<Integer> cacheHit = reads.submit(() -> readFirstBytes(source));
                assertEquals("a cache hit must not wait behind the network lane", expected.length,
                        (int) cacheHit.get(500L, TimeUnit.MILLISECONDS));
            } finally {
                requestLock.unlock();
            }
        } finally {
            source.close();
            reads.shutdownNow();
        }
        assertEquals("cache hits must not create another HTTP Range", 1, requestCount.get());
    }

    @Test
    public void foregroundCacheMissReusesAnOverlappingPrefetchRange() throws Exception {
        CountDownLatch prefetchOpened = new CountDownLatch(1);
        CountDownLatch prefetchResponseSent = new CountDownLatch(1);
        CountDownLatch releasePrefetch = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        ExecutorService handlers = Executors.newCachedThreadPool();
        ExecutorService reads = Executors.newSingleThreadExecutor();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                for (int request = 1; request <= 2; request++) {
                    Socket socket = serverSocket.accept();
                    requestCount.incrementAndGet();
                    final int requestIndex = request;
                    handlers.submit(() -> serveSerializedRangeResponse(socket, requestIndex,
                            prefetchOpened, prefetchResponseSent, releasePrefetch));
                }
                if (!prefetchResponseSent.await(5L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for prefetch response body");
                }
                serverSocket.setSoTimeout(500);
                try (Socket duplicate = serverSocket.accept()) {
                    requestCount.incrementAndGet();
                    throw new AssertionError("foreground miss reopened an overlapping Range");
                } catch (SocketTimeoutException expectedNoDuplicateRange) {
                    // Both the metadata probe and the fill Range were enough for the read.
                }
            } catch (IOException error) {
                throw new AssertionError(error);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        try {
            assertEquals(ADVERTISED_LENGTH, source.getSize());
            assertTrue(prefetchOpened.await(5L, TimeUnit.SECONDS));

            Future<Integer> foregroundRead = reads.submit(() -> {
                byte[] bytes = new byte[4];
                return source.readAt(256L * 1024L, bytes, 0, bytes.length);
            });

            // The foreground read is inside the in-flight prefetch Range. It must wait for that
            // response and consume the committed window instead of opening the same offset.
            Thread.sleep(350L);
            assertFalse("the overlapping foreground miss must not open a duplicate Range",
                    foregroundRead.isDone());
            releasePrefetch.countDown();
            assertEquals(4, (int) foregroundRead.get(5L, TimeUnit.SECONDS));
            server.get(5L, TimeUnit.SECONDS);
            assertEquals("the overlapping Range should be requested only once", 2,
                    requestCount.get());
        } finally {
            releasePrefetch.countDown();
            source.close();
            reads.shutdownNow();
            handlers.shutdownNow();
        }
    }

    @Test
    public void systemForegroundReadIsCancelledBySeekAndNewPositionBecomesAnchor() throws Exception {
        CountDownLatch oldRangeOpened = new CountDownLatch(1);
        CountDownLatch oldRangeCancelled = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        ExecutorService reads = Executors.newSingleThreadExecutor();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveCancelledReadThenSeekRange(oldRangeOpened, oldRangeCancelled, requestCount);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        try {
            Future<Integer> oldRead = reads.submit(() -> readFirstBytes(source));
            assertTrue("old playback Range should reach the server",
                    oldRangeOpened.await(5L, TimeUnit.SECONDS));

            source.onPlaybackSeekStarted();

            try {
                oldRead.get(5L, TimeUnit.SECONDS);
                throw new AssertionError("seek should cancel the old foreground read");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof HttpRangeMediaDataSource.PlaybackReadSupersededException);
            }
            assertTrue("old HTTP request should be cancelled promptly",
                    oldRangeCancelled.await(5L, TimeUnit.SECONDS));

            byte[] soughtBytes = new byte[4];
            assertEquals(soughtBytes.length,
                    source.readAt(1_024L, soughtBytes, 0, soughtBytes.length));
            source.onPlaybackSeekFinished();
            assertEquals(1_024L, source.getPlaybackAnchorPositionSnapshot());
            server.get(5L, TimeUnit.SECONDS);
        } finally {
            source.close();
            reads.shutdownNow();
        }
        assertEquals(2, requestCount.get());
    }

    private int readFirstBytes(HttpRangeMediaDataSource source) throws IOException {
        byte[] bytes = new byte[4];
        return source.readAt(0L, bytes, 0, bytes.length);
    }

    private void serveSerializedRangeResponse(Socket socket,
                                               int requestIndex,
                                               CountDownLatch prefetchOpened,
                                               CountDownLatch prefetchResponseSent,
                                               CountDownLatch releasePrefetch) {
        try (Socket client = socket) {
            client.setSoTimeout(5_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            String range = null;
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                if (line.regionMatches(true, 0, "Range:", 0, "Range:".length())) {
                    range = line.substring("Range:".length()).trim();
                }
            }
            assertTrue(range != null && range.startsWith("bytes="));
            if (requestIndex == 2) {
                prefetchOpened.countDown();
                client.setSoTimeout(100);
                while (releasePrefetch.getCount() > 0L) {
                    try {
                        if (reader.read() < 0) {
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {
                        // Keep observing the client until it cancels or the test releases it.
                    }
                }
            }
            long start = Long.parseLong(range.substring("bytes=".length()).split("-")[0]);
            long end = Math.min(ADVERTISED_LENGTH - 1L, start + 256L * 1024L - 1L);
            int payloadLength = (int) (end - start + 1L);
            OutputStream output = client.getOutputStream();
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes " + start + "-" + end + "/" + ADVERTISED_LENGTH + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Content-Length: " + payloadLength + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[payloadLength]);
            output.flush();
            if (requestIndex == 2) {
                prefetchResponseSent.countDown();
            }
        } catch (IOException error) {
            // The test releases the body before closing the source; I/O failure is not expected.
            if (requestIndex != 2) {
                throw new AssertionError(error);
            }
        }
    }

    private void serveCancelledReadThenSeekRange(CountDownLatch oldRangeOpened,
                                                  CountDownLatch oldRangeCancelled,
                                                  AtomicInteger requestCount) throws IOException {
        try (Socket oldRequest = serverSocket.accept()) {
            oldRequest.setSoTimeout(100);
            String oldRange = readRangeHeader(oldRequest);
            assertTrue(oldRange.startsWith("bytes=0-"));
            requestCount.incrementAndGet();
            oldRangeOpened.countDown();

            while (true) {
                try {
                    if (oldRequest.getInputStream().read() < 0) {
                        oldRangeCancelled.countDown();
                        break;
                    }
                } catch (SocketTimeoutException waitingForSeekCancellation) {
                    // Observe the client connection until the seek closes the old Call.
                }
            }
        }

        try (Socket seekRequest = serverSocket.accept()) {
            String seekRange = readRangeHeader(seekRequest);
            assertTrue("the next Range must start at the seek target",
                    seekRange.startsWith("bytes=1024-"));
            requestCount.incrementAndGet();
            OutputStream output = seekRequest.getOutputStream();
            byte[] body = new byte[]{0x61, 0x62, 0x63, 0x64};
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes 1024-1027/2048\r\n"
                    + "Content-Length: 4\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }
    }

    private void servePrefetchRanges(AtomicInteger requestCount,
                                     long totalSize,
                                     int maxRequests) throws IOException {
        try {
            for (int requestIndex = 0; requestIndex < maxRequests; requestIndex++) {
                try (Socket socket = serverSocket.accept()) {
                String range = readRangeHeader(socket);
                assertTrue(range != null && range.startsWith("bytes="));
                requestCount.incrementAndGet();
                String[] bounds = range.substring("bytes=".length()).split("-", 2);
                long start = Long.parseLong(bounds[0]);
                long requestedEnd = bounds.length > 1 && !bounds[1].isEmpty()
                        ? Long.parseLong(bounds[1]) : start + 1_023L;
                long end = Math.min(totalSize - 1L, Math.max(start, requestedEnd));
                int length = (int) Math.min(1_024L, end - start + 1L);
                OutputStream output = socket.getOutputStream();
                String headers = "HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes " + start + "-" + (start + length - 1L)
                        + "/" + totalSize + "\r\n"
                        + "Content-Length: " + length + "\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                output.write(new byte[length]);
                    output.flush();
                }
            }
        } catch (SocketTimeoutException expectedIdleAfterPrefetch) {
            // The fill lane is allowed to stop once the startup target is reached; no
            // additional Range request is required for this regression.
        }
    }

    private void writeZeroBytes(OutputStream output, int length) throws IOException {
        byte[] chunk = new byte[Math.min(64 * 1024, Math.max(1, length))];
        int remaining = length;
        while (remaining > 0) {
            int count = Math.min(remaining, chunk.length);
            output.write(chunk, 0, count);
            remaining -= count;
        }
    }

    private void serveGatedRangeResponse(Socket socket,
                                          AtomicInteger requestCount,
                                          CountDownLatch firstRequestOpened,
                                          CountDownLatch releaseFirstRequest) {
        try (Socket client = socket) {
            client.setSoTimeout(5_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            String range = null;
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                if (line.regionMatches(true, 0, "Range:", 0, "Range:".length())) {
                    range = line.substring("Range:".length()).trim();
                }
            }
            assertTrue(range != null && range.startsWith("bytes=0-"));
            int request = requestCount.incrementAndGet();
            if (request == 1) {
                firstRequestOpened.countDown();
                if (!releaseFirstRequest.await(5L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release first range request");
                }
            }

            OutputStream output = client.getOutputStream();
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes 0-" + (ADVERTISED_LENGTH - 1)
                    + "/" + ADVERTISED_LENGTH + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Content-Length: " + ADVERTISED_LENGTH + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[ADVERTISED_LENGTH]);
            output.flush();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private void serveRangeResponse(AtomicInteger requestCount, byte[] body) throws IOException {
        try (Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(5_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String range = null;
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                if (line.regionMatches(true, 0, "Range:", 0, "Range:".length())) {
                    range = line.substring("Range:".length()).trim();
                }
            }
            assertTrue(range != null && range.startsWith("bytes=0-"));
            requestCount.incrementAndGet();

            OutputStream output = socket.getOutputStream();
            long advertisedEnd = body.length > 0 ? body.length - 1L : ADVERTISED_LENGTH - 1L;
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes 0-" + advertisedEnd
                    + "/" + ADVERTISED_LENGTH + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }
    }

    private void serveResetRangeResponse(AtomicInteger requestCount, long expectedStart) throws IOException {
        try (Socket socket = serverSocket.accept()) {
            String range = readRangeHeader(socket);
            assertTrue(range != null && range.startsWith("bytes=" + expectedStart + "-"));
            requestCount.incrementAndGet();

            int resetPayloadLength = (int) expectedStart;
            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Range: bytes 0-" + (resetPayloadLength - 1)
                    + "/" + ADVERTISED_LENGTH + "\r\n"
                    // Deliberately match the spider's malformed full-file declaration.
                    + "Content-Length: " + ADVERTISED_LENGTH + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[resetPayloadLength]);
            output.flush();
        }
    }

    private void serveShortThenFullRangeResponse(AtomicInteger requestCount) throws IOException {
        byte[] fullBody = new byte[ADVERTISED_LENGTH];
        fullBody[0] = 0x31;
        fullBody[1] = 0x32;
        fullBody[2] = 0x33;
        fullBody[3] = 0x34;
        byte[] shortBody = new byte[64];
        System.arraycopy(fullBody, 0, shortBody, 0, shortBody.length);
        for (int request = 0; request < 2; request++) {
            try (Socket socket = serverSocket.accept()) {
                String range = readRangeHeader(socket);
                assertTrue(range != null && range.startsWith("bytes=0-"));
                requestCount.incrementAndGet();
                byte[] body = request == 0 ? shortBody : fullBody;
                OutputStream output = socket.getOutputStream();
                String headers = "HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes 0-" + (ADVERTISED_LENGTH - 1)
                        + "/" + ADVERTISED_LENGTH + "\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            }
        }
    }

    private void serveSystemShortPrefixThenExactContinuation(AtomicInteger requestCount)
            throws IOException {
        byte[] prefix = filledBytes(64, (byte) 0x41);
        try (Socket first = serverSocket.accept()) {
            String range = readRangeHeader(first);
            assertTrue(range != null && range.startsWith("bytes=0-"));
            requestCount.incrementAndGet();
            OutputStream output = first.getOutputStream();
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes 0-" + (ADVERTISED_LENGTH - 1L) + "/"
                    + ADVERTISED_LENGTH + "\r\n"
                    + "Content-Length: " + prefix.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(prefix);
            output.flush();
        }
        try (Socket second = serverSocket.accept()) {
            String range = readRangeHeader(second);
            assertTrue(range != null && range.startsWith("bytes=64-"));
            requestCount.incrementAndGet();
            byte[] continuation = new byte[]{0x51, 0x52, 0x53, 0x54};
            OutputStream output = second.getOutputStream();
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes 64-67/" + ADVERTISED_LENGTH + "\r\n"
                    + "Content-Length: " + continuation.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(continuation);
            output.flush();
        }
    }

    private void serveOverstatedTailRangeResponse(AtomicInteger requestCount) throws IOException {
        try (Socket socket = serverSocket.accept()) {
            String range = readRangeHeader(socket);
            assertTrue(range != null && range.startsWith("bytes=1024-"));
            requestCount.incrementAndGet();
            byte[] body = new byte[1_024];
            body[0] = 0x71;
            body[1] = 0x72;
            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    // Deliberately overstate the response end; the total is authoritative.
                    + "Content-Range: bytes 1024-999999/2048\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }
    }

    private void serveBareThenShiftedLoopbackRangeResponses(AtomicInteger requestCount,
                                                             long secondStart,
                                                             int windowSize,
                                                             int deliveredWindowSize,
                                                             long totalLength) throws IOException {
        serveBareThenShiftedLoopbackRangeResponses(requestCount, secondStart, windowSize,
                deliveredWindowSize, totalLength, -8L);
    }

    private void serveBareThenShiftedLoopbackRangeResponses(AtomicInteger requestCount,
                                                             long secondStart,
                                                             int windowSize,
                                                             int deliveredWindowSize,
                                                             long totalLength,
                                                             long endDelta) throws IOException {
        try (Socket first = serverSocket.accept()) {
            String range = readRangeHeader(first);
            assertTrue(range != null && range.startsWith("bytes=0-"));
            requestCount.incrementAndGet();
            writeChunkedResponse(first.getOutputStream(), "bytes 0", totalLength,
                    filledBytes(deliveredWindowSize, (byte) 0x41));
        }
        try (Socket second = serverSocket.accept()) {
            String range = readRangeHeader(second);
            assertTrue(range != null && range.startsWith("bytes="));
            String[] requestedBounds = range.substring("bytes=".length()).split("-", 2);
            long requestedStart = Long.parseLong(requestedBounds[0]);
            // Unknown-length loopback seeks intentionally use an open-ended wire range. The
            // logical window remains bounded by the source, so use the same nominal window end
            // for this response when the physical header has no explicit end.
            long requestedEnd = requestedBounds.length > 1
                    && !requestedBounds[1].isEmpty()
                    ? Long.parseLong(requestedBounds[1])
                    : requestedStart + windowSize - 1L;
            assertEquals("the physical request must stay at the logical cursor",
                    secondStart, requestedStart);
            requestCount.incrementAndGet();
            // The endpoint's known defect is in Content-Range only: it trims eight bytes from
            // both coordinates but the entity remains a contiguous prefix beginning at the
            // requested logical offset.
            String malformedRange = "bytes " + (requestedStart + 8L) + "-"
                    + (requestedEnd + endDelta) + "/" + totalLength;
            writeChunkedResponse(second.getOutputStream(), malformedRange, totalLength,
                    filledBytes(deliveredWindowSize, (byte) 0x42));
        }
    }

    private static byte[] filledBytes(int length, byte value) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static void writeChunkedResponse(OutputStream output,
                                             String contentRange,
                                             long declaredLength,
                                             byte[] body) throws IOException {
        String headers = "HTTP/1.1 206 Partial Content\r\n"
                + "Content-Range: " + contentRange + "\r\n"
                + "Content-Length: " + declaredLength + "\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(Integer.toHexString(body.length).getBytes(StandardCharsets.US_ASCII));
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private void serveExactRangeResponse(AtomicInteger requestCount,
                                         long expectedStart,
                                         byte[] body) throws IOException {
        serveExactRangeResponse(requestCount, expectedStart, body, ADVERTISED_LENGTH);
    }

    private void serveExactRangeResponse(AtomicInteger requestCount,
                                         long expectedStart,
                                         byte[] body,
                                         long totalLength) throws IOException {
        try (Socket socket = serverSocket.accept()) {
            String range = readRangeHeader(socket);
            assertTrue(range != null && range.startsWith("bytes=" + expectedStart + "-"));
            requestCount.incrementAndGet();

            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes " + expectedStart + "-"
                    + (expectedStart + body.length - 1L) + "/" + totalLength + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }
    }

    private void serveUnsatisfiedRangeResponse(AtomicInteger requestCount,
                                                long expectedStart,
                                                long temporaryLength) throws IOException {
        try (Socket socket = serverSocket.accept()) {
            String range = readRangeHeader(socket);
            assertTrue(range != null && range.startsWith("bytes=" + expectedStart + "-"));
            requestCount.incrementAndGet();

            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                    + "Content-Range: bytes */" + temporaryLength + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
    }

    private String readRangeHeader(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
        String range = null;
        for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
            if (line.regionMatches(true, 0, "Range:", 0, "Range:".length())) {
                range = line.substring("Range:".length()).trim();
            }
        }
        return range;
    }

}
