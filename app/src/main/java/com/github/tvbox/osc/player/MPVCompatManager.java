package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HdrDeviceSupport;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import is.xyz.mpv.MPVLib;

public final class MPVCompatManager {
    private static final String TAG = "MPVCompatManager";
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean CREATED = new AtomicBoolean(false);
    private static volatile boolean preferHdrOutput = true;
    private static volatile String outputMode = "base-hdr";
    private static volatile int hdrTargetPeakNits = 1000;
    private static final String VO_GPU = "gpu";

    private MPVCompatManager() {
    }

    public static void ensureInitialized(@NonNull Context context) {
        if (INITIALIZED.get()) {
            return;
        }
        synchronized (MPVCompatManager.class) {
            if (INITIALIZED.get()) {
                return;
            }
            Context appContext = context.getApplicationContext();
            File configDir = new File(appContext.getFilesDir(), "mpv-config");
            File cacheDir = new File(appContext.getCacheDir(), "mpv-cache");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            HdrDeviceSupport.Capabilities caps = HdrDeviceSupport.query(appContext);
            hdrTargetPeakNits = caps.hdrTargetPeakNits();
            copyAssetIfNeeded(appContext, "cacert.pem", new File(appContext.getFilesDir(), "cacert.pem"));
            if (!CREATED.get()) {
                MPVLib.create(appContext);
                CREATED.set(true);
            }
            setOption("config", "no");
            setOption("config-dir", configDir.getAbsolutePath());
            setOption("gpu-shader-cache-dir", cacheDir.getAbsolutePath());
            setOption("icc-cache-dir", cacheDir.getAbsolutePath());
            setOption("load-auto-profiles", "no");
            setOption("load-scripts", "no");
            setOption("ytdl", "no");
            setOption("script-opts", "ytdl_hook-ytdl_path=");
            setOption("profile", "fast");
            // mediacodec_embed aborts on some Huawei/Android TV Surface transitions
            // when WinID becomes 0. Keep GPU output and MediaCodec decoding separate
            // so source switching/fullscreen exits cannot trip that native assert.
            setOption("vo", VO_GPU);
            setOption("gpu-api", "opengl");
            setOption("gpu-context", "android");
            setOption("opengl-es", "yes");
            setOption("fbo-format", "rgba8");
            setOption("video-sync", "audio");
            setOption("framedrop", "vo");
            setOption("interpolation", "no");
            // 解码仍优先走硬件 MediaCodec；GPU VO 只负责稳定渲染和兼容 HDR 映射链路。
            setOption("hwdec", "mediacodec");
            setOption("vd", "lavc");
            setOption("vd-lavc-threads", "0");
            setOption("vd-lavc-dr", "yes");
            setOption("vd-lavc-software-fallback", "no");
            setOption("hwdec-software-fallback", "no");
            setOption("demuxer-lavf-o", "fflags=+fastseek,probesize=10485760,analyzeduration=10000000");
            applyAudioOutputOptions();
            setOption("tls-verify", "yes");
            setOption("tls-ca-file", new File(appContext.getFilesDir(), "cacert.pem").getAbsolutePath());
            setOption("demuxer-max-bytes", String.valueOf(96 * 1024 * 1024));
            setOption("demuxer-max-back-bytes", String.valueOf(48 * 1024 * 1024));
            setOption("cache", "yes");
            setOption("cache-pause", "no");
            setOption("cache-pause-wait", "2");
            setOption("cache-secs", "20");
            setOption("demuxer-thread", "yes");
            applySubtitleOutputOptions();
            setOption("keep-open", "no");
            applyOutputMode();
            setOption("dither-depth", "auto");
            setOption("input-default-bindings", "no");
            setOption("save-position-on-quit", "no");
            setOption("force-window", "no");
            setOption("idle", "once");
            MPVLib.init();
            applyPlaybackModeOptions();
            INITIALIZED.set(true);
            LOG.i("echo-mpvcompat initialized targetPeak=" + hdrTargetPeakNits
                    + " caps=" + caps.summary);
        }
    }

    public static void release() {
        synchronized (MPVCompatManager.class) {
            if (!INITIALIZED.get()) {
                return;
            }
            try {
                MPVLib.destroy();
            } catch (Throwable th) {
                Log.w(TAG, "destroy failed", th);
            }
            INITIALIZED.set(false);
            CREATED.set(false);
        }
    }

