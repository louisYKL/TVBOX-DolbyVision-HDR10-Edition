package com.github.tvbox.osc.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class StereoPcmAudioProcessorTest {
    @Test
    public void multichannelInputIsConsumedAndConvertedToStereo() throws Exception {
        StereoPcmAudioProcessor processor = configuredProcessor(6);
        ByteBuffer input = ByteBuffer.allocateDirect(13).order(ByteOrder.nativeOrder());
        input.putShort((short) 1000);
        input.putShort((short) 2000);
        input.putShort((short) 1000);
        input.putShort((short) 400);
        input.putShort((short) 600);
        input.putShort((short) 800);
        input.put((byte) 0x7f);
        input.flip();

        processor.queueInput(input);

        assertFalse(input.hasRemaining());
        ByteBuffer output = processor.getOutput().order(ByteOrder.nativeOrder());
        assertEquals(4, output.remaining());
        assertEquals(2107, output.getShort());
        assertEquals(3207, output.getShort());
    }

    @Test
    public void partialFrameIsConsumedWithoutStallingTheSink() throws Exception {
        StereoPcmAudioProcessor processor = configuredProcessor(6);
        ByteBuffer input = ByteBuffer.allocateDirect(1);
        input.put((byte) 0x7f).flip();

        processor.queueInput(input);

        assertFalse(input.hasRemaining());
        assertEquals(0, processor.getOutput().remaining());
    }

    private StereoPcmAudioProcessor configuredProcessor(int channels)
            throws AudioProcessor.UnhandledAudioFormatException {
        StereoPcmAudioProcessor processor = new StereoPcmAudioProcessor();
        AudioProcessor.AudioFormat output = processor.configure(
                new AudioProcessor.AudioFormat(48_000, channels, C.ENCODING_PCM_16BIT));
        assertEquals(2, output.channelCount);
        processor.flush();
        return processor;
    }
}
