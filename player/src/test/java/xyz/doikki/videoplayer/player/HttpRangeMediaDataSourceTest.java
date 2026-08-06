package xyz.doikki.videoplayer.player;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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
    public void systemStreamingCommitsVerifiedShortPrefixThenContinuesAtExactOffset() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveSystemShortPrefixThenExactContinuation(requestCount);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        byte[] prefix = new byte[64];
        byte[] continuation = new byte[4];
        try {
            assertEquals(prefix.length, source.readAt(0L, prefix, 0, prefix.length));
            assertEquals(continuation.length, source.readAt(prefix.length, continuation, 0,
                    continuation.length));
        } finally {
            source.close();
        }
        server.get(5L, TimeUnit.SECONDS);

        assertEquals(2, requestCount.get());
        assertEquals(0x41, prefix[0]);
        assertArrayEquals(new byte[]{0x51, 0x52, 0x53, 0x54}, continuation);
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
        final int windowSize = 8 * 1024 * 1024;
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
        final int windowSize = 8 * 1024 * 1024;
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
    public void foregroundCacheMissDoesNotOpenAnOverlappingPrefetchRange() throws Exception {
        CountDownLatch prefetchOpened = new CountDownLatch(1);
        CountDownLatch prefetchCancelled = new CountDownLatch(1);
        CountDownLatch thirdRequestOpenedBeforePrefetchCancellation = new CountDownLatch(1);
        CountDownLatch releasePrefetch = new CountDownLatch(1);
        ExecutorService handlers = Executors.newCachedThreadPool();
        ExecutorService reads = Executors.newSingleThreadExecutor();
        Future<?> server = serverExecutor.submit(() -> {
            try {
                for (int request = 1; request <= 3; request++) {
                    Socket socket = serverSocket.accept();
                    final int requestIndex = request;
                    handlers.submit(() -> serveSerializedRangeResponse(socket, requestIndex,
                            prefetchOpened, prefetchCancelled,
                            thirdRequestOpenedBeforePrefetchCancellation, releasePrefetch));
                }
            } catch (IOException error) {
                throw new AssertionError(error);
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

            assertFalse("a foreground miss must cancel the prefetch before opening its range",
                    thirdRequestOpenedBeforePrefetchCancellation.await(350L, TimeUnit.MILLISECONDS));
            releasePrefetch.countDown();
            assertEquals(4, (int) foregroundRead.get(5L, TimeUnit.SECONDS));
            server.get(5L, TimeUnit.SECONDS);
        } finally {
            releasePrefetch.countDown();
            source.close();
            reads.shutdownNow();
            handlers.shutdownNow();
        }
    }

    private int readFirstBytes(HttpRangeMediaDataSource source) throws IOException {
        byte[] bytes = new byte[4];
        return source.readAt(0L, bytes, 0, bytes.length);
    }

    private void serveSerializedRangeResponse(Socket socket,
                                               int requestIndex,
                                               CountDownLatch prefetchOpened,
                                               CountDownLatch prefetchCancelled,
                                               CountDownLatch thirdRequestOpenedBeforePrefetchCancellation,
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
                            prefetchCancelled.countDown();
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {
                        // Keep observing the client until it cancels or the test releases it.
                    }
                }
            } else if (requestIndex == 3) {
                if (!prefetchCancelled.await(250L, TimeUnit.MILLISECONDS)) {
                    thirdRequestOpenedBeforePrefetchCancellation.countDown();
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
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } catch (IOException error) {
            // The foreground request deliberately cancels the in-flight prefetch before it
            // acquires the source-local request lock. A broken pipe is therefore expected.
            if (requestIndex != 2) {
                throw new AssertionError(error);
            }
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
