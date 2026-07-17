package com.github.tvbox.osc.util;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;

import xyz.doikki.videoplayer.player.AudioOutputRoutePolicy;

public class PlayerCapability {
    private PlayerCapability() {
    }

    public static boolean supportsAudioPassthrough(VideoStreamProbe.Result probe) {
        if (!hasCompressedAudio(probe) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
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
                if (!isEncodedPassthroughOutput(device) || !supportsProbeEncoding(device, probe)) {
                    continue;
                }
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    public static String describeAudioPassthrough(VideoStreamProbe.Result probe) {
        String required = requiredEncodings(probe);
        if (!hasCompressedAudio(probe)) {
            return "required=" + required + " result=no-compressed-track";
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "required=" + required + " result=api<23";
        }
        try {
            AudioManager audioManager = (AudioManager) App.getInstance().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return "required=" + required + " result=no-audio-manager";
            }
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null || devices.length == 0) {
                return "required=" + required + " result=no-output-device";
            }
            StringBuilder outputs = new StringBuilder();
            boolean supported = false;
            for (AudioDeviceInfo device : devices) {
                if (device == null) {
                    continue;
                }
                if (outputs.length() > 0) {
                    outputs.append(';');
                }
                boolean passthroughOutput = isEncodedPassthroughOutput(device);
                boolean deviceSupportsProbe = passthroughOutput && supportsProbeEncoding(device, probe);
                supported = supported || deviceSupportsProbe;
                outputs.append("type=").append(device.getType())
                        .append(",name=").append(safeDeviceName(device))
                        .append(",enc=").append(deviceEncodings(device))
                        .append(",match=").append(deviceSupportsProbe);
            }
            return "required=" + required + " outputs=" + outputs + " result=" + supported;
        } catch (Throwable th) {
            return "required=" + required + " result=inspection-failed:"
                    + th.getClass().getSimpleName();
        }
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
                if (!isEncodedPassthroughOutput(device)) {
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

    private static boolean isEncodedPassthroughOutput(AudioDeviceInfo device) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return AudioOutputRoutePolicy.isEncodedPassthroughTransport(device.getType());
    }

    private static boolean hasEac3Encoding(AudioDeviceInfo device) {
        if (hasEncoding(device, AudioFormat.ENCODING_E_AC3)) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && hasEncoding(device, AudioFormat.ENCODING_E_AC3_JOC);
    }

    private static boolean supportsProbeEncoding(AudioDeviceInfo device, VideoStreamProbe.Result probe) {
        if (probe == null) {
            return false;
        }
        // Keep the established Java64/phone route conservative. The expanded TrueHD and
        // Atmos capability checks below are only needed for the active TV32 system path.
        if ((probe.hasTrueHdAudio || probe.hasAtmosLikeAudio)
                && !ScreenUtils.isTv32Device(App.getInstance())) {
            return false;
        }
        return AudioPassthroughPolicy.supports(
                requiredEncodingMask(probe),
                supportedEncodingMask(device));
    }

    private static int requiredEncodingMask(VideoStreamProbe.Result probe) {
        if (probe == null) {
            return 0;
        }
        int required = 0;
        if (probe.hasAc3Audio) required |= AudioPassthroughPolicy.AC3;
        if (probe.hasEac3Audio) required |= AudioPassthroughPolicy.E_AC3;
        if (probe.hasDtsAudio) required |= AudioPassthroughPolicy.DTS;
        if (probe.hasTrueHdAudio) required |= AudioPassthroughPolicy.TRUE_HD;
        if (probe.hasAtmosLikeAudio && !probe.hasTrueHdAudio) {
            required |= AudioPassthroughPolicy.E_AC3_JOC;
        }
        return required;
    }

    private static int supportedEncodingMask(AudioDeviceInfo device) {
        int supported = 0;
        if (hasEncoding(device, AudioFormat.ENCODING_AC3)) {
            supported |= AudioPassthroughPolicy.AC3;
        }
        if (hasEac3Encoding(device)) {
            supported |= AudioPassthroughPolicy.E_AC3;
        }
        if (hasDtsEncoding(device)) {
            supported |= AudioPassthroughPolicy.DTS;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && hasEncoding(device, AudioFormat.ENCODING_DOLBY_TRUEHD)) {
            supported |= AudioPassthroughPolicy.TRUE_HD;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && hasEncoding(device, AudioFormat.ENCODING_E_AC3_JOC)) {
            supported |= AudioPassthroughPolicy.E_AC3_JOC;
        }
        return supported;
    }

    private static boolean hasCompressedAudio(VideoStreamProbe.Result probe) {
        return probe != null && (probe.hasAc3Audio
                || probe.hasEac3Audio
                || probe.hasDtsAudio
                || probe.hasTrueHdAudio
                || probe.hasAtmosLikeAudio);
    }

    private static String requiredEncodings(VideoStreamProbe.Result probe) {
        if (probe == null) {
            return "none";
        }
        StringBuilder required = new StringBuilder();
        appendEncoding(required, probe.hasAc3Audio, "ac3");
        appendEncoding(required, probe.hasEac3Audio, "eac3");
        appendEncoding(required, probe.hasDtsAudio, "dts");
        appendEncoding(required, probe.hasTrueHdAudio, "truehd");
        appendEncoding(required, probe.hasAtmosLikeAudio && !probe.hasTrueHdAudio, "eac3-joc");
        return required.length() == 0 ? "none" : required.toString();
    }

    private static void appendEncoding(StringBuilder builder, boolean include, String encoding) {
        if (!include) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(encoding);
    }

    private static String safeDeviceName(AudioDeviceInfo device) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "unknown";
        }
        CharSequence productName = device.getProductName();
        if (TextUtils.isEmpty(productName)) {
            return "unknown";
        }
        return productName.toString().replace(',', '_').replace(';', '_');
    }

    private static String deviceEncodings(AudioDeviceInfo device) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "none";
        }
        int[] encodings = device.getEncodings();
        if (encodings == null || encodings.length == 0) {
            return "pcm";
        }
        StringBuilder values = new StringBuilder();
        for (int encoding : encodings) {
            if (values.length() > 0) {
                values.append(',');
            }
            values.append(encoding);
        }
        return values.toString();
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
