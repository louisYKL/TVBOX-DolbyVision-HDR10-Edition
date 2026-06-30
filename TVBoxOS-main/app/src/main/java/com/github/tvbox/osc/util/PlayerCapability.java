package com.github.tvbox.osc.util;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;

import com.github.tvbox.osc.base.App;

public class PlayerCapability {
    private PlayerCapability() {
    }

    public static boolean supportsAudioPassthrough(VideoStreamProbe.Result probe) {
        if (probe == null) {
            return false;
        }
        if (probe.hasTrueHdAudio || probe.hasAtmosLikeAudio) {
            return false;
        }
        boolean needsAc3 = probe.hasAc3Audio;
        boolean needsEac3 = probe.hasEac3Audio;
        boolean needsDts = probe.hasDtsAudio;
        if (!needsAc3 && !needsEac3 && !needsDts) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            AudioManager audioManager = (AudioManager) App.getInstance().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return false;
            }
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null || devices.length == 0) {
                return false;
            }
            for (AudioDeviceInfo device : devices) {
                if (!isHdmiOutput(device)) {
                    continue;
                }
                if ((!needsAc3 || hasEncoding(device, AudioFormat.ENCODING_AC3))
                        && (!needsEac3 || hasEac3Encoding(device))
                        && (!needsDts || hasDtsEncoding(device))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    public static boolean supportsAudioPassthrough() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            AudioManager audioManager = (AudioManager) App.getInstance().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return false;
            }
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null || devices.length == 0) {
                return false;
            }
            for (AudioDeviceInfo device : devices) {
                if (device == null) {
                    continue;
                }
                if (!isHdmiOutput(device)) {
                    continue;
                }
                for (int encoding : device.getEncodings()) {
                    if (encoding == AudioFormat.ENCODING_AC3
                            || encoding == AudioFormat.ENCODING_E_AC3
                            || encoding == AudioFormat.ENCODING_DTS
                            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && encoding == AudioFormat.ENCODING_DTS_HD)
                            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && encoding == AudioFormat.ENCODING_IEC61937)
                            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && encoding == AudioFormat.ENCODING_DOLBY_TRUEHD)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static boolean isHdmiOutput(AudioDeviceInfo device) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_HDMI
                || type == AudioDeviceInfo.TYPE_HDMI_ARC
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == AudioDeviceInfo.TYPE_HDMI_EARC);
    }

    private static boolean hasEac3Encoding(AudioDeviceInfo device) {
        if (hasEncoding(device, AudioFormat.ENCODING_E_AC3)) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && hasEncoding(device, AudioFormat.ENCODING_E_AC3_JOC);
    }

    private static boolean hasDtsEncoding(AudioDeviceInfo device) {
        if (hasEncoding(device, AudioFormat.ENCODING_DTS)) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && hasEncoding(device, AudioFormat.ENCODING_DTS_HD);
    }

    private static boolean hasEncoding(AudioDeviceInfo device, int expectedEncoding) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        int[] encodings = device.getEncodings();
        if (encodings == null || encodings.length == 0) {
            return false;
        }
        for (int encoding : encodings) {
            if (encoding == expectedEncoding) {
                return true;
            }
        }
        return false;
    }
}
