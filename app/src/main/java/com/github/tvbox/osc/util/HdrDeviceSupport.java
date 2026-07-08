package com.github.tvbox.osc.util;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Display;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 真实探测设备能力（不靠机型猜测）：
 *  - 显示/HDMI 输出端能力：Display.getHdrCapabilities() —— 电视能“显示”哪些 HDR（含 DV passthrough）。
 *  - 解码端能力：MediaCodecList —— 盒子的解码器能“解”哪些（是否有 video/dolby-vision 解码器、HEVC Main10 等）。
 * “设备是否支持杜比视界 = 既能解码 DV，又能显示 DV”。仅显示端支持(passthrough)但无 DV 解码器时，
 * 必须把 DV 映射成 HDR10/HDR10+ 再激发 HDR。
 */
public final class HdrDeviceSupport {
    private static final String[] CODEC_XML_DIRS = new String[]{
            "/odm/etc",
            "/vendor/etc",
            "/system/etc"
    };
    private static final String[] DOLBY_VISION_CODEC_MARKERS = new String[]{
            "type=\"video/dolby-vision\"",
            "type='video/dolby-vision'",
            "c2.qti.dv.decoder",
            "omx.dolby",
            "video/dolby-vision"
    };
    private static final String[] HEVC_MAIN10_CODEC_MARKERS = new String[]{
            "type=\"video/hevc\"",
            "type='video/hevc'",
            "hevcprofilemain10",
            "main10",
            "hdr10",
            "hdr10plus"
    };
    private static final String[] DOLBY_VISION_DECODER_PROPS = new String[]{
            "persist.sys.feature.dolby_vision",
            "persist.sys.feature.dolby_vision_app",
            "vendor.display.force_dv_support",
            "ro.vendor.oplus.dolby_vision_dpu"
    };

    private HdrDeviceSupport() {
    }

    public static final class Capabilities {
        // 显示/HDMI 输出端
        public final boolean displayHdr10;
        public final boolean displayHdr10Plus;
        public final boolean displayHlg;
        public final boolean displayDolbyVision;
        public final float desiredMaxLuminance;
        public final float desiredMaxAverageLuminance;
        public final float desiredMinLuminance;
        // 解码端（盒子能否硬解）
        public final boolean dolbyVisionDecoder;
        public final boolean codecListDolbyVisionDecoder;
        public final boolean declaredDolbyVisionDecoder;
        public final boolean hevcMain10Decoder;
        public final String summary;

        // 兼容旧字段名：hdr10/hdr10Plus 表示“显示端可输出 HDR10/HDR10+”
        public final boolean hdr10;
        public final boolean hdr10Plus;
        // 兼容旧字段名：dolbyVision 表示“设备可端到端走原生杜比视界”(需同时具备解码器+显示)
        public final boolean dolbyVision;

        private Capabilities(boolean displayHdr10, boolean displayHdr10Plus, boolean displayHlg,
                             boolean displayDolbyVision, float desiredMaxLuminance,
                             float desiredMaxAverageLuminance, float desiredMinLuminance,
                             boolean dolbyVisionDecoder,
                             boolean codecListDolbyVisionDecoder,
                             boolean declaredDolbyVisionDecoder,
                             boolean hevcMain10Decoder, String summary) {
            this.displayHdr10 = displayHdr10;
            this.displayHdr10Plus = displayHdr10Plus;
            this.displayHlg = displayHlg;
            this.displayDolbyVision = displayDolbyVision;
            this.desiredMaxLuminance = desiredMaxLuminance;
            this.desiredMaxAverageLuminance = desiredMaxAverageLuminance;
            this.desiredMinLuminance = desiredMinLuminance;
            this.dolbyVisionDecoder = dolbyVisionDecoder;
            this.codecListDolbyVisionDecoder = codecListDolbyVisionDecoder;
            this.declaredDolbyVisionDecoder = declaredDolbyVisionDecoder;
            this.hevcMain10Decoder = hevcMain10Decoder;
            this.summary = summary;
            this.hdr10 = displayHdr10;
            this.hdr10Plus = displayHdr10Plus;
            this.dolbyVision = dolbyVisionDecoder && displayDolbyVision;
        }

        /** 显示端能进 HDR（HDR10/HDR10+/HLG 任一），是“能否激发电视 HDR 模式”的依据。 */
        public boolean displaySupportsHdr() {
            return displayHdr10 || displayHdr10Plus || displayHlg;
        }

        /** 能显示 HDR 但不能端到端原生 DV —— 需要把 DV 映射成 HDR10/HDR10+。 */
        public boolean supportsHdrButNotDolbyVision() {
            return displaySupportsHdr() && !dolbyVision;
        }

        /** 端到端原生杜比视界：既能解 DV 又能显示 DV。 */
        public boolean supportsNativeDolbyVision() {
            return codecListDolbyVisionDecoder && displayDolbyVision;
        }

