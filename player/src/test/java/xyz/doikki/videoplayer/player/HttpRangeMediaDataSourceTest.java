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
