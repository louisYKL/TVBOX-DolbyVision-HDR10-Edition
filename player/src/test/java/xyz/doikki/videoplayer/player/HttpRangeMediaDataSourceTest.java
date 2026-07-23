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
    public void startupPrebufferWaitsForTheContiguousRemainingShortFile() throws Exception {
        int length = 3 * 1024 * 1024;
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch firstRangeOpened = new CountDownLatch(1);
        CountDownLatch releaseFirstRange = new CountDownLatch(1);
        Future<?> server = serverExecutor.submit(() -> {
            try {
                serveSizedRangeResponse(requestCount, length, firstRangeOpened, releaseFirstRange);
                serveSizedRangeResponse(requestCount, length);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });

        HttpRangeMediaDataSource source = HttpRangeMediaDataSource.createForStreamingPlayback(
                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video", null);
        try {
            assertTrue(source.beginStartupPrebuffer());
            assertTrue(firstRangeOpened.await(5L, TimeUnit.SECONDS));
            assertFalse(source.isStartupPrebufferReady());
            releaseFirstRange.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (!source.isStartupPrebufferReady() && System.nanoTime() < deadline) {
                assertFalse(source.hasStartupPrebufferFailed());
                Thread.sleep(10L);
            }
            assertTrue(source.isStartupPrebufferReady());
            assertEquals(length, source.getPlaybackStartBufferTargetBytes());
            assertTrue(source.getBufferedAheadBytes() >= length);
        } finally {
            source.close();
        }
        server.get(5, TimeUnit.SECONDS);
        assertEquals(2, requestCount.get());
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

    private void serveSizedRangeResponse(AtomicInteger requestCount, int totalLength) throws IOException {
        serveSizedRangeResponse(requestCount, totalLength, null, null);
    }

    private void serveSizedRangeResponse(AtomicInteger requestCount,
                                         int totalLength,
                                         CountDownLatch requestOpened,
                                         CountDownLatch releaseResponse) throws IOException {
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
            assertTrue(range != null && range.startsWith("bytes="));
            String value = range.substring("bytes=".length());
            int dash = value.indexOf('-');
            long start = Long.parseLong(dash < 0 ? value : value.substring(0, dash));
            long requestedEnd = dash < 0 || dash == value.length() - 1
                    ? totalLength - 1L : Long.parseLong(value.substring(dash + 1));
            long end = Math.min(totalLength - 1L, requestedEnd);
            assertTrue(start >= 0L && start <= end);
            long payloadLength = end - start + 1L;
            requestCount.incrementAndGet();
            if (requestOpened != null) {
                requestOpened.countDown();
            }
            if (releaseResponse != null) {
                try {
                    if (!releaseResponse.await(5L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release range response");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to release range response", interrupted);
                }
            }

            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes " + start + "-" + end + "/" + totalLength + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Content-Length: " + payloadLength + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[16 * 1024];
            for (long remaining = payloadLength; remaining > 0L; ) {
                int length = (int) Math.min(chunk.length, remaining);
                output.write(chunk, 0, length);
                remaining -= length;
            }
            output.flush();
        }
    }
}
