package xyz.doikki.videoplayer.player;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration coverage for the two-lane direct-origin Range fill.
 *
 * <p>The existing source tests use a serial server. These tests deliberately hold the primary
 * and adjacent sibling responses open so a duplicate Range or an unbounded third lane is visible
 * in a real socket trace.</p>
 */
public class HttpRangeConcurrentPrefetchTest {
    private static final long CONTENT_LENGTH = 256L * 1024L * 1024L;
    private static final int WINDOW_BYTES = 24 * 1024 * 1024;

    private DelayedRangeServer server;

    @Before
    public void setUp() throws Exception {
        server = new DelayedRangeServer(CONTENT_LENGTH);
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void directOriginOverlapsPrimaryAndSiblingWithoutDuplicateOffset() throws Exception {
        HttpRangeMediaDataSource source = newSource();
        try {
            byte[] first = new byte[1];
            assertEquals(1, source.readAt(0L, first, 0, first.length));

            // The first post-header window is the primary fill. A cache hit asks the source to
            // schedule the adjacent sibling rather than reopening the primary offset.
            assertEquals(1, source.readAt(0L, first, 0, first.length));
            server.awaitRequestCount(3, 5, TimeUnit.SECONDS);
            server.awaitActiveRequests(2, 5, TimeUnit.SECONDS);
            assertTrue("direct primary and sibling requests should overlap",
                    server.getMaximumActiveRequests() >= 2);
            assertEquals("primary and sibling reservations must be counted",
                    48L * 1024L * 1024L, readLongField(source, "reservedPrefetchBytes"));

            Set<Long> starts = server.getRequestedStarts();
            assertEquals("initial foreground plus primary and sibling use unique offsets", 3,
                    starts.size());
            assertTrue(starts.contains(0L));
            assertTrue(starts.contains(WINDOW_BYTES * 1L));
            assertTrue(starts.contains(WINDOW_BYTES * 2L));
            assertEquals("a Range offset must not be requested twice",
                    1, server.getMaximumRequestsForSameOffset());
            assertCacheAndReservationsWithinLimit(source);
        } finally {
            server.releaseDelayedBodies();
            source.close();
        }
    }

    @Test
    public void foregroundReusesInFlightWindowThenSeekCreatesNewAnchor() throws Exception {
        HttpRangeMediaDataSource source = newSource();
        try {
            byte[] first = new byte[1];
            assertEquals(1, source.readAt(0L, first, 0, first.length));
            server.awaitRequestCount(2, 5, TimeUnit.SECONDS);

            // This byte belongs to the delayed primary window. The foreground read waits for
            // that response and must not create a second request at the same offset.
            ExecutorService reader = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<Integer> result = reader.submit(
                        () -> source.readAt(WINDOW_BYTES, first, 0, first.length));
                server.awaitActiveRequests(1, 5, TimeUnit.SECONDS);
                server.releaseDelayedBodies();
                assertEquals(1, (int) result.get(5, TimeUnit.SECONDS));
            } finally {
                reader.shutdownNow();
            }
            assertTrue("foreground coverage must reuse the in-flight primary",
                    server.getRequestedStarts().contains(WINDOW_BYTES * 1L));
            assertEquals("foreground coverage must not duplicate the primary offset",
                    1, server.getMaximumRequestsForSameOffset());

            long seekPosition = WINDOW_BYTES * 3L;
            source.onPlaybackSeekStarted();
            source.onExtractorSeekStarted(seekPosition);
            assertEquals(seekPosition, source.getPlaybackAnchorPositionSnapshot());
            assertEquals(1, source.readAt(seekPosition, first, 0, first.length));
            assertTrue("seek must request the new anchor range",
                    server.getRequestedStarts().contains(seekPosition));
            assertCacheAndReservationsWithinLimit(source);
        } finally {
            server.releaseDelayedBodies();
            source.close();
        }
    }

