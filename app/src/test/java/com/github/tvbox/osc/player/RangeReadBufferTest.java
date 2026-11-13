package com.github.tvbox.osc.player;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import static org.junit.Assert.*;

public class RangeReadBufferTest {
    @Test
    public void actualExtractorInputSmallReadsUseTheCoalescedReader() throws Exception {
        RangeReadBuffer buffer = new RangeReadBuffer();
        AtomicInteger calls = new AtomicInteger();
        RangeReadBuffer.Reader origin = (position, data, offset, length) -> {
            calls.incrementAndGet();
            for (int i = 0; i < length; i++) data[offset + i] = (byte) (position + i);
            return length;
        };
        long[] cursor = {0L};
        DefaultExtractorInput input = new DefaultExtractorInput((data, offset, length) -> {
            int count = buffer.read(origin, cursor[0], data, offset, length, -1L);
            if (count > 0) cursor[0] += count;
            return count;
        }, 0L, 1024L * 1024L);
        byte[] single = new byte[1];
        for (int i = 0; i < 1024 * 1024; i++) {
            input.readFully(single, 0, 1);
            assertEquals((byte) i, single[0]);
        }
        assertEquals(64, calls.get());
    }

    @Test
    public void oneMegabyteOfSingleByteCuesUsesOnlySixtyFourSourceReads() throws Exception {
        RangeReadBuffer buffer = new RangeReadBuffer();
        AtomicInteger calls = new AtomicInteger();
        RangeReadBuffer.Reader reader = (position, target, offset, length) -> {
            calls.incrementAndGet();
            for (int i = 0; i < length; i++) target[offset + i] = (byte) (position + i);
            return length;
        };
        byte[] single = new byte[1];
        long tail = 88L * 1024 * 1024 * 1024;
        for (int i = 0; i < 1024 * 1024; i++) {
            assertEquals(1, buffer.read(reader, tail + i, single, 0, 1, -1L));
            assertEquals((byte) (tail + i), single[0]);
        }
        assertEquals(64, calls.get());
    }

    @Test
    public void smallBoundedRequestDoesNotReadPastItsDataSpecAndResetDropsOldMedia() throws Exception {
        RangeReadBuffer buffer = new RangeReadBuffer();
        AtomicInteger calls = new AtomicInteger();
        RangeReadBuffer.Reader reader = (position, target, offset, length) -> {
            calls.incrementAndGet();
            assertEquals(3, length);
            for (int i = 0; i < length; i++) target[offset + i] = (byte) calls.get();
            return length;
        };
        byte[] target = new byte[4];
        assertEquals(1, buffer.read(reader, 10L, target, 0, 1, 3L));
        assertEquals(2, buffer.read(reader, 11L, target, 1, 2, 2L));
        assertEquals(-1, buffer.read(reader, 13L, target, 0, 1, 0L));
        assertEquals(1, calls.get());
        buffer.reset();
        assertEquals(1, buffer.read(reader, 10L, target, 0, 1, 3L));
        assertEquals(2, target[0]);
    }

    @Test
    public void disjointForwardBackwardAndTailReadsHaveIndependentExactBytes() throws Exception {
        RangeReadBuffer buffer = new RangeReadBuffer();
        RangeReadBuffer.Reader reader = (position, target, offset, length) -> {
            int count = (int) Math.min(length, 100_000L - position);
            for (int i = 0; i < count; i++) target[offset + i] = (byte) (position + i);
            return count == 0 ? -1 : count;
        };
        byte[] target = new byte[20];
        for (long position : new long[]{0, 90_000, 30_000, 99_997, 0}) {
            int expected = (int) Math.min(target.length, 100_000L - position);
            assertEquals(expected, buffer.read(reader, position, target, 0,
                    target.length, 100_000L - position));
            for (int i = 0; i < expected; i++) assertEquals((byte) (position + i), target[i]);
        }
    }
}