    public static void resetPlaybackState() {
        synchronized (MPVCompatManager.class) {
            if (!INITIALIZED.get() || !CREATED.get()) {
                return;
            }
            try {
                MPVLib.setPropertyBoolean("pause", true);
            } catch (Throwable ignored) {
            }
            try {
                MPVLib.command(new String[]{"stop"});
            } catch (Throwable th) {
                Log.w(TAG, "resetPlaybackState stop failed", th);
            }
            try {
                MPVLib.detachSurface();
            } catch (Throwable ignored) {
            }
            try {
                MPVLib.setOptionString("http-header-fields", "");
                MPVLib.setOptionString("referrer", "");
                MPVLib.setOptionString("force-window", "no");
            } catch (Throwable ignored) {
            }
        }
    }

    public static void hardResetForNextPlayback() {
        synchronized (MPVCompatManager.class) {
            if (!INITIALIZED.get() || !CREATED.get()) {
                return;
            }
            resetPlaybackState();
            try {
                MPVLib.destroy();
            } catch (Throwable th) {
                Log.w(TAG, "hard reset destroy failed", th);
            }
            INITIALIZED.set(false);
            CREATED.set(false);
            LOG.i("echo-mpvcompat hard-reset");
        }
    }

    public static void setPreferHdrOutput(boolean preferHdr) {
        preferHdrOutput = preferHdr;
        outputMode = preferHdr ? "base-hdr" : "sdr";
        LOG.i("echo-mpvcompat output=" + outputMode);
    }

    public static void setOutputMode(String mode) {
        String normalized = normalizeOutputMode(mode);
        outputMode = normalized;
        preferHdrOutput = !"map-sdr".equals(normalized) && !"sdr".equals(normalized);
        LOG.i("echo-mpvcompat output=" + outputMode);
    }

    public static void applyDolbyVisionMappingOptions() {
        setOutputMode(preferHdrOutput ? "map-hdr" : "map-sdr");
    }

    public static String getOutputMode() {
        return outputMode;
    }

    public static boolean isMappingOutputMode() {
        return isMappingMode();
    }

    public static void promoteRuntimeHdrOutput(String reason) {
        if (isHdrOutputMode()) {
            LOG.i("echo-mpvcompat runtime-hdr already mode=" + outputMode + " reason=" + reason);
            return;
        }
        outputMode = "base-hdr";
        preferHdrOutput = true;
        LOG.i("echo-mpvcompat runtime-hdr promote mode=base-hdr reason=" + reason);
    }

    public static void applyPlaybackModeOptions() {
        boolean hdr = isHdrOutputMode();
        boolean mapping = isMappingMode();
        String vo = selectVideoOutput(mapping);
        applyAudioOutputOptions();
        setRuntimeString("vo", vo);
        setRuntimeString("hwdec", "mediacodec");
        setRuntimeString("vd", "lavc");
        setRuntimeString("vd-lavc-dr", "yes");
        setRuntimeString("vd-lavc-threads", "0");
        setRuntimeString("vd-lavc-software-fallback", "no");
        setRuntimeString("hwdec-software-fallback", "no");
        if (VO_GPU.equals(vo)) {
            setRuntimeString("gpu-api", "opengl");
            setRuntimeString("gpu-context", "android");
            setRuntimeString("opengl-es", "yes");
            // Huawei 32-bit TV firmware fails or drops frames with rgba10a2/rgba16f.
            // Keep the GL path light; HDR activation is requested through the Activity window.
            setRuntimeString("fbo-format", "rgba8");
        }
        setRuntimeString("framedrop", "vo");
        setRuntimeString("video-output-levels", "limited");
        applySubtitleOutputOptions();
        setRuntimeString("video-aspect-override", "no");
        setRuntimeString("video-unscaled", "no");
        setRuntimeString("video-scale-x", "1");
        setRuntimeString("video-scale-y", "1");
        setRuntimeString("video-zoom", "0");
        setRuntimeString("video-align-x", "0");
        setRuntimeString("video-align-y", "0");
        setRuntimeString("target-colorspace-hint", hdr ? "yes" : "no");
        setRuntimeString("target-colorspace-hint-mode", mapping && hdr ? "source-dynamic" : "target");
        setRuntimeString("target-trc", mapping ? (hdr ? "pq" : "bt.1886") : "auto");
        setRuntimeString("target-prim", mapping ? (hdr ? "bt.2020" : "bt.709") : "auto");
        setRuntimeString("target-peak", hdr ? String.valueOf(hdrTargetPeakNits) : "auto");
        setRuntimeString("tone-mapping", mapping ? (hdr ? "mobius" : "bt.2446a") : "auto");
        setRuntimeString("gamut-mapping-mode", mapping ? (hdr ? "clip" : "perceptual") : "auto");
        setRuntimeString("hdr-compute-peak", "no");
        setRuntimeString("hdr-peak-percentile", "100");
        setRuntimeString("sigmoid-upscaling", "no");
        setRuntimeString("deband", "no");
        setRuntimeString("scale", "bilinear");
        setRuntimeString("cscale", "bilinear");
        setRuntimeString("dscale", "bilinear");
        setRuntimeString("vf", "");
        LOG.i("echo-mpvcompat mode=" + outputMode + " vo=" + vo + " hdr=" + hdr
                + " mapping=" + mapping + " targetPeak=" + (hdr ? hdrTargetPeakNits : 0));
    }

