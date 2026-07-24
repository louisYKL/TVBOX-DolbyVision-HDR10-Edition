package com.github.tvbox.osc.player;

/** Deterministic PCM16 downmix used before AudioTrack output. */
final class StereoPcmDownmix {
    private StereoPcmDownmix() {
    }

    static void downmixFrame(short[] channels, int channelCount, short[] stereoOut) {
        if (channels == null || stereoOut == null || stereoOut.length < 2 || channelCount <= 0) {
            throw new IllegalArgumentException("Invalid PCM frame");
        }
        if (channelCount == 1) {
            stereoOut[0] = channels[0];
            stereoOut[1] = channels[0];
            return;
        }

        double left = channels[0];
        double right = channels[1];
        if (channelCount > 2) {
            double center = channels[2] * 0.70710678d;
            left += center;
            right += center;
        }
        if (channelCount > 3) {
            double lfe = channels[3] * 0.25d;
            left += lfe;
            right += lfe;
        }
        if (channelCount > 4) {
            left += channels[4] * 0.5d;
        }
        if (channelCount > 5) {
            right += channels[5] * 0.5d;
        }
        if (channelCount > 6) {
            left += channels[6] * 0.5d;
        }
        if (channelCount > 7) {
            right += channels[7] * 0.5d;
        }
        for (int i = 8; i < channelCount; i++) {
            if ((i & 1) == 0) {
                left += channels[i] * 0.35d;
            } else {
                right += channels[i] * 0.35d;
            }
        }
        stereoOut[0] = clampToPcm16(left);
        stereoOut[1] = clampToPcm16(right);
    }

    private static short clampToPcm16(double value) {
        if (value >= Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value <= Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) Math.round(value);
    }
}
