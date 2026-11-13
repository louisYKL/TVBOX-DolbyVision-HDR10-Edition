package xyz.doikki.videoplayer.player;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Scaled real-source tests exercise the allocation boundary without allocating large videos. */
public class HttpRangeProcessBudgetTest {
    private static final long KIB = 1024L;
    private static final long WINDOW = 24L * KIB;

    @Test
    public void realSourceAdmitsTheFourthStartupWindowUnderRecoverablePressure() throws Exception {
        Heap heap = new Heap();
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * KIB, 96L * KIB, heap);
        HttpRangeMediaDataSource source = source(budget);
        Object reservation = null;
        try {
            seedThreeWindows(source);
            heap.used = 400L * KIB;
            reservation = reserve(source, WINDOW);
            assertNotNull("startup must not remain at 72/96 MiB behind a 96 MiB headroom guard",
                    reservation);
            assertNull(source.getPlaybackCacheError());
            assertEquals(96L * KIB, budget.chargedBytes());
            source.close();
            assertEquals("close cannot refund the response still owned by a worker",
                    WINDOW, budget.chargedBytes());
            release(source, reservation);
            reservation = null;
            assertEquals(0L, budget.chargedBytes());
        } finally {
            if (reservation != null) release(source, reservation);
            source.close();
        }
    }

    @Test
    public void realSourceReportsAnImpossibleRunwayInsteadOfSchedulingInfiniteRetries() throws Exception {
        Heap heap = new Heap();
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * KIB, 96L * KIB, heap);
        HttpRangeMediaDataSource source = source(budget);
        try {
            seedThreeWindows(source);
            heap.used = 480L * KIB;
            assertNull(reserve(source, WINDOW));
            assertTrue(source.getPlaybackCacheError()
                    instanceof HttpRangeMediaDataSource.RangeMemoryBudgetException);
            assertFalse(field("budgetRetryScheduled").getBoolean(source));
            try {
                source.readAt(72L * KIB, new byte[1], 0, 1);
                throw new AssertionError("an impossible admission must yield a recoverable error");
            } catch (HttpRangeMediaDataSource.RangeMemoryBudgetException expected) {
                assertTrue(expected.getMessage().contains("heap"));
            }
            assertEquals(72L * KIB, budget.chargedBytes());
        } finally {
            source.close();
        }
        assertEquals(0L, budget.chargedBytes());
    }

    @Test
    public void knownTailReservesOnlyItsRemainingPayload() throws Exception {
        Heap heap = new Heap();
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * KIB, 96L * KIB, heap);
        HttpRangeMediaDataSource source = source(budget);
        Object reservation = null;
        try {
            setLong(source, "totalSize", 73L * KIB);
            setLong(source, "startupBufferAnchorPosition", 72L * KIB);
            setLong(source, "playbackAnchorPosition", 72L * KIB);
            heap.used = 462L * KIB; // 50 KiB free: enough for a 1 KiB tail plus 48 KiB headroom.
            Method remaining = HttpRangeMediaDataSource.class.getDeclaredMethod(
                    "reservationForRange", long.class, long.class);
            remaining.setAccessible(true);
            long actual = (Long) remaining.invoke(source, 72L * KIB, WINDOW);
            assertEquals(KIB, actual);
            reservation = reserve(source, actual);
            assertNotNull(reservation);
            assertEquals(KIB, budget.chargedBytes());
            assertNull(source.getPlaybackCacheError());
        } finally {
            if (reservation != null) release(source, reservation);
            source.close();
        }
    }

    @Test
    public void anotherPausedSourceProducesARecoverableConflictAndCanReturnItsBudget() throws Exception {
        Heap heap = new Heap();
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * KIB, 96L * KIB, heap);
        RawRangeCacheBudget.Account paused = budget.openAccount();
        assertTrue(paused.tryResize(120L * KIB, 0L));
        HttpRangeMediaDataSource source = source(budget);
        try {
            seedThreeWindows(source);
            assertNull(reserve(source, WINDOW));
            assertNotNull(source.getPlaybackCacheError());
            assertFalse(field("budgetRetryScheduled").getBoolean(source));
            source.onPlaybackSeekStarted();
            assertNull("a user retry/seek clears the old resource error", source.getPlaybackCacheError());
        } finally {
            source.close();
            assertTrue(paused.tryResize(0L, 0L));
        }
        assertEquals(0L, budget.chargedBytes());
    }

    private static HttpRangeMediaDataSource source(RawRangeCacheBudget budget) throws Exception {
        HttpRangeMediaDataSource source = HttpRangeMediaDataSource
                .createForSystemStreamingPlayback("https://media.invalid/video.mkv", null,
                        null, false, null);
        field("rawBudget").set(source, budget.openAccount());
        field("prefetchEnabled").setBoolean(source, true);
        setLong(source, "startBufferBytes", 96L * KIB);
        setLong(source, "cacheLimitBytes", 192L * KIB);
        setLong(source, "prefetchWindowSize", WINDOW);
        setLong(source, "defaultWindowSize", WINDOW);
        setLong(source, "maxWindowSize", WINDOW);
        setLong(source, "totalSize", 1024L * KIB);
        setLong(source, "startupBufferAnchorPosition", 0L);
        setLong(source, "playbackAnchorPosition", 0L);
        return source;
    }

    private static void seedThreeWindows(HttpRangeMediaDataSource source) throws Exception {
        Class<?> window = Class.forName(HttpRangeMediaDataSource.class.getName() + "$WindowData");
        Constructor<?> constructor = window.getDeclaredConstructor(long.class, long.class,
                byte[].class, long.class, long.class, long.class, long.class, int.class);
        constructor.setAccessible(true);
        Method store = HttpRangeMediaDataSource.class.getDeclaredMethod("storeWindowLocked", window);
        store.setAccessible(true);
        synchronized (source) {
            for (int i = 0; i < 3; i++) {
                long start = i * WINDOW;
                long end = start + WINDOW - 1L;
                store.invoke(source, constructor.newInstance(start, end, new byte[(int) WINDOW],
                        1024L * KIB, start, end, end, 206));
            }
        }
    }

    private static Object reserve(HttpRangeMediaDataSource source, long bytes) throws Exception {
        Method method = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "reservePrefetchLocked", long.class, long.class);
        method.setAccessible(true);
        synchronized (source) {
            return method.invoke(source, 99L, bytes);
        }
    }

    private static void release(HttpRangeMediaDataSource source, Object reservation) throws Exception {
        Method method = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "releasePrefetchReservationLocked", reservation.getClass());
        method.setAccessible(true);
        synchronized (source) {
            method.invoke(source, reservation);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setLong(HttpRangeMediaDataSource source, String name, long value)
            throws Exception {
        field(name).setLong(source, value);
    }

    private static final class Heap implements RawRangeCacheBudget.HeapState {
        volatile long used = 32L * KIB;
        @Override public long maxBytes() { return 512L * KIB; }
        @Override public long usedBytes() { return used; }
    }
}
