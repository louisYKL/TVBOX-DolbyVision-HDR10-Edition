package com.github.tvbox.osc.util;

import android.content.Context;
import android.media.AudioManager;

import com.orhanobut.hawk.Hawk;

/** Keeps Android's media stream aligned with the fixed-level passthrough contract. */
public final class AudioPassthroughVolumePolicy {
    private AudioPassthroughVolumePolicy() {
    }

    public static boolean isVolumeLocked() {
        return shouldForceMaximum(Hawk.get(HawkConfig.PLAYER_AUDIO_PASSTHROUGH, false));
    }

    static boolean shouldForceMaximum(boolean passthroughEnabled) {
        return passthroughEnabled;
    }

    public static void enforceMaximum(Context context) {
        if (!isVolumeLocked() || context == null) {
            return;
        }
        try {
            AudioManager audioManager = (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (maxVolume > 0 && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != maxVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
            }
        } catch (Throwable ignored) {
            // A vendor audio service may be temporarily unavailable during route changes.
        }
    }
}