    public static String buildDolbyVisionPerFileOptions() {
        return buildPlaybackPerFileOptions();
    }

    public static String buildPlaybackPerFileOptions() {
        boolean hdr = isHdrOutputMode();
        boolean mapping = isMappingMode();
        StringBuilder builder = new StringBuilder();
        appendFileOption(builder, "hwdec", "mediacodec");
        appendFileOption(builder, "vd", "lavc");
        appendFileOption(builder, "vd-lavc-dr", "yes");
        appendFileOption(builder, "vd-lavc-threads", "0");
        appendFileOption(builder, "vd-lavc-software-fallback", "no");
        appendFileOption(builder, "hwdec-software-fallback", "no");
        String vo = selectVideoOutput(mapping);
        appendFileOption(builder, "vo", vo);
        if (VO_GPU.equals(vo)) {
            appendFileOption(builder, "gpu-api", "opengl");
            appendFileOption(builder, "gpu-context", "android");
            appendFileOption(builder, "opengl-es", "yes");
            appendFileOption(builder, "fbo-format", "rgba8");
        }
        appendFileOption(builder, "video-sync", "audio");
        appendFileOption(builder, "framedrop", "vo");
        appendFileOption(builder, "interpolation", "no");
        // slang contains commas; passing it through loadfile's comma-separated
        // option string makes mpv treat later language tokens as option names.
        // Keep it as a runtime property via applySubtitleOutputOptions().
        appendFileOption(builder, "sid", "auto");
        appendFileOption(builder, "sub-auto", "fuzzy");
        appendFileOption(builder, "sub-visibility", "no");
        appendFileOption(builder, "sub-ass", "yes");
        appendFileOption(builder, "sub-ass-override", "force");
        appendFileOption(builder, "sub-font-size", "44");
        appendFileOption(builder, "sub-color", "#D0909090");
        appendFileOption(builder, "sub-border-color", "#D8000000");
        appendFileOption(builder, "sub-shadow-color", "#E0000000");
        appendFileOption(builder, "sub-border-size", "3");
        appendFileOption(builder, "sub-shadow-offset", "1");
        appendFileOption(builder, "video-aspect-override", "no");
        appendFileOption(builder, "video-unscaled", "no");
        appendFileOption(builder, "video-scale-x", "1");
        appendFileOption(builder, "video-scale-y", "1");
        appendFileOption(builder, "video-zoom", "0");
        appendFileOption(builder, "video-align-x", "0");
        appendFileOption(builder, "video-align-y", "0");
        appendFileOption(builder, "target-colorspace-hint", hdr ? "yes" : "no");
        appendFileOption(builder, "target-colorspace-hint-mode", mapping && hdr ? "source-dynamic" : "target");
        appendFileOption(builder, "target-trc", mapping ? (hdr ? "pq" : "bt.1886") : "auto");
        appendFileOption(builder, "target-prim", mapping ? (hdr ? "bt.2020" : "bt.709") : "auto");
        appendFileOption(builder, "target-peak", hdr ? String.valueOf(hdrTargetPeakNits) : "auto");
        appendFileOption(builder, "video-output-levels", "limited");
        appendFileOption(builder, "tone-mapping", mapping ? (hdr ? "mobius" : "bt.2446a") : "auto");
        appendFileOption(builder, "gamut-mapping-mode", mapping ? (hdr ? "clip" : "perceptual") : "auto");
        appendFileOption(builder, "hdr-compute-peak", "no");
        appendFileOption(builder, "hdr-peak-percentile", "100");
        appendFileOption(builder, "deband", "no");
        appendFileOption(builder, "scale", "bilinear");
        appendFileOption(builder, "cscale", "bilinear");
        appendFileOption(builder, "dscale", "bilinear");
        return builder.toString();
    }

    public static void applyAudioOutputOptions() {
        boolean passthrough = Hawk.get(HawkConfig.PLAYER_AUDIO_PASSTHROUGH, false);
        setRuntimeString("ao", "audiotrack");
        setRuntimeDouble("volume", 100d);
        setRuntimeBoolean("mute", false);
        setRuntimeString("audio-device", "auto");
        setRuntimeString("audio-set-media-role", "yes");
        setRuntimeString("audio-stream-silence", "no");
        if (passthrough) {
            setRuntimeString("audio-exclusive", "yes");
            setRuntimeString("audio-spdif", "ac3,eac3,dts,dts-hd,truehd");
        } else {
            setRuntimeString("audio-exclusive", "no");
            setRuntimeString("audio-spdif", "");
        }
        LOG.i("echo-mpv-audio passthrough=" + passthrough + " volume=100");
    }

