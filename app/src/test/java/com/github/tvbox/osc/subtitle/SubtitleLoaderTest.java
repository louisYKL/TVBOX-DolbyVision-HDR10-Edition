package com.github.tvbox.osc.subtitle;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SubtitleLoaderTest {
    @Test
    public void readLimitedAcceptsContentAtTheLimit() throws Exception {
        byte[] result = SubtitleLoader.readLimited(new ByteArrayInputStream(new byte[8]), 8);

        assertEquals(8, result.length);
    }

    @Test
    public void readLimitedRejectsContentOverTheLimit() throws Exception {
        try {
            SubtitleLoader.readLimited(new ByteArrayInputStream(new byte[9]), 8);
            fail("Expected oversized subtitle to be rejected");
        } catch (IOException expected) {
            assertEquals("Subtitle file exceeds 8 bytes", expected.getMessage());
        }
    }

    @Test
    public void unknownLengthResponseStopsAtTheBoundWithoutWaitingForEof() throws Exception {
        final int[] networkReads = {0};
        InputStream endlessResponse = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                networkReads[0]++;
                if (networkReads[0] > 2) {
                    throw new AssertionError("Oversized stream was read past its rejection point");
                }
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'x');
                return length;
            }
        };
        try {
            SubtitleLoader.readLimited(endlessResponse, 8192);
            fail("Expected unknown-length response to be bounded");
        } catch (IOException expected) {
            assertEquals("Subtitle file exceeds 8192 bytes", expected.getMessage());
            assertEquals(2, networkReads[0]);
        }
    }
}
