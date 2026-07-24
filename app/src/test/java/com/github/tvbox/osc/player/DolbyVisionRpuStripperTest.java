package com.github.tvbox.osc.player;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DolbyVisionRpuStripperTest {
    @Test
    public void removesRpuAndEnhancementUnitsWithMixedStartCodes() {
        ByteBuffer data = ByteBuffer.allocateDirect(64);
        put(data, 0x00, 0x00, 0x01, 0x40, 0x11,
                0x00, 0x00, 0x00, 0x01, 0x7c, 0x22, 0x23,
                0x00, 0x00, 0x01, 0x7e, 0x33,
                0x00, 0x00, 0x01, 0x42, 0x44);
        data.flip();

        DolbyVisionRpuStripper stripper = new DolbyVisionRpuStripper();
        int removed = stripper.stripInPlace(data);

        assertEquals(2, removed);
        assertArrayEquals(new byte[]{0, 0, 1, 0x40, 0x11, 0, 0, 1, 0x42, 0x44},
                remainingBytes(data));
        assertEquals(10, stripper.lastOutputBytes());
    }

    @Test
    public void preservesPrefixAndPositionWhenCompacting() {
        ByteBuffer data = ByteBuffer.allocate(64);
        data.position(3);
        put(data, 0x55, 0x66, 0x00, 0x00, 0x01, 0x40, 0x01,
                0x00, 0x00, 0x01, 0x7c, 0x02,
                0x00, 0x00, 0x01, 0x42, 0x03);
        data.flip();
        data.position(3);

        int removed = new DolbyVisionRpuStripper().stripInPlace(data);

        assertEquals(1, removed);
        assertEquals(3, data.position());
        assertEquals(12, data.remaining());
        assertEquals(0x55, data.get(3) & 0xFF);
        assertEquals(0x42, data.get(13) & 0xFF);
    }

    @Test
    public void leavesAccessUnitWithoutDolbyNalUntouched() {
        ByteBuffer data = ByteBuffer.wrap(new byte[]{0, 0, 1, 0x40, 1, 2, 3});
        int oldLimit = data.limit();

        int removed = new DolbyVisionRpuStripper().stripInPlace(data);

        assertEquals(0, removed);
        assertEquals(0, data.position());
        assertEquals(oldLimit, data.limit());
        assertArrayEquals(new byte[]{0, 0, 1, 0x40, 1, 2, 3}, remainingBytes(data));
    }

    @Test
    public void reusesScratchForSuccessiveLargeAccessUnits() {
        DolbyVisionRpuStripper stripper = new DolbyVisionRpuStripper();
        ByteBuffer first = sampleWithRpu(1024);
        ByteBuffer second = sampleWithRpu(512);

        assertTrue(stripper.stripInPlace(first) > 0);
        int scratchCapacity = stripper.scratchCapacity();
        assertTrue(stripper.stripInPlace(second) > 0);

        assertEquals(scratchCapacity, stripper.scratchCapacity());
        assertEquals(512 + 12, stripper.lastInputBytes());
        assertEquals(512 + 5, stripper.lastOutputBytes());
    }

    private static ByteBuffer sampleWithRpu(int payloadSize) {
        ByteBuffer data = ByteBuffer.allocateDirect(payloadSize + 16);
        put(data, 0, 0, 1, 0x40, 1);
        for (int i = 0; i < payloadSize; i++) {
            data.put((byte) ((i % 251) + 1));
        }
        put(data, 0, 0, 1, 0x7c, 2, 3, 4);
        data.flip();
        return data;
    }

    private static byte[] remainingBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static void put(ByteBuffer buffer, int... values) {
        for (int value : values) {
            buffer.put((byte) value);
        }
    }
}