        /**
         * 和播放路由保持一致的“原生 DV 系统播放”判定。
         * java64 手机/平板优先信任真实解码器能力，避免因为 display 能力上报不完整，
         * 前面被路由到原生 DV，后面又因为 header 判定不一致退回坏链路。
         */
        public boolean supportsNativeDolbyVisionRoute(boolean java64TouchPhone) {
            return java64TouchPhone ? dolbyVisionDecoder : supportsNativeDolbyVision();
        }

        /** 仅表示系统声明/配置里存在杜比能力，不足以作为原生 DV 路由依据。 */
        public boolean hasDeclaredDolbyVisionDecoder() {
            return declaredDolbyVisionDecoder;
        }

        /**
         * MPV/libplacebo 的 HDR 目标峰值亮度。优先使用系统公开的显示端 HDR
         * 亮度参数，避免把所有电视都粗暴映射到 1000nit。
         */
        public int hdrTargetPeakNits() {
            float peak = sanitizeLuminance(desiredMaxLuminance);
            float average = sanitizeLuminance(desiredMaxAverageLuminance);
            if (peak <= 0f) {
                peak = average > 0f ? average * 2f : 1000f;
            }
            if (average > 0f && peak > average * 8f) {
                // Some TV firmwares report a placeholder 10000nit peak. Bound it by
                // the real sustained luminance so HDR mapping does not become too dim.
                peak = average * 4f;
            }
            if (peak <= 0f || Float.isNaN(peak) || Float.isInfinite(peak)) {
                return 1000;
            }
            peak = Math.max(200f, Math.min(4000f, peak));
            return Math.round(peak);
        }
    }

    private static float sanitizeLuminance(float value) {
        if (value <= 0f || Float.isNaN(value) || Float.isInfinite(value)) {
            return -1f;
        }
        return value;
    }

    private static volatile Capabilities sCached;

