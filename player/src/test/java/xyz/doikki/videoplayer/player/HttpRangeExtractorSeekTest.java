package xyz.doikki.videoplayer.player;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.NavigableMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * Regression coverage for extractor-resolved byte seeks.  These tests intentionally exercise
 * the source directly instead of the player callbacks so a header/Cue probe cannot silently
 * become the new startup runway.
 */
public class HttpRangeExtractorSeekTest {
    private static final int FILE_SIZE = 128 * 1024;
    private static final int MIDDLE = FILE_SIZE / 2;

    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;

    @After
    public void tearDown() throws Exception {
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void realHttpRangeFillsSmallFileAndReusesExactMiddleAndEofSeeks() throws Exception {
        byte[] content = testContent(FILE_SIZE);
        serverSocket = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        serverExecutor = Executors.newSingleThreadExecutor();
        Future<?> server = serverExecutor.submit(() -> serveOneRange(content));

        HttpRangeMediaDataSource source =
                HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                        "http://127.0.0.1:" + serverSocket.getLocalPort() + "/video",
                        null, null, false, null);
        byte[] actual = new byte[content.length];
        try {
            assertEquals(content.length, source.readAt(0L, actual, 0, actual.length));
            assertArrayEquals(content, actual);
            assertEquals(FILE_SIZE, source.getKnownTotalSize());
            assertTrue("the verified small entity is a complete startup runway",
                    source.isStartupBufferGateSatisfied());

            // readAt consumed the whole entity, so its live cursor is now EOF (zero remaining).
            // Compare seek targets from byte zero rather than that consumed cursor.
            source.onExtractorSeekStarted(0L);
            source.onExtractorSeekStarted(MIDDLE);
            assertEquals(MIDDLE, source.getPlaybackAnchorPositionSnapshot());
            assertTrue("a cached exact-position seek must retain the forward runway",
                    source.getBufferedAheadBytes() >= FILE_SIZE - MIDDLE);
            assertTrue("cached middle seek must satisfy its byte gate",
                    source.isStartupBufferGateSatisfied());
            assertTrue("middle target cannot exceed the remaining entity",
                    source.getPlaybackStartBufferTargetBytes() <= FILE_SIZE - MIDDLE);
            assertTrue("the middle target remains positive for the remaining entity",
                    source.getPlaybackStartBufferTargetBytes() > 0L);

            byte[] middleBytes = new byte[64];
            assertEquals(middleBytes.length,
                    source.readAt(MIDDLE, middleBytes, 0, middleBytes.length));
            byte[] expectedMiddle = new byte[middleBytes.length];
            System.arraycopy(content, MIDDLE, expectedMiddle, 0, expectedMiddle.length);
            assertArrayEquals(expectedMiddle, middleBytes);

            source.onExtractorSeekStarted(FILE_SIZE - 1024L);
            assertEquals(FILE_SIZE - 1024L, source.getPlaybackAnchorPositionSnapshot());
            assertTrue("tail seek must use the cached short remaining range",
                    source.getBufferedAheadBytes() >= 1024L);
            assertTrue("tail seek must pass without a full 48 MiB runway",
                    source.isStartupBufferGateSatisfied());
            assertTrue("tail target must be bounded by remaining bytes",
                    source.getPlaybackStartBufferTargetBytes() <= 1024L);
        } finally {
            source.close();
        }
        // The entire 128 KiB entity is served by the first (large requested) Range response.
        server.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void extractorSeekDoesNotTreatOnlyHeaderAsResumeRunway() throws Exception {
        HttpRangeMediaDataSource source =
                HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                        "https://media.example/video.mkv", null, null, false, null);
        try {
            installWindow(source, 0L, MIDDLE - 1L, FILE_SIZE);
            source.onExtractorSeekStarted(MIDDLE);

            assertEquals(MIDDLE, source.getPlaybackAnchorPositionSnapshot());
            assertFalse("header bytes before the extractor seek are not a new runway",
                    source.isStartupBufferGateSatisfied());
            assertTrue("the missing middle range must remain visible as unavailable",
                    source.getBufferedAheadBytes() < source.getPlaybackStartBufferTargetBytes());

            installWindow(source, MIDDLE, FILE_SIZE - 1L, FILE_SIZE);
            source.onExtractorSeekStarted(MIDDLE);
            assertTrue("once the exact seek range is cached the gate can complete",
                    source.isStartupBufferGateSatisfied());
            assertTrue(source.getBufferedAheadBytes() >= FILE_SIZE - MIDDLE);
        } finally {
            source.close();
        }
    }

    private void serveOneRange(byte[] content) {
        try (Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(5_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            String range = null;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                    range = line.substring(6).trim();
                }
            }
            long start = 0L;
            long requestedEnd = content.length - 1L;
            if (range != null && range.startsWith("bytes=")) {
                String[] bounds = range.substring(6).split("-", 2);
                start = Long.parseLong(bounds[0]);
                if (bounds.length > 1 && !bounds[1].isEmpty()) {
                    requestedEnd = Long.parseLong(bounds[1]);
                }
            }
            if (start < 0L || start >= content.length) {
                writeResponse(socket, "HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + content.length + "\r\n"
                        + "Connection: close\r\n\r\n", new byte[0]);
                return;
            }
            int end = (int) Math.min(content.length - 1L, Math.max(start, requestedEnd));
            byte[] body = new byte[end - (int) start + 1];
            System.arraycopy(content, (int) start, body, 0, body.length);
            String headers = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Range: bytes " + start + "-" + end + "/" + content.length + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            writeResponse(socket, headers, body);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void writeResponse(Socket socket, String headers, byte[] body)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static byte[] testContent(int size) {
        byte[] content = new byte[size];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31 + 7);
        }
        return content;
    }

    private static void installWindow(HttpRangeMediaDataSource source,
                                      long start,
                                      long end,
                                      long totalSize) throws Exception {
        Field cacheField = HttpRangeMediaDataSource.class.getDeclaredField("cachedWindows");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        NavigableMap<Long, Object> cache =
                (NavigableMap<Long, Object>) cacheField.get(source);
        Class<?> windowClass = Class.forName(
                "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$WindowData");
        Constructor<?> constructor = windowClass.getDeclaredConstructor(
                long.class, long.class, byte[].class, long.class,
                long.class, long.class, long.class, int.class);
        constructor.setAccessible(true);
        byte[] data = new byte[(int) (end - start + 1L)];
        Object window = constructor.newInstance(start, end, data, totalSize,
                start, end, end, 206);

        Field cachedBytesField = HttpRangeMediaDataSource.class.getDeclaredField("cachedBytes");
        cachedBytesField.setAccessible(true);
        Field totalSizeField = HttpRangeMediaDataSource.class.getDeclaredField("totalSize");
        totalSizeField.setAccessible(true);
        synchronized (source) {
            cache.clear();
            cache.put(start, window);
            cachedBytesField.setLong(source, data.length);
            totalSizeField.setLong(source, totalSize);
        }
    }
}
