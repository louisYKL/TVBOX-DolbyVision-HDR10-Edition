package com.github.tvbox.osc.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts decoded mono or multichannel PCM16 to stereo PCM16 for optical/ARC output. */
final class StereoPcmAudioProcessor extends BaseAudioProcessor {
    private short[] inputFrame = new short[0];
    private final short[] outputFrame = new short[2];

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new AudioProcessor.UnhandledAudioFormatException(inputAudioFormat);
        }
        if (inputAudioFormat.channelCount == 2) {
            return AudioFormat.NOT_SET;
        }
        inputFrame = new short[Math.max(1, inputAudioFormat.channelCount)];
        return new AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int channelCount = inputAudioFormat.channelCount;
        int bytesPerInputFrame = inputAudioFormat.bytesPerFrame;
        int frameCount = bytesPerInputFrame <= 0 ? 0 : inputBuffer.remaining() / bytesPerInputFrame;
        if (frameCount <= 0) {
            // AudioProcessor callers require forward progress. A malformed partial PCM frame must
            // not be returned forever to DefaultAudioSink, where it would stall the whole clock.
            inputBuffer.position(inputBuffer.limit());
            return;
        }

        inputBuffer.order(ByteOrder.nativeOrder());
        ByteBuffer outputBuffer = replaceOutputBuffer(frameCount * 2 * 2);
        outputBuffer.order(ByteOrder.nativeOrder());
        for (int frame = 0; frame < frameCount; frame++) {
            for (int channel = 0; channel < channelCount; channel++) {
                inputFrame[channel] = inputBuffer.getShort();
            }
            StereoPcmDownmix.downmixFrame(inputFrame, channelCount, outputFrame);
            outputBuffer.putShort(outputFrame[0]);
            outputBuffer.putShort(outputFrame[1]);
        }
        // Decoded PCM is normally frame aligned. Drop only an invalid trailing partial frame so
        // the sink can continue instead of repeatedly submitting the same bytes.
        inputBuffer.position(inputBuffer.limit());
        outputBuffer.flip();
    }
}