    public static Capabilities query(Context context) {
        Capabilities cached = sCached;
        if (cached != null) {
            return cached;
        }
        boolean displayHdr10 = false;
        boolean displayHdr10Plus = false;
        boolean displayHlg = false;
        boolean displayDolbyVision = false;
        float desiredMaxLuminance = -1f;
        float desiredMaxAverageLuminance = -1f;
        float desiredMinLuminance = -1f;
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                Display display = displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                Display.HdrCapabilities caps = display == null ? null : display.getHdrCapabilities();
                if (caps != null) {
                    desiredMaxLuminance = caps.getDesiredMaxLuminance();
                    desiredMaxAverageLuminance = caps.getDesiredMaxAverageLuminance();
                    desiredMinLuminance = caps.getDesiredMinLuminance();
                    for (int type : caps.getSupportedHdrTypes()) {
                        if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            displayHdr10 = true;
                        } else if (isHdr10PlusType(type)) {
                            displayHdr10Plus = true;
                        } else if (type == Display.HdrCapabilities.HDR_TYPE_HLG) {
                            displayHlg = true;
                        } else if (type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) {
                            displayDolbyVision = true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        boolean codecListDolbyVisionDecoder = hasDecoderForType("video/dolby-vision");
        boolean xmlDolbyVisionDecoder = codecListDolbyVisionDecoder || hasDeclaredCodecMarker(DOLBY_VISION_CODEC_MARKERS);
        boolean propertyDolbyVisionDecoder = codecListDolbyVisionDecoder || xmlDolbyVisionDecoder || hasTruthySystemProperty(DOLBY_VISION_DECODER_PROPS);
        boolean dolbyVisionDecoder = codecListDolbyVisionDecoder || xmlDolbyVisionDecoder || propertyDolbyVisionDecoder;

        boolean codecListHevcMain10Decoder = hasHevcMain10Decoder();
        boolean xmlHevcMain10Decoder = codecListHevcMain10Decoder || hasDeclaredCodecMarker(HEVC_MAIN10_CODEC_MARKERS);
        boolean hevcMain10Decoder = codecListHevcMain10Decoder || xmlHevcMain10Decoder;

        Capabilities result = new Capabilities(displayHdr10, displayHdr10Plus, displayHlg,
                displayDolbyVision, desiredMaxLuminance, desiredMaxAverageLuminance,
                desiredMinLuminance, dolbyVisionDecoder, codecListDolbyVisionDecoder,
                xmlDolbyVisionDecoder || propertyDolbyVisionDecoder, hevcMain10Decoder,
                buildSummary(displayHdr10, displayHdr10Plus, displayHlg, displayDolbyVision,
                        desiredMaxLuminance, desiredMaxAverageLuminance, desiredMinLuminance,
                        dolbyVisionDecoder, hevcMain10Decoder,
                        codecListDolbyVisionDecoder, xmlDolbyVisionDecoder, propertyDolbyVisionDecoder,
                        codecListHevcMain10Decoder, xmlHevcMain10Decoder));
        sCached = result;
        return result;
    }

    private static boolean isHdr10PlusType(int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) {
            return true;
        }
        // Android 在 API 29+ 以数值 4 暴露 HDR10+，即使用旧 SDK 常量编译也需识别。
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == 4;
    }

    /** MediaCodecList 中是否存在某 mime 的（非编码器）解码器。 */
    private static boolean hasDecoderForType(String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type != null && type.equalsIgnoreCase(mimeType)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 是否存在支持 HEVC Main10（10bit）的解码器 —— HDR10/HLG 原生硬解的前提。 */
    private static boolean hasHevcMain10Decoder() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type == null || !type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                        continue;
                    }
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        if (caps == null) {
                            continue;
                        }
                        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                            // HEVCProfileMain10 = 2, HEVCProfileMain10HDR10 = 0x1000, HDR10Plus = 0x4000
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                    || pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                                    || pl.profile == 0x4000 /* HEVCProfileMain10HDR10Plus */) {
                                return true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasDeclaredCodecMarker(String[] markers) {
        if (markers == null || markers.length == 0) {
            return false;
        }
        for (String dirPath : CODEC_XML_DIRS) {
            File dir = new File(dirPath);
            File[] files = dir.listFiles((file, name) ->
                    name != null
                            && name.toLowerCase(Locale.US).contains("codec")
                            && name.toLowerCase(Locale.US).endsWith(".xml"));
            if (files == null || files.length == 0) {
                continue;
            }
            Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                try {
                    String content = readFileLowercase(file, 1024 * 1024);
                    if (content == null || content.isEmpty()) {
                        continue;
                    }
                    for (String marker : markers) {
                        if (marker != null && content.contains(marker.toLowerCase(Locale.US))) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static String readFileLowercase(File file, int maxBytes) {
        if (file == null || !file.isFile() || maxBytes <= 0) {
            return null;
        }
        byte[] data = new byte[Math.max(4096, Math.min(maxBytes, 1024 * 1024))];
        int total = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (total < data.length) {
                int read = input.read(data, total, data.length - total);
                if (read <= 0) {
                    break;
                }
                total += read;
            }
            if (total <= 0) {
                return null;
            }
            return new String(data, 0, total).toLowerCase(Locale.US);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasTruthySystemProperty(String[] keys) {
        if (keys == null || keys.length == 0) {
            return false;
        }
        for (String key : keys) {
            String value = readSystemProperty(key);
            if (isTruthy(value)) {
                return true;
            }
        }
        return false;
    }

    private static String readSystemProperty(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            return (String) cls.getMethod("get", String.class, String.class).invoke(null, key, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.US);
        return "1".equals(lower)
                || "true".equals(lower)
                || "yes".equals(lower)
                || "on".equals(lower)
                || "enabled".equals(lower);
    }

    private static String buildSummary(boolean displayHdr10, boolean displayHdr10Plus, boolean displayHlg,
                                       boolean displayDolbyVision, float desiredMaxLuminance,
                                       float desiredMaxAverageLuminance, float desiredMinLuminance,
                                       boolean dolbyVisionDecoder,
                                       boolean hevcMain10Decoder,
                                       boolean codecListDolbyVisionDecoder,
                                       boolean xmlDolbyVisionDecoder,
                                       boolean propertyDolbyVisionDecoder,
                                       boolean codecListHevcMain10Decoder,
                                       boolean xmlHevcMain10Decoder) {
        List<String> display = new ArrayList<>();
        if (displayDolbyVision) display.add("DV");
        if (displayHdr10Plus) display.add("HDR10+");
        if (displayHdr10) display.add("HDR10");
        if (displayHlg) display.add("HLG");
        String displayPart = display.isEmpty() ? "SDR" : android.text.TextUtils.join("/", display);
        List<String> decode = new ArrayList<>();
        if (dolbyVisionDecoder) decode.add("DV");
        if (hevcMain10Decoder) decode.add("HEVC10");
        String decodePart = decode.isEmpty() ? "none" : android.text.TextUtils.join("/", decode);
        List<String> detect = new ArrayList<>();
        if (codecListDolbyVisionDecoder) detect.add("DV-CODEC");
        else if (xmlDolbyVisionDecoder) detect.add("DV-XML");
        else if (propertyDolbyVisionDecoder) detect.add("DV-PROP");
        if (codecListHevcMain10Decoder) detect.add("HEVC10-CODEC");
        else if (xmlHevcMain10Decoder) detect.add("HEVC10-XML");
        String detectPart = detect.isEmpty() ? "" : " SRC=" + android.text.TextUtils.join("/", detect);
        String luminance = "LUM=" + formatLuminance(desiredMaxLuminance)
                + "/" + formatLuminance(desiredMaxAverageLuminance)
                + "/" + formatLuminance(desiredMinLuminance);
        return ("display=" + displayPart + " decode=" + decodePart + detectPart + " " + luminance).toUpperCase(Locale.US);
    }

    private static String formatLuminance(float value) {
        if (value <= 0f || Float.isNaN(value) || Float.isInfinite(value)) {
            return "NA";
        }
        return String.valueOf(Math.round(value));
    }
}
