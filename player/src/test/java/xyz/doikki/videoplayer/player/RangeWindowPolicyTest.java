package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RangeWindowPolicyTest {
    private static final long BLOCK = 2L * 1024L * 1024L;
    private static final long MAX = 4L * 1024L * 1024L;

    @Test
    public void smallStartupReadReturnsOneStreamingWindow() {
        RangeWindowPolicy.Window window = RangeWindowPolicy.resolveForegroundWindow(
                0L, 64 * 1024, BLOCK, MAX);

        assertEquals(0L, window.start);
        assertEquals(BLOCK, window.size);
    }

    @Test
    public void arbitrarySeekWindowCoversTheRequestedBytes() {
        long position = 100L * 1024L * 1024L + 250L * 1024L;
        int requested = 64 * 1024;
        RangeWindowPolicy.Window window = RangeWindowPolicy.resolveForegroundWindow(
                position, requested, BLOCK, MAX);

        assertTrue(window.start <= position);
        assertTrue(window.start + window.size >= position + requested);
        assertEquals(BLOCK, window.size);
    }

    @Test
    public void oversizedNativeReadIsCappedForIterativeLoading() {
        RangeWindowPolicy.Window window = RangeWindowPolicy.resolveForegroundWindow(
                3L * BLOCK, 8 * 1024 * 1024, BLOCK, MAX);

        assertEquals(MAX, window.size);
    }

    @Test
    public void hundredThousandAlternatingPositionsAlwaysResolveToBoundedCoveringWindows() {
        long mediaSize = 20_327_632_348L;
        int requested = 128 * 1024;
        long usableSize = mediaSize - requested;

        for (int seek = 0; seek < 100_000; seek++) {
            long position = seek % 2 == 0
                    ? ((long) seek * 13_771_337L) % usableSize
                    : usableSize - 1L - (((long) seek * 7_919_219L) % usableSize);
            RangeWindowPolicy.Window window = RangeWindowPolicy.resolveForegroundWindow(
                    position, requested, BLOCK, MAX);

            assertEquals(position - (position % BLOCK), window.start);
            assertTrue(window.start <= position);
            assertTrue(window.start + window.size >= position + requested);
            assertTrue(window.size >= BLOCK);
            assertTrue(window.size <= MAX);
        }
    }
}
