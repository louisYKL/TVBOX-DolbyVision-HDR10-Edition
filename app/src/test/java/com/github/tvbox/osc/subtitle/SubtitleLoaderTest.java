package com.github.tvbox.osc.subtitle;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

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
}
