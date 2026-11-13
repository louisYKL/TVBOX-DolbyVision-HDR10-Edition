package xyz.doikki.videoplayer.player;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpRangePayloadReadTest {
    @Test
    public void fullProductionWindowUsesBoundedSegmentsAndCopiesAcrossBoundaries()
            throws Exception {
        int requested = 24 * 1024 * 1024;
        PatternInputStream stream = new PatternInputStream(requested);
        HttpRangeMediaDataSource.ReadResult payload =
                HttpRangeMediaDataSource.readUpTo(stream, requested, null);

        assertEquals(requested, payload.length);
        assertEquals(requested, payload.capacityBytes);
        assertEquals(384, payload.segments.length);
        assertTrue("the HTTP read must never allocate a complete 24 MiB array",
                stream.largestTarget <= 64 * 1024);
        for (int offset : new int[]{0, 65_530, 1_048_570, requested - 67}) {
            byte[] copy = new byte[67];
            payload.copyTo(offset, copy, 0, copy.length);
            for (int i = 0; i < copy.length; i++) {
                assertEquals((byte) (offset + i), copy[i]);
            }
        }
    }

    @Test
    public void nearFullProductionResponseDoesNotDuplicateItsFinalSegment() throws Exception {
        int requested = 24 * 1024 * 1024;
        PatternInputStream stream = new PatternInputStream(requested - 8);
        HttpRangeMediaDataSource.ReadResult payload =
                HttpRangeMediaDataSource.readUpTo(stream, requested, null);

        assertEquals(requested - 8, payload.length);
        assertEquals("padding is still charged inside the existing reservation",
                requested, payload.capacityBytes);
        assertSame("the final near-full response array must not be copied",
                stream.lastPayloadTarget, payload.segments[payload.segments.length - 1]);
        byte[] copy = new byte[9];
        payload.copyTo(payload.length - copy.length, copy, 0, copy.length);
        for (int i = 0; i < copy.length; i++) {
            assertEquals((byte) (payload.length - copy.length + i), copy[i]);
        }
        try {
            payload.copyTo(payload.length - 1, copy, 0, 2);
            fail("reserved padding is not valid video data");
        } catch (IndexOutOfBoundsException expected) {
        }
    }

    @Test
    public void tinyCleanPrefixesDoNotRetainAFullRangeOrSegment() throws Exception {
        int requested = 24 * 1024 * 1024;
        for (int length : new int[]{1, 1_024, 65_535, 65_536, 65_537, 1_048_577}) {
            PatternInputStream stream = new PatternInputStream(length);
            HttpRangeMediaDataSource.ReadResult payload =
                    HttpRangeMediaDataSource.readUpTo(stream, requested, null);
            assertEquals(length, payload.length);
            assertEquals(length, payload.capacityBytes);
            assertTrue(stream.largestTarget <= 64 * 1024);
            byte[] last = new byte[1];
            payload.copyTo(length - 1, last, 0, 1);
            assertEquals((byte) (length - 1), last[0]);
        }
    }

    @Test
    public void transportFailureAfterSeveralSegmentsDoesNotPublishAPrefix() throws Exception {
        PatternInputStream stream = new PatternInputStream(2 * 64 * 1024) {
            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (position >= 2 * 64 * 1024) {
                    throw new IOException("broken body after two segments");
                }
                return super.read(target, offset, length);
            }
        };
        try {
            HttpRangeMediaDataSource.readUpTo(stream, 24 * 1024 * 1024, null);
            fail("partial transport failure must retain the Range retry path");
        } catch (IOException expected) {
            assertEquals("broken body after two segments", expected.getMessage());
        }
    }

    private static class PatternInputStream extends InputStream {
        private final int length;
        int position;
        int largestTarget;
        byte[] lastPayloadTarget;

        PatternInputStream(int length) {
            this.length = length;
        }

        @Override
        public int read() {
            return position < length ? (position++ & 0xff) : -1;
        }

        @Override
        public int read(byte[] target, int offset, int requested) throws IOException {
            largestTarget = Math.max(largestTarget, target.length);
            if (position >= length) {
                return -1;
            }
            int count = Math.min(requested, length - position);
            for (int i = 0; i < count; i++) {
                target[offset + i] = (byte) position++;
            }
            lastPayloadTarget = target;
            return count;
        }
    }

    @Test
    public void nearFullShortBodyRetainsTheAdmittedArrayInsteadOfCopyingIt() throws Exception {
        byte[] expected = new byte[32 * 1024 - 8];
        Arrays.fill(expected, (byte) 0x51);
        byte[][] admitted = new byte[1][];
        ByteArrayInputStream stream = new ByteArrayInputStream(expected) {
            @Override
            public synchronized int read(byte[] data, int offset, int length) {
                admitted[0] = data;
                return super.read(data, offset, length);
            }
        };

        HttpRangeMediaDataSource.ReadResult payload =
                HttpRangeMediaDataSource.readUpTo(stream, 32 * 1024, null);

        assertSame("the proxy's near-full clean EOF must not duplicate its response array",
                admitted[0], payload.data);
        assertEquals(32 * 1024, payload.data.length);
        assertEquals(expected.length, payload.length);
        assertArrayEquals(expected, Arrays.copyOf(payload.data, payload.length));
    }

    @Test
    public void allocatedCapacityCountsAgainstBudgetButPaddingIsNeitherProgressNorVideo()
            throws Exception {
        byte[] expected = new byte[32 * 1024 - 8];
        Arrays.fill(expected, (byte) 0x51);
        HttpRangeMediaDataSource.ReadResult payload = HttpRangeMediaDataSource.readUpTo(
                new ByteArrayInputStream(expected), 32 * 1024, null);
        HttpRangeMediaDataSource source = newSource();
        try {
            installAtStart(source, payload);

            assertEquals(payload.data.length, source.getCachedBytes());
            assertEquals(expected.length, source.getBufferedAheadBytes());
            assertEquals(expected.length, source.getPlaybackStartBufferTargetBytes());

            Field limitField = HttpRangeMediaDataSource.class.getDeclaredField("cacheLimitBytes");
            limitField.setAccessible(true);
            long limit = limitField.getLong(source);
            Method admission = HttpRangeMediaDataSource.class.getDeclaredMethod(
                    "makeCapacityForNextWindowLocked", long.class, long.class);
            admission.setAccessible(true);
            synchronized (source) {
                assertFalse("unused backing capacity is still live memory", (Boolean)
                        admission.invoke(source, 0L, limit - payload.data.length + 1L));
            }

            byte[] actual = new byte[expected.length + 12];
            Arrays.fill(actual, (byte) 0x7f);
            assertEquals(expected.length, source.readAt(0L, actual, 0, actual.length));
            assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
            assertEquals("zero padding must not overwrite the caller's remaining bytes",
                    0x7f, actual[expected.length]);
            assertEquals(-1, source.readAt(expected.length, actual, 0, actual.length));
        } finally {
            source.close();
        }
    }

    @Test
    public void substantiallyShortBodiesCanFillAContinuousStartupRunway() throws Exception {
        // Scale the 96/240 MiB policy to KiB so repeated very short responses exercise the
        // same admission/accounting decisions without a large-memory test fixture.
        final int requested = 48 * 1024;
        final int shortLength = 1024;
        final long startup = 96 * 1024L;
        HttpRangeMediaDataSource source = newSource();
        setLong(source, "startBufferBytes", startup);
        setLong(source, "cacheLimitBytes", 240 * 1024L);
        source.onExtractorSeekStarted(0L);
        Method admission = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "makeCapacityForNextWindowLocked", long.class, long.class);
        admission.setAccessible(true);
        try {
            for (long position = 0L; position < startup; position += shortLength) {
                synchronized (source) {
                    assertTrue("short responses must leave capacity for the next valid prefix",
                            (Boolean) admission.invoke(source, 0L, (long) requested));
                }
                byte[][] admitted = new byte[1][];
                InputStream stream = new ByteArrayInputStream(new byte[shortLength]) {
                    @Override
                    public synchronized int read(byte[] data, int offset, int length) {
                        admitted[0] = data;
                        return super.read(data, offset, length);
                    }
                };
                HttpRangeMediaDataSource.ReadResult payload =
                        HttpRangeMediaDataSource.readUpTo(stream, requested, null);
                assertNotSame(admitted[0], payload.data);
                assertEquals("large unused capacities must not accumulate", shortLength,
                        payload.data.length);
                install(source, position, startup * 4L, payload);
                assertEquals(position + shortLength, source.getBufferedAheadBytes());
                assertEquals(position + shortLength, source.getCachedBytes());
            }
            assertEquals(startup, source.getPlaybackStartBufferTargetBytes());
            assertTrue("valid contiguous bytes must reach the startup gate",
                    source.isStartupBufferGateSatisfied());
        } finally {
            source.close();
        }
    }

    @Test
    public void metadataReadsAlsoStopAtActualEntityEnd() throws Exception {
        byte[] expected = {0x61, 0x62, 0x63};
        HttpRangeMediaDataSource.ReadResult payload = HttpRangeMediaDataSource.readUpTo(
                new ByteArrayInputStream(expected), 4096, null);
        HttpRangeMediaDataSource source = newSource();
        try {
            installAtStart(source, payload);
            source.setContainerMetadataMode(true);

            byte[] actual = new byte[12];
            Arrays.fill(actual, (byte) 0x7f);
            assertEquals(expected.length, source.readAt(0L, actual, 0, actual.length));
            assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
            assertEquals(0x7f, actual[expected.length]);
            assertEquals(-1, source.readAt(expected.length, actual, 0, actual.length));
        } finally {
            source.close();
        }
    }

    @Test
    public void transportErrorDoesNotPublishItsPartialBodyAsCleanEof() throws Exception {
        InputStream stream = new InputStream() {
            private boolean first = true;

            @Override
            public int read() throws IOException {
                throw new IOException("transport failed");
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (first) {
                    first = false;
                    target[offset] = 0x31;
                    return 1;
                }
                throw new IOException("transport failed");
            }
        };
        try {
            HttpRangeMediaDataSource.readUpTo(stream, 4096, null);
            fail("a transport exception must retain the retry path");
        } catch (IOException expected) {
            assertEquals("transport failed", expected.getMessage());
        }
    }

    private static HttpRangeMediaDataSource newSource() {
        return HttpRangeMediaDataSource.createForSystemStreamingPlayback(
                "https://media.example/short.mkv", null, null, false, null);
    }

    private static void installAtStart(HttpRangeMediaDataSource source,
                                       HttpRangeMediaDataSource.ReadResult payload)
            throws Exception {
        source.onExtractorSeekStarted(0L);
        install(source, 0L, payload.length, payload);
    }

    private static void install(HttpRangeMediaDataSource source, long start, long total,
                                HttpRangeMediaDataSource.ReadResult payload) throws Exception {
        Class<?> windowClass = Class.forName(
                "xyz.doikki.videoplayer.player.HttpRangeMediaDataSource$WindowData");
        Constructor<?> constructor = windowClass.getDeclaredConstructor(
                long.class, long.class, byte[].class, int.class, long.class,
                long.class, long.class, long.class, int.class);
        constructor.setAccessible(true);
        Object window = constructor.newInstance(start, start + payload.length - 1L,
                payload.data, payload.length, total,
                start, start + payload.length - 1L, start + payload.data.length - 1L, 206);
        Method store = HttpRangeMediaDataSource.class.getDeclaredMethod(
                "storeWindowLocked", windowClass);
        store.setAccessible(true);
        synchronized (source) {
            store.invoke(source, window);
        }
    }

    private static void setLong(HttpRangeMediaDataSource source, String name, long value)
            throws Exception {
        Field field = HttpRangeMediaDataSource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(source, value);
    }
}
