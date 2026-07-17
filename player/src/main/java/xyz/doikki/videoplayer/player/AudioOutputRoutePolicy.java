package xyz.doikki.videoplayer.player;

import android.media.AudioDeviceInfo;

/**
 * Classifies encoded-audio transports without taking media routing away from Android.
 *
 * <p>TV firmware often exposes ARC/SPDIF as a logical speaker route and performs the
 * real output switch in its audio HAL. Calling {@code MediaPlayer.setPreferredDevice()}
 * bypasses that policy and can leave decoded audio on a silent built-in speaker route.
 */
public final class AudioOutputRoutePolicy {
    private AudioOutputRoutePolicy() {
    }

    public static boolean isEncodedPassthroughTransport(int type) {
        return type == AudioDeviceInfo.TYPE_HDMI
                || type == AudioDeviceInfo.TYPE_HDMI_ARC
                || type == AudioDeviceInfo.TYPE_HDMI_EARC
                || type == AudioDeviceInfo.TYPE_LINE_DIGITAL;
    }
}
