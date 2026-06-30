package com.github.tvbox.osc.player;

import android.content.Context;
import android.content.pm.PackageManager;
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
    private static final String SPDIF_CODECS_FULL = "ac3,eac3,dts,dts-hd,truehd";
    private static final String SPDIF_CODECS_TV32 = "ac3,eac3,dts";
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean CREATED = new AtomicBoolean(false);
    private static volatile boolean preferHdrOutput = true;
    private static volatile String outputMode = "base-hdr";
    private static volatile int hdrTargetPeakNits = 1000;
    private static volatile boolean java64PhoneAudioSafeMode = false;
    private static volatile boolean currentPlayIsDolbyVision = false;

    public static boolean isJava64Build() {
        return com.github.tvbox.osc.base.App.isJava64Build();
    }

    public static void setCurrentPlayIsDolbyVision(boolean isDV) {
        currentPlayIsDolbyVision = isDV;
        LOG.i("echo-mpvcompat setCurrentPlayIsDolbyVision=" + isDV);
    }
    private static volatile boolean tv32AudioSafeMode = false;
    private static volatile boolean currentFileAllowsPassthrough = false;
    private static volatile boolean currentFileForcesTv32LocalProxyPcm = false;
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
            java64PhoneAudioSafeMode = isJava64Phone(appContext);
            tv32AudioSafeMode = isTv32Device(appContext);
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
            setOption("demuxer-lavf-o", "fflags=+fastseek,probesize=52428800,analyzeduration=50000000");
            applyAudioOutputOptions();
            setOption("tls-verify", "yes");
            setOption("tls-ca-file", new File(appContext.getFilesDir(), "cacert.pem").getAbsolutePath());
            if (com.github.tvbox.osc.base.App.isJava64Build()) {
                setOption("demuxer-max-bytes", String.valueOf(256 * 1024 * 1024));
                setOption("demuxer-max-back-bytes", String.valueOf(128 * 1024 * 1024));
                setOption("cache", "yes");
                setOption("cache-pause", "no");
                setOption("cache-pause-wait", "2");
                setOption("cache-secs", "60");
            } else {
                setOption("demuxer-max-bytes", String.valueOf(96 * 1024 * 1024));
                setOption("demuxer-max-back-bytes", String.valueOf(48 * 1024 * 1024));
                setOption("cache", "yes");
                setOption("cache-pause", "no");
                setOption("cache-pause-wait", "2");
                setOption("cache-secs", "20");
            }
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
        currentPlayIsDolbyVision = false;
        currentFileForcesTv32LocalProxyPcm = false;
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

    public static boolean promoteRuntimeHdrOutput(String preferredMode, String reason) {
        String targetMode = normalizeOutputMode(preferredMode);
        if (!isHdrOutputMode(targetMode)) {
            targetMode = "base-hdr";
        }
        boolean changed = !TextUtils.equals(outputMode, targetMode) || !preferHdrOutput;
        outputMode = targetMode;
        preferHdrOutput = true;
        LOG.i("echo-mpvcompat runtime-hdr " + (changed ? "promote" : "keep")
                + " mode=" + outputMode + " reason=" + reason);
        return changed;
    }

    public static boolean promoteRuntimeHdrOutput(String reason) {
        return promoteRuntimeHdrOutput("base-hdr", reason);
    }

    public static void setCurrentFileAllowsPassthrough(boolean allowed) {
        currentFileAllowsPassthrough = allowed;
        LOG.i("echo-mpv-audio filePassthroughAllowed=" + allowed);
        if (INITIALIZED.get() && CREATED.get()) {
            applyAudioOutputOptions();
        }
    }

    public static void setCurrentFileForcesTv32LocalProxyPcm(boolean forced) {
        currentFileForcesTv32LocalProxyPcm = forced;
        LOG.i("echo-mpv-audio tv32LocalProxyPcm=" + forced);
        if (INITIALIZED.get() && CREATED.get()) {
            applyAudioOutputOptions();
        }
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
        setRuntimeString("video-sync", currentFileForcesTv32LocalProxyPcm ? "display-desync" : "audio");
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
        appendFileOption(builder, "video-sync",
                currentFileForcesTv32LocalProxyPcm ? "display-desync" : "audio");
        appendFileOption(builder, "framedrop", "vo");
        appendFileOption(builder, "interpolation", "no");
        if (currentFileForcesTv32LocalProxyPcm) {
            appendFileOption(builder, "ao", "audiotrack");
            appendFileOption(builder, "audio-spdif", "");
            appendFileOption(builder, "audio-exclusive", "no");
            appendFileOption(builder, "audio-channels", "stereo");
            appendFileOption(builder, "audio-normalize-downmix", "yes");
            appendFileOption(builder, "audio-buffer", "1.0");
            appendFileOption(builder, "audio-stream-silence", "yes");
        }
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
        if (java64PhoneAudioSafeMode) {
            passthrough = false;
        }
        if (currentFileForcesTv32LocalProxyPcm) {
            passthrough = false;
        }
        boolean effectivePassthrough = passthrough
                && currentFileAllowsPassthrough
                && !java64PhoneAudioSafeMode;
        String spdifCodecs = tv32AudioSafeMode ? SPDIF_CODECS_TV32 : SPDIF_CODECS_FULL;
        setRuntimeString("ao", "audiotrack");
        setRuntimeDouble("volume", 100d);
        setRuntimeBoolean("mute", false);
        setRuntimeString("audio-device", "auto");
        setRuntimeString("audio-stream-silence", "no");
        if (java64PhoneAudioSafeMode || tv32AudioSafeMode) {
            setRuntimeString("audio-client-name", "TVBox");
            setRuntimeString("audio-set-media-role", "no");
        } else {
            setRuntimeString("audio-set-media-role", "yes");
        }
        if (java64PhoneAudioSafeMode) {
            // java64 触屏设备的核心问题不是“软件音量”，而是系统原生 MKV+EAC3
            // 提取不到音轨时需要由 mpv 自己完整 demux+decode 音频。
            // 这里尽量贴近 mpv-android 的稳定默认值，只禁用 raw/passthrough 分支，
            // 避免我们额外锁死声道/格式导致 AudioTrack 持续输出 mute data。
            setRuntimeString("audio-channels", "auto");
            setRuntimeString("ad", "auto");
            setRuntimeString("audio-file-auto", "all");
            setRuntimeString("audio-exclusive", "no");
            setRuntimeString("audio-spdif", "");
            setRuntimeString("audio-normalize-downmix", "yes");
            setRuntimeString("audio-fallback-to-null", "no");
            setRuntimeString("alang", "");
        } else if (tv32AudioSafeMode) {
            // tv32 must only passthrough when probe already confirmed a supported
            // compressed track. If probe metadata is incomplete, stay on PCM decode
            // to avoid the repeated "video ok but no sound" regressions.
            setRuntimeString("audio-channels", currentFileForcesTv32LocalProxyPcm ? "stereo" : "auto");
            setRuntimeString("ad", "auto");
            setRuntimeString("audio-file-auto", "all");
            setRuntimeString("audio-exclusive", effectivePassthrough ? "yes" : "no");
            setRuntimeString("audio-spdif", effectivePassthrough ? spdifCodecs : "");
            setRuntimeString("audio-normalize-downmix", effectivePassthrough ? "no" : "yes");
            setRuntimeString("audio-buffer", currentFileForcesTv32LocalProxyPcm ? "1.0" : "0.2");
            setRuntimeString("audio-stream-silence", currentFileForcesTv32LocalProxyPcm ? "yes" : "no");
            setRuntimeString("audio-fallback-to-null", "no");
            // 保持容器默认音轨，用户手动切换时再改。
            setRuntimeString("alang", "");
        } else {
            setRuntimeString("audio-channels", "auto");
            setRuntimeString("ad", "auto");
            setRuntimeString("audio-file-auto", "all");
            setRuntimeString("audio-exclusive", effectivePassthrough ? "yes" : "no");
            setRuntimeString("audio-spdif", effectivePassthrough ? spdifCodecs : "");
        }
        LOG.i("echo-mpv-audio passthrough=" + passthrough
                + " effectivePassthrough=" + effectivePassthrough
                + " fileAllowed=" + currentFileAllowsPassthrough
                + " volume=100 safePhoneMode=" + java64PhoneAudioSafeMode
                + " safeTv32Mode=" + tv32AudioSafeMode
                + " tv32LocalProxyPcm=" + currentFileForcesTv32LocalProxyPcm
                + " exclusive=" + (effectivePassthrough && !java64PhoneAudioSafeMode)
                + " spdif=" + (effectivePassthrough ? spdifCodecs : "")
                + " channels=" + (currentFileForcesTv32LocalProxyPcm ? "stereo" : "auto"));
    }

    private static boolean isJava64Phone(@NonNull Context context) {
        if (!com.github.tvbox.osc.base.App.isJava64Build()) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            if (pm != null) {
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                        || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                    return false;
                }
                if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static boolean isTv32Device(@NonNull Context context) {
        return com.github.tvbox.osc.util.ScreenUtils.isTv32Device(context);
    }

    private static void applySubtitleOutputOptions() {
        setRuntimeString("slang", "zh-Hans,zh-CN,cmn-Hans,chs,zh,chi,zho,zh-Hant,zh-TW,cmn-Hant,cht");
        setRuntimeString("sid", "auto");
        setRuntimeString("sub-auto", "fuzzy");
        setRuntimeString("subs-with-matching-audio", "no");
        setRuntimeString("sub-forced-events-only", "no");
        setRuntimeString("sub-visibility", "no");
        setRuntimeString("sub-ass", "yes");
        setRuntimeString("sub-ass-override", "force");
        setRuntimeString("sub-ass-force-style", "PrimaryColour=&H00D8D8D8,SecondaryColour=&H00D8D8D8,OutlineColour=&H00000000,BackColour=&H80000000");
        setRuntimeString("sub-font-size", "44");
        setRuntimeString("sub-color", "#D0909090");
        setRuntimeString("sub-border-color", "#D8000000");
        setRuntimeString("sub-shadow-color", "#E0000000");
        setRuntimeString("sub-border-size", "3");
        setRuntimeString("sub-shadow-offset", "1");
        LOG.i("echo-mpv-subtitle manual zh preferred hdr-safe");
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
        return isHdrOutputMode(outputMode);
    }

    private static boolean isHdrOutputMode(String mode) {
        return "base-hdr".equals(mode)
                || "map-hdr".equals(mode)
                || "dv-base-hdr".equals(mode);
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
