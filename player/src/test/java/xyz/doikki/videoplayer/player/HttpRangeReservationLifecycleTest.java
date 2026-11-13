package xyz.doikki.videoplayer.player;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Deterministic cancellation interleavings for the actual Range worker lifecycle. */
public class HttpRangeReservationLifecycleTest {
    private static final long WINDOW = 1_024L;

    @Test
    public void cancellingAQueuedSiblingStillRunsItsReservationCleanup() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        HttpRangeMediaDataSource source = queuedSibling(executor, "https://media.example/video");
        try {
            assertEquals(WINDOW, getLong(source, "reservedPrefetchBytes"));
            source.onPlaybackSeekStarted();
            assertEquals("cancellation does not release a worker's token prematurely",
                    WINDOW, getLong(source, "reservedPrefetchBytes"));
            assertEquals(1, executor.tasks.size());
            executor.runNext();
            assertEquals("a queued cancelled worker must still execute its finally cleanup",
                    0L, getLong(source, "reservedPrefetchBytes"));
            assertTrue(reservations(source).isEmpty());
            assertEquals("no cancelled response may commit", 0L, source.getCachedBytesSnapshot());
        } finally {
            source.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellationDuringBodyReadRetainsItsTokenUntilWorkerExit() throws Exception {
        assertCancelledResponseRetainsReservation(false);
    }

    @Test
    public void completedResponseWaitingForCommitStillRetainsItsToken() throws Exception {
        assertCancelledResponseRetainsReservation(true);
    }

    @Test
    public void closeDropsCommittedArraysWhileForegroundBodyReadIsStillBlocked() throws Exception {
        CountDownLatch bodyRead = new CountDownLatch(1);
        CountDownLatch releaseBodyRead = new CountDownLatch(1);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        ManualExecutor fills = new ManualExecutor();
        try (RangeServer server = new RangeServer(4L * WINDOW, WINDOW, 8L * WINDOW)) {
            HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                    .createForSystemStreamingPlayback(server.url(), null, bytes -> {
                        bodyRead.countDown();
                        try {
                            if (!releaseBodyRead.await(5L, TimeUnit.SECONDS)) {
                                throw new AssertionError("foreground body barrier timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }, false, null);
            try {
                configureSmallSource(source, fills);
                setLong(source, "cacheLimitBytes", 8L * WINDOW);
                Object cached = window(0L, (int) WINDOW, 8L * WINDOW);
                Method store = HttpRangeMediaDataSource.class.getDeclaredMethod(
                        "storeWindowLocked", cached.getClass());
                store.setAccessible(true);
                synchronized (source) {
                    store.invoke(source, cached);
                    field("containerMetadataWindow").set(source,
                            window(7L * WINDOW, 256, 8L * WINDOW));
                    setLong(source, "containerMetadataReservationBytes", 256L);
                    setLong(source, "foregroundReservationBytes", 256L);
                }
                Future<Throwable> read = reader.submit(() -> {
                    try {
                        source.readAt(4L * WINDOW, new byte[1], 0, 1);
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                assertTrue(bodyRead.await(5L, TimeUnit.SECONDS));
                assertEquals(WINDOW, source.getCachedBytesSnapshot());
                assertEquals(WINDOW + 256L, getLong(source, "foregroundReservationBytes"));

                // The reader holds blockingReadLock and its admitted response array here.
                // close must nevertheless release every committed cache/metadata reference.
                source.close();
                assertFalse("close must not wait for the network reader", read.isDone());
                assertEquals(0L, source.getCachedBytesSnapshot());
                assertEquals(0L, getLong(source, "cachedBytes"));
                assertTrue(((Map<?, ?>) field("cachedWindows").get(source)).isEmpty());
                assertNull(field("containerMetadataWindow").get(source));
                assertNull(field("rangeTransferListener").get(source));
                assertEquals(0L, getLong(source, "containerMetadataReservationBytes"));
                assertEquals("the blocked body stays charged until the reader exits",
                        WINDOW, getLong(source, "foregroundReservationBytes"));
                assertEquals(0L, source.getBufferedAheadBytes());
                assertEquals(0L, source.getPlaybackStartBufferTargetBytes());

                releaseBodyRead.countDown();
                assertTrue("closed foreground data must never commit", read.get(5L,
                        TimeUnit.SECONDS) instanceof IOException);
                assertEquals(0L, getLong(source, "foregroundReservationBytes"));
                assertEquals(0L, source.getCachedBytesSnapshot());
                server.await();
            } finally {
                releaseBodyRead.countDown();
                source.close();
                fills.shutdownNow();
            }
        } finally {
            reader.shutdownNow();
            reader.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @Test
    public void closeRetainsQueuedWorkerReservationUntilItsFinallyRuns() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        HttpRangeMediaDataSource source = queuedSibling(executor, "https://media.example/video");
        try {
            source.close();
            assertEquals("close cannot free memory still owned by the queued worker",
                    WINDOW, getLong(source, "reservedPrefetchBytes"));
            executor.runNext();
            assertEquals(0L, getLong(source, "reservedPrefetchBytes"));
            assertTrue(reservations(source).isEmpty());
            assertEquals(0L, source.getCachedBytesSnapshot());
        } finally {
            source.close();
            executor.shutdownNow();
        }
    }

    private void assertCancelledResponseRetainsReservation(boolean completeBeforeCancel)
            throws Exception {
        CountDownLatch receivedBody = new CountDownLatch(1);
        CountDownLatch allowBodyReturn = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reservation-test-worker");
            worker.set(thread);
            return thread;
        });
        try (RangeServer server = new RangeServer(0L, WINDOW, WINDOW)) {
            HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                    .createForSystemStreamingPlayback(server.url(), null, bytes -> {
                        receivedBody.countDown();
                        try {
                            if (!allowBodyReturn.await(5L, TimeUnit.SECONDS)) {
                                throw new AssertionError("body barrier timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }, true, null);
            try {
                configureSmallSource(source, executor);
                source.requestPlaybackPrebuffer();
                assertTrue(receivedBody.await(5L, TimeUnit.SECONDS));
                synchronized (source) {
                    if (completeBeforeCancel) {
                        allowBodyReturn.countDown();
                        awaitThreadState(worker.get(), Thread.State.BLOCKED);
                    }
                    source.onPlaybackSeekStarted();
                    assertEquals("cancelled response still owns its allocated window",
                            WINDOW, getLong(source, "reservedPrefetchBytes"));
                    assertFalse("a replacement window must wait for the old response to unwind",
                            admit(source, WINDOW));
                }
                allowBodyReturn.countDown();
                awaitReservationsReleased(source);
                assertEquals("a cancelled response cannot enter the cache",
                        0L, source.getCachedBytesSnapshot());
                assertTrue(reservations(source).isEmpty());
                server.await();
            } finally {
                allowBodyReturn.countDown();
                source.close();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @Test
    public void foregroundReadWaitsForCancelledWorkerWithoutLosingTheRequest() throws Exception {
        assertForegroundReadSurvivesCancelledWorker(false);
    }

    @Test
    public void foregroundReadReusesRefillScheduledByCancelledWorker() throws Exception {
        assertForegroundReadSurvivesCancelledWorker(true);
    }

    private void assertForegroundReadSurvivesCancelledWorker(boolean keepPrefetchEnabled)
            throws Exception {
        ManualExecutor executor = new ManualExecutor();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> readingThread = new AtomicReference<>();
        try (RangeServer server = new RangeServer(4L * WINDOW, WINDOW, 8L * WINDOW)) {
            HttpRangeMediaDataSource source = queuedSibling(executor, server.url());
            try {
                source.onPlaybackSeekStarted();
                source.setPlaybackFillAheadEnabled(keepPrefetchEnabled);
                source.onExtractorSeekStarted(4L * WINDOW);
                Future<Integer> read = reader.submit(() -> {
                    readingThread.set(Thread.currentThread());
                    return source.readAt(4L * WINDOW, new byte[1], 0, 1);
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
                while (readingThread.get() == null && System.nanoTime() < deadline) {
                    Thread.sleep(5L);
                }
                awaitThreadState(readingThread.get(), Thread.State.WAITING);
                assertEquals("the foreground must not allocate while a cancelled token fills the budget",
                        0, server.requests.get());
                synchronized (source) {
                    executor.runNext();
                    if (keepPrefetchEnabled) {
                        // Keep the foreground waiter outside the monitor while the cancelled
                        // worker's finally schedules and completes a replacement fill. Its
                        // response must be reused even though it now occupies the full budget.
                        assertEquals(1, executor.tasks.size());
                        executor.runNext();
                        assertEquals(WINDOW, source.getCachedBytesSnapshot());
                    }
                }
                assertEquals("cleanup must wake and preserve the foreground read",
                        1, (int) read.get(5L, TimeUnit.SECONDS));
                assertEquals(1, server.requests.get());
                assertEquals(0L, getLong(source, "reservedPrefetchBytes"));
                assertTrue(source.getCachedBytesSnapshot() <= WINDOW);
                server.await();
            } finally {
                source.close();
                executor.shutdownNow();
            }
        } finally {
            reader.shutdownNow();
            reader.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    private static HttpRangeMediaDataSource queuedSibling(ManualExecutor executor, String url)
            throws Exception {
        // Use a direct origin identity for sibling scheduling. The queued task is cancelled
        // before execution, so it never touches this URL or the supplied response server.
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback(url.replace("/proxy/play/", "/direct/"),
                        null, null, true, null);
        configureSmallSource(source, executor);
        setLong(source, "totalSize", 8L * WINDOW);
        setLong(source, "cacheLimitBytes", 2L * WINDOW);
        setLong(source, "playbackAnchorPosition", 0L);
        setLong(source, "inFlightPrefetchStart", 0L);
        setLong(source, "inFlightPrefetchEnd", WINDOW - 1L);
        setLong(source, "activePrefetchId", 1L);
        setLong(source, "nextPrefetchId", 1L);
        field("hasLoadedWindow").setBoolean(source, true);
        Method schedule = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "scheduleSiblingPrefetchLocked", long.class);
        schedule.setAccessible(true);
        synchronized (source) {
            schedule.invoke(source, 0L);
        }
        setLong(source, "cacheLimitBytes", WINDOW);
        return source;
    }

    private static void configureSmallSource(HttpRangeMediaDataSource source,
                                              ExecutorService executor) throws Exception {
        field("prefetchExecutor").set(source, executor);
        setLong(source, "prefetchWindowSize", WINDOW);
        setLong(source, "defaultWindowSize", WINDOW);
        setLong(source, "maxWindowSize", WINDOW);
        setLong(source, "cacheLimitBytes", WINDOW);
    }

    private static boolean admit(HttpRangeMediaDataSource source, long bytes) throws Exception {
        Method method = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "makeCapacityForNextWindowLocked", long.class, long.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(source, 0L, bytes);
    }

    private static void awaitThreadState(Thread thread, Thread.State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (thread != null && thread.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue("worker did not reach " + expected, thread != null && thread.getState() == expected);
    }

    private static void awaitReservationsReleased(HttpRangeMediaDataSource source) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            synchronized (source) {
                if (getLong(source, "reservedPrefetchBytes") == 0L) {
                    return;
                }
            }
            Thread.sleep(5L);
        }
        assertEquals("worker did not release its reservation", 0L,
                getLong(source, "reservedPrefetchBytes"));
    }

    private static Field field(String name) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object window(long start, int length, long total) throws Exception {
        Class<?> type = Class.forName(HttpRangeMediaDataSource.class.getName() + "$WindowData");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, long.class,
                byte[].class, long.class, long.class, long.class, long.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(start, start + length - 1L, new byte[length], total,
                start, start + length - 1L, start + length - 1L, 206);
    }

    private static long getLong(Object source, String name) throws Exception {
        return field(name).getLong(source);
    }

    private static void setLong(Object source, String name, long value) throws Exception {
        field(name).setLong(source, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> reservations(HttpRangeMediaDataSource source) throws Exception {
        return (Map<?, ?>) field("prefetchReservations").get(source);
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown;

        @Override public void execute(Runnable task) { tasks.add(task); }
        void runNext() { tasks.remove().run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; tasks.clear(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { return isTerminated(); }
    }

    private static final class RangeServer implements AutoCloseable {
        final ServerSocket socket;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> response;
        final AtomicInteger requests = new AtomicInteger();

        RangeServer(long expectedStart, long length, long total) throws Exception {
            socket = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(5_000);
            response = executor.submit(() -> {
                try (Socket client = socket.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.US_ASCII));
                    String range = null;
                    for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                        if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                            range = line.substring(6).trim();
                        }
                    }
                    assertEquals("bytes=" + expectedStart + "-" + (expectedStart + length - 1L), range);
                    requests.incrementAndGet();
                    OutputStream output = client.getOutputStream();
                    String headers = "HTTP/1.1 206 Partial Content\r\n"
                            + "Content-Range: bytes " + expectedStart + "-" + (expectedStart + length - 1L)
                            + "/" + total + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n";
                    output.write(headers.getBytes(StandardCharsets.US_ASCII));
                    output.write(new byte[(int) length]);
                    output.flush();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }

        String url() { return "http://127.0.0.1:" + socket.getLocalPort() + "/proxy/play/video"; }
        void await() throws Exception { response.get(5L, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            socket.close();
            response.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }
}