    private static void applySubtitleOutputOptions() {
        setRuntimeString("slang", "zh-Hans,zh-CN,chs,zh,chi,zho,zh-Hant,zh-TW,cht");
        setRuntimeString("sid", "auto");
        setRuntimeString("sub-auto", "fuzzy");
        setRuntimeString("sub-visibility", "no");
        setRuntimeString("sub-ass", "yes");
        setRuntimeString("sub-ass-override", "force");
        setRuntimeString("sub-ass-force-style", "PrimaryColour=&H00606060,SecondaryColour=&H00606060,OutlineColour=&H00000000,BackColour=&H80000000");
        setRuntimeString("sub-font-size", "44");
        setRuntimeString("sub-color", "#D0909090");
        setRuntimeString("sub-border-color", "#D8000000");
        setRuntimeString("sub-shadow-color", "#E0000000");
        setRuntimeString("sub-border-size", "3");
        setRuntimeString("sub-shadow-offset", "1");
        LOG.i("echo-mpv-subtitle auto zh preferred hdr-safe");
    }

    public static boolean shouldRequestHdrOutput() {
        return isHdrOutputMode();
    }

    private static void applyOutputMode() {
        applyPlaybackModeOptions();
    }

    private static boolean isMappingMode() {
        return "map-hdr".equals(outputMode) || "map-sdr".equals(outputMode);
    }

    private static boolean isHdrOutputMode() {
        return "base-hdr".equals(outputMode)
                || "map-hdr".equals(outputMode)
                || "dv-base-hdr".equals(outputMode);
    }

    private static String selectVideoOutput(boolean mapping) {
        return VO_GPU;
    }

    private static String normalizeOutputMode(String mode) {
        if ("map-hdr".equalsIgnoreCase(mode) || "hdr-map".equalsIgnoreCase(mode)) {
            return "map-hdr";
        }
        if ("map-sdr".equalsIgnoreCase(mode) || "sdr-map".equalsIgnoreCase(mode)) {
            return "map-sdr";
        }
        if ("sdr".equalsIgnoreCase(mode)) {
            return "sdr";
        }
        if ("dv-base-hdr".equalsIgnoreCase(mode) || "dv-hdr".equalsIgnoreCase(mode)) {
            return "dv-base-hdr";
        }
        return "base-hdr";
    }

    private static void setOption(String name, String value) {
        try {
            int result = MPVLib.setOptionString(name, value);
            if (result < 0) {
                LOG.e("echo-mpv-option-failed " + name + "=" + value + " result=" + result);
            }
        } catch (Throwable th) {
            Log.w(TAG, "setOption failed " + name + "=" + value, th);
        }
    }

    private static void setRuntimeString(String name, String value) {
        setOption(name, value);
        try {
            MPVLib.setPropertyString(name, value);
        } catch (Throwable th) {
            Log.w(TAG, "setProperty failed " + name + "=" + value, th);
        }
    }

    private static void setRuntimeDouble(String name, double value) {
        setOption(name, String.valueOf(value));
        try {
            MPVLib.setPropertyDouble(name, value);
        } catch (Throwable th) {
            Log.w(TAG, "setProperty failed " + name + "=" + value, th);
        }
    }

    private static void setRuntimeBoolean(String name, boolean value) {
        setOption(name, value ? "yes" : "no");
        try {
            MPVLib.setPropertyBoolean(name, value);
        } catch (Throwable th) {
            Log.w(TAG, "setProperty failed " + name + "=" + value, th);
        }
    }

    private static void appendFileOption(StringBuilder builder, String key, String value) {
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(key).append('=').append(value == null ? "" : value);
    }

    private static void copyAssetIfNeeded(Context context, String assetName, File outFile) {
        if (context == null || TextUtils.isEmpty(assetName) || outFile == null) {
            return;
        }
        try (InputStream inputStream = context.getAssets().open(assetName)) {
            int assetSize = inputStream.available();
            if (outFile.exists() && outFile.length() == assetSize) {
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "stat asset failed: " + assetName, e);
        }
        try (InputStream inputStream = context.getAssets().open(assetName);
             FileOutputStream outputStream = new FileOutputStream(outFile, false)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.flush();
        } catch (IOException e) {
            Log.w(TAG, "copy asset failed: " + assetName, e);
        }
    }
}