    private HttpRangeMediaDataSource newSource() {
        return HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                server.url(), null, null, true, null);
    }

    private static void assertCacheAndReservationsWithinLimit(
            HttpRangeMediaDataSource source) throws Exception {
        synchronized (source) {
            long cached = readLongField(source, "cachedBytes");
            long reserved = readLongField(source, "reservedPrefetchBytes");
            long foregroundReserved = readLongField(source, "foregroundReservationBytes");
            long limit = readLongField(source, "cacheLimitBytes");
            assertTrue("cached + in-flight reservations must stay within the Range cap",
                    cached + reserved + foregroundReserved <= limit);
            assertTrue("the source cap must remain within the shared 240 MiB maximum",
                    limit <= 240L * 1024L * 1024L);
        }
    }

    private static long readLongField(Object value, String name) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(value);
    }

    private static final class DelayedRangeServer implements AutoCloseable {
        private final long contentLength;
        private final ServerSocket socket;
        private final ExecutorService handlers = Executors.newCachedThreadPool();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicInteger activeRequests = new AtomicInteger();
        private final AtomicInteger maximumActiveRequests = new AtomicInteger();
        private final AtomicInteger maximumRequestsForSameOffset = new AtomicInteger();
        private final java.util.concurrent.ConcurrentHashMap<Long, AtomicInteger> requestsByOffset =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<Long> requestedStarts = java.util.Collections.synchronizedSet(new HashSet<>());
        private final CountDownLatch releaseDelayedBodies = new CountDownLatch(1);
        private Thread acceptThread;

        DelayedRangeServer(long contentLength) throws IOException {
            this.contentLength = contentLength;
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(200);
        }

        void start() {
            running.set(true);
            acceptThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket client = socket.accept();
                        handlers.submit(() -> serve(client));
                    } catch (SocketTimeoutException ignored) {
                        // Poll running so close is prompt.
                    } catch (IOException error) {
                        if (running.get()) {
                            throw new AssertionError(error);
                        }
                    }
                }
            }, "range-test-accept");
            acceptThread.start();
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/video";
        }

        int getActiveRequests() {
            return activeRequests.get();
        }

        int getRequestCount() {
            return requestCount.get();
        }

        int getMaximumActiveRequests() {
            return maximumActiveRequests.get();
        }

        int getMaximumRequestsForSameOffset() {
            return maximumRequestsForSameOffset.get();
        }

        Set<Long> getRequestedStarts() {
            synchronized (requestedStarts) {
                return new HashSet<>(requestedStarts);
            }
        }

        void awaitRequestCount(int expected, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (requestCount.get() < expected && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue("timed out waiting for HTTP Range request " + expected,
                    requestCount.get() >= expected);
        }

        void awaitActiveRequests(int expected, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (maximumActiveRequests.get() < expected && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue("timed out waiting for overlapping HTTP requests " + expected,
                    maximumActiveRequests.get() >= expected);
        }

        void releaseDelayedBodies() {
            releaseDelayedBodies.countDown();
        }

        private void serve(Socket client) {
            int active = activeRequests.incrementAndGet();
            maximumActiveRequests.accumulateAndGet(active, Math::max);
            try (Socket ignored = client) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        client.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                long start = 0L;
                long end = contentLength - 1L;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                        String value = line.substring(6).trim();
                        int dash = value.indexOf('-');
                        start = Long.parseLong(value.substring(value.indexOf('=') + 1, dash));
                        if (dash + 1 < value.length()) {
                            end = Long.parseLong(value.substring(dash + 1));
                        }
                    }
                }
                if (start >= contentLength) {
                    write(client, "HTTP/1.1 416 Range Not Satisfiable\r\n"
                            + "Content-Range: bytes */" + contentLength
                            + "\r\nConnection: close\r\n\r\n", 0L, 0L);
                    return;
                }
                end = Math.min(contentLength - 1L,
                        Math.min(end, start + WINDOW_BYTES - 1L));
                long length = end - start + 1L;
                requestedStarts.add(start);
                int sameOffsetRequests = requestsByOffset
                        .computeIfAbsent(start, ignoredOffset -> new AtomicInteger()).incrementAndGet();
                maximumRequestsForSameOffset.accumulateAndGet(sameOffsetRequests, Math::max);
                int request = requestCount.incrementAndGet();
                OutputStream output = client.getOutputStream();
                String headers = "HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes " + start + "-" + end + "/" + contentLength + "\r\n"
                        + "Content-Length: " + length + "\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                if (request > 1) {
                    releaseDelayedBodies.await(10, TimeUnit.SECONDS);
                }
                byte[] chunk = new byte[64 * 1024];
                long remaining = length;
                long cursor = start;
                while (remaining > 0L) {
                    int count = (int) Math.min(chunk.length, remaining);
                    for (int index = 0; index < count; index++) {
                        chunk[index] = (byte) ((cursor + index) & 0xff);
                    }
                    output.write(chunk, 0, count);
                    remaining -= count;
                    cursor += count;
                }
                output.flush();
            } catch (Exception error) {
                if (running.get()) {
                    throw new AssertionError(error);
                }
            } finally {
                activeRequests.decrementAndGet();
            }
        }

        private static void write(Socket client, String headers, long start, long length)
                throws IOException {
            OutputStream output = client.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            releaseDelayedBodies();
            socket.close();
            handlers.shutdownNow();
            handlers.awaitTermination(2, TimeUnit.SECONDS);
            if (acceptThread != null) {
                acceptThread.join(2_000L);
            }
        }
    }
}
