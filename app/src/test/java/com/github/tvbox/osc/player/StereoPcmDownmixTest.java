package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class StereoPcmDownmixTest {
    @Test
    public void monoIsDuplicatedToBothChannels() {
        short[] output = new short[2];
        StereoPcmDownmix.downmixFrame(new short[]{1234}, 1, output);
        assertArrayEquals(new short[]{1234, 1234}, output);
    }

    @Test
    public void stereoIsKeptUnchanged() {
        short[] output = new short[2];
        StereoPcmDownmix.downmixFrame(new short[]{1200, -800}, 2, output);
        assertArrayEquals(new short[]{1200, -800}, output);
    }

    @Test
    public void surroundChannelsAreMixedIntoStereo() {
        short[] output = new short[2];
        StereoPcmDownmix.downmixFrame(
                new short[]{1000, 2000, 1000, 400, 600, 800}, 6, output);
        assertEquals(2107, output[0]);
        assertEquals(3207, output[1]);
    }

    @Test
    public void mixedOutputIsClampedToPcm16() {
        short[] output = new short[2];
        StereoPcmDownmix.downmixFrame(
                new short[]{Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE,
                        Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE}, 6, output);
        assertArrayEquals(new short[]{Short.MAX_VALUE, Short.MAX_VALUE}, output);
    }
}
