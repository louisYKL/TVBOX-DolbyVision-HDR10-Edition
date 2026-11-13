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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class HttpRangePlaybackSessionTest {
    private static final long CONTENT_LENGTH = 512L * 1024L * 1024L;
    private static final int RESPONSE_SIZE = 256 * 1024;

    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;

    @Before
    public void setUp() throws IOException {
        serverSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        serverSocket.setSoTimeout(5_000);
        serverExecutor = Executors.newCachedThreadPool();
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
    public void reopensReuseOneSourceAndHitPreviouslyDownloadedRanges() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        Future<?> server = serverExecutor.submit(() -> serveRanges(requestCount));
        String url = "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video";
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer stable");

        // Disable fill-ahead here so the test proves exactly which reads are network misses.
        HttpRangePlaybackSession session = new HttpRangePlaybackSession(false);
        HttpRangeMediaDataSource source = session.acquire(url, headers, 0L, null);
        byte[] prefix = new byte[128];
        assertEquals(prefix.length, source.readAt(0L, prefix, 0, prefix.length));
        assertEquals(0, prefix[0]);
        assertEquals(127, prefix[127] & 0xff);
        int afterPrefix = requestCount.get();

        // Read a tiny EOF index probe without declaring a seek. The source must keep the
        // original decoder anchor at byte zero while caching this separate metadata range.
        long secondRangePosition = CONTENT_LENGTH - 128L;
        HttpRangeMediaDataSource resumed = session.acquire(url, headers, secondRangePosition,
                null);
        assertSame(source, resumed);
        assertEquals(0L, resumed.getInitialDataSourcePosition());
        byte[] tail = new byte[128];
        assertEquals(tail.length,
                resumed.readAt(secondRangePosition, tail, 0, tail.length));
        assertEquals("an EOF metadata probe must not replace the decoder anchor", 0L,
                resumed.getPlaybackAnchorPositionSnapshot());
        assertEquals((byte) secondRangePosition, tail[0]);
        assertEquals((byte) (secondRangePosition + tail.length - 1L), tail[tail.length - 1]);
        int afterTail = requestCount.get();
        assertEquals("the tail must be a distinct network range", afterPrefix + 1, afterTail);

        HttpRangeMediaDataSource original = session.acquire(url, headers, 0L, null);
        assertSame(source, original);
        byte[] prefixAgain = new byte[prefix.length];
        assertEquals(prefixAgain.length,
                original.readAt(0L, prefixAgain, 0, prefixAgain.length));
        assertArrayEquals(prefix, prefixAgain);
        assertEquals("reacquiring the original range should hit the bounded cache",
                afterTail, requestCount.get());

        try {
            assertEquals("the range server should observe only the two cache misses", 2,
                    requestCount.get());
            server.get(5L, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    public void changedHeadersAndCloseNeverReuseSource() throws Exception {
        HttpRangePlaybackSession session = new HttpRangePlaybackSession();
        String url = "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video";
        Map<String, String> firstHeaders = new HashMap<>();
        firstHeaders.put("X-Token", "one");
        Map<String, String> secondHeaders = new HashMap<>();
        secondHeaders.put("X-Token", "two");

        HttpRangeMediaDataSource first = session.acquire(url, firstHeaders, 0L, null);
        HttpRangeMediaDataSource changed = session.acquire(url, secondHeaders, 0L, null);
        assertNotSame(first, changed);
        session.reset();
        HttpRangeMediaDataSource afterReset = session.acquire(url, secondHeaders, 0L, null);
        assertNotSame(changed, afterReset);
        session.close();
    }

    private void serveRanges(AtomicInteger requestCount) {
        try {
            while (!Thread.currentThread().isInterrupted() && requestCount.get() < 2) {
                Socket socket;
                try {
                    socket = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                try (Socket client = socket) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    long start = 0L;
                    long end = CONTENT_LENGTH - 1L;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                            String value = line.substring(6).trim();
                            int dash = value.indexOf('-');
                            start = Long.parseLong(value.substring(value.indexOf('=') + 1, dash));
                            String endText = value.substring(dash + 1);
                            if (!endText.isEmpty()) {
                                end = Math.min(end, Long.parseLong(endText));
                            }
                        }
                    }
                    if (start >= CONTENT_LENGTH) {
                        continue;
                    }
                    end = Math.min(end, CONTENT_LENGTH - 1L);
                    long requestedLength = end - start + 1L;
                    int length = (int) Math.min(requestedLength, RESPONSE_SIZE);
                    end = start + length - 1L;
                    byte[] body = new byte[length];
                    for (int i = 0; i < length; i++) {
                        body[i] = (byte) ((start + i) & 0xff);
                    }
                    requestCount.incrementAndGet();
                    OutputStream output = client.getOutputStream();
                    String response = "HTTP/1.1 206 Partial Content\r\n"
                            + "Content-Range: bytes " + start + "-" + end + "/" + CONTENT_LENGTH + "\r\n"
                            + "Content-Length: " + length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    output.write(response.getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    output.flush();
                }
            }
        } catch (IOException ignored) {
            // ServerSocket is closed by tearDown after the client has finished.
        }
    }
}
