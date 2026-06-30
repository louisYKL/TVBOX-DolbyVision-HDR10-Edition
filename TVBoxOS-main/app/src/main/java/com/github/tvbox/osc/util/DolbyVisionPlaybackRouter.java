package com.github.tvbox.osc.util;

import android.content.Context;

import com.github.tvbox.osc.base.App;

import java.util.Locale;
import java.util.Map;

/**
 * 三路由策略（依据真实探测的设备能力，见 {@link HdrDeviceSupport}）：
 *  路由1：DV 源 + 设备无 DV 解码器 → 兼容播放器(MPV) 把 DV 映射成 HDR10/HDR10+（显示端不支持 HDR 时降级 SDR），并激发 HDR。
 *  路由2：DV 源 + 设备有 DV 解码器且显示端支持 DV → 系统播放器走原生解码器，激发杜比视界。
 *  路由3：普通 SDR/HDR10/HDR10+ 的 MKV/MP4 → 系统播放器原生硬解（最强性能），由设备自身解码器激活 HDR。
 *
 * 关键纠错：HDR10/HDR10+/DV 只能由视频流探测结果触发，禁止根据标题、文件名里的 DV/HDR/10Bit 字样判断。
 * 容器类型（MKV/WebM）可以根据 URL/容器探测决定播放器兼容链，但不参与 HDR/DV 类型判断。
 * 在无 DV 解码器的设备上，真实探测到的 DV 源绝不能交给系统播放器。
 *
 * 双层 DV / Profile 8 纠错：这类流带 HDR10 基础层时，不做重映射；直接用硬解播放 HDR10 基础层并请求 HDR 输出。
 * 只有 Profile 5 或探测不到 HDR10 基础层的 DV 才走兼容映射链。
 */
public final class DolbyVisionPlaybackRouter {
    private DolbyVisionPlaybackRouter() {
    }

    public static final class Decision {
        public final boolean looksLikeDolbyVision;
        public final boolean useCompatPlayer;
        public final boolean preferHdrFallback;
        public final boolean preferSdrFallback;
        public final boolean needsBuiltInMapping;
        public final String compatMode;
        public final boolean requiresHdrOutput;
        public final int playerType;
        public final String reason;

        private Decision(boolean looksLikeDolbyVision,
                         boolean useCompatPlayer,
                         boolean preferHdrFallback,
                         boolean preferSdrFallback,
                         boolean needsBuiltInMapping,
                         String compatMode,
                         boolean requiresHdrOutput,
                         int playerType,
                         String reason) {
            this.looksLikeDolbyVision = looksLikeDolbyVision;
            this.useCompatPlayer = useCompatPlayer;
            this.preferHdrFallback = preferHdrFallback;
            this.preferSdrFallback = preferSdrFallback;
            this.needsBuiltInMapping = needsBuiltInMapping;
            this.compatMode = compatMode == null ? "" : compatMode;
            this.requiresHdrOutput = requiresHdrOutput;
            this.playerType = playerType;
            this.reason = reason;
        }
    }

    public static Decision resolve(Context context, int requestedPlayerType, String url, Map<String, String> headers, String... extraHints) {
        VideoStreamProbe.Result streamProbe = VideoStreamProbe.probe(context, url, headers);
        return resolve(context, requestedPlayerType, url, headers, streamProbe, extraHints);
    }

    public static Decision resolve(Context context,
                                   int requestedPlayerType,
                                   String url,
                                   Map<String, String> headers,
                                   VideoStreamProbe.Result streamProbe,
                                   String... extraHints) {
        if (streamProbe == null) {
            streamProbe = VideoStreamProbe.Result.unknown("preprobe-null");
        }
        HdrDeviceSupport.Capabilities caps = HdrDeviceSupport.query(context);

        boolean streamDetectedDv = streamProbe.probed && streamProbe.hasDolbyVision;
        boolean matroskaLike = isMatroskaLike(url, extraHints) || streamProbe.isMatroska;
        boolean probeTimedOut = streamProbe.summary != null
                && streamProbe.summary.toLowerCase(Locale.US).contains("timeoutexception");

        // HDR/DV 只能由视频流探测结果决定，禁止根据标题/文件名里的 DV/HDR/10Bit 字样判断。
        boolean looksLikeDolbyVision = streamDetectedDv;

        boolean requiresHdrOutput = streamProbe.hasDolbyVision
                || streamProbe.hasHdr10
                || streamProbe.hasHdr10Plus;

        // 原生 DV 路由必须以“真实存在 DV 解码器 + 显示端支持 DV”为前提。
        // 仅有系统属性/XML 宣称支持，但 MediaCodecList 没有 video/dolby-vision 解码器时，
        // 32/64 位都不能冒险走原生杜比，否则会出现偏色、黑屏或只出声不出画。
        boolean localProxyMatroskaDvNeedsCompat = looksLikeDolbyVision
                && matroskaLike
                && url != null
                && url.contains("/proxy/play/")
                && (!streamProbe.hasHdr10BaseLayer || streamProbe.dolbyVisionProfile <= 0);
        boolean java64TouchPhone = context != null && App.isJava64Build() && !ScreenUtils.isTv(context);
        boolean nativeDolbyVisionRouteDevice = caps.supportsNativeDolbyVisionRoute(java64TouchPhone);
        if (nativeDolbyVisionRouteDevice) {
            // 真实具备原生杜比视界解码器 + 显示能力的设备，不应因为 local proxy / Matroska
            // 预探测缺少 hdr10Base/profile 就被错误压到兼容链。这里统一保留系统原生 DV。
            localProxyMatroskaDvNeedsCompat = false;
        }
        boolean preferNativeHdrSystem = nativeDolbyVisionRouteDevice
                && !localProxyMatroskaDvNeedsCompat;
        boolean allowSystemForHdrContainer = preferNativeHdrSystem && requiresHdrOutput;
        boolean systemCanOpenContainer = (!matroskaLike && !java64TouchPhone)
                || allowSystemForHdrContainer
                || (matroskaLike
                && !localProxyMatroskaDvNeedsCompat
                && !looksLikeDolbyVision
                && caps.hevcMain10Decoder
                && !java64TouchPhone);
        int nativeRequestedPlayerType = allowSystemForHdrContainer
                ? PlayerHelper.PLAYER_TYPE_SYSTEM
                : requestedPlayerType;

        if (looksLikeDolbyVision) {
            // 路由2：设备能端到端原生 DV 且容器系统播放器能打开 → 系统播放器原生杜比视界。
            if (nativeDolbyVisionRouteDevice && systemCanOpenContainer && !localProxyMatroskaDvNeedsCompat && !java64TouchPhone) {
                LOG.i("echo-dolby-route route=native-dv player=" + PlayerHelper.PLAYER_TYPE_SYSTEM
                        + " caps=" + caps.summary
                        + " nativeHdrSystem=" + preferNativeHdrSystem
                        + " streamDv=" + streamDetectedDv
                        + " localProxyMatroskaCompat=" + localProxyMatroskaDvNeedsCompat
                        + " probe=" + streamProbe.summary + " url=" + safeSnippet(url));
                return new Decision(true, false, false, false, false, "", true,
                        PlayerHelper.PLAYER_TYPE_SYSTEM, "native-dolby-vision");
            }

            // 64位手机/平板同样必须先具备真实 DV 解码器。
            // 只有严格满足原生解码能力时，才保留系统原生 DV 链路。
            if (java64TouchPhone && nativeDolbyVisionRouteDevice && systemCanOpenContainer && !localProxyMatroskaDvNeedsCompat) {
                LOG.i("echo-dolby-route route=native-dv-java64 player=" + PlayerHelper.PLAYER_TYPE_SYSTEM
                        + " caps=" + caps.summary
                        + " nativeHdrSystem=" + preferNativeHdrSystem
                        + " streamDv=" + streamDetectedDv
                        + " profile=" + streamProbe.dolbyVisionProfile
                        + " matroska=" + matroskaLike
                        + " localProxyMatroskaCompat=" + localProxyMatroskaDvNeedsCompat
                        + " probe=" + streamProbe.summary
                        + " url=" + safeSnippet(url));
                return new Decision(true, false, false, false, false, "", true,
                        PlayerHelper.PLAYER_TYPE_SYSTEM, "native-dolby-vision-java64");
            }

            // 双层/Profile 7/8/DV+HDR：只播放 HDR10 基础层，避免在低功耗电视上做 GPU/CPU 重映射。
            boolean canUseHdr10BaseLayer = streamProbe.hasHdr10BaseLayer
                    || (streamProbe.hasDolbyVision && streamProbe.hasHdr10)
                    || (matroskaLike && streamProbe.dolbyVisionProfile < 0)
                    || streamProbe.dolbyVisionProfile == 7
                    || streamProbe.dolbyVisionProfile == 8;
            boolean canPreferSystemHdr10BaseLayer = !App.isJava64Build()
                    && !nativeDolbyVisionRouteDevice
                    && canUseHdr10BaseLayer
                    && caps.displaySupportsHdr()
                    && caps.hevcMain10Decoder;
            if (canPreferSystemHdr10BaseLayer) {
                LOG.i("echo-dolby-route route=dv-base-hdr10-system player=" + PlayerHelper.PLAYER_TYPE_SYSTEM
                        + " caps=" + caps.summary
                        + " streamDv=" + streamDetectedDv
                        + " profile=" + streamProbe.dolbyVisionProfile
                        + " hdr10Base=" + streamProbe.hasHdr10BaseLayer
                        + " matroska=" + matroskaLike + " probe=" + streamProbe.summary
                        + " url=" + safeSnippet(url));
                return new Decision(true, false, false, false, false, "", true,
                        PlayerHelper.PLAYER_TYPE_SYSTEM,
                        "dv-hdr10-base-layer-system");
            }
            if (canUseHdr10BaseLayer && caps.displaySupportsHdr() && caps.hevcMain10Decoder) {
                LOG.i("echo-dolby-route route=dv-base-hdr10 player=" + PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT
                        + " caps=" + caps.summary
                        + " streamDv=" + streamDetectedDv
                        + " profile=" + streamProbe.dolbyVisionProfile
                        + " hdr10Base=" + streamProbe.hasHdr10BaseLayer
                        + " matroska=" + matroskaLike + " probe=" + streamProbe.summary
                        + " url=" + safeSnippet(url));
                return new Decision(true, true, true, false, false, "dv-base-hdr", true,
                        PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT,
                        "dv-hdr10-base-layer");
            }

            // Profile 5 或无 HDR10 基础层 → 兼容播放器(MPV/libplacebo)做映射。
            // 显示端不支持 HDR → 降级 SDR。
            boolean preferHdr = caps.displaySupportsHdr();
            String reason = (matroskaLike ? "dv-matroska-" : "dv-")
                    + (preferHdr ? "map-hdr" : "map-sdr")
                    + (nativeDolbyVisionRouteDevice ? "-mkv-no-system" : "-no-dv-decoder");
            LOG.i("echo-dolby-route route=dv-map player=" + PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT
                    + " caps=" + caps.summary + " preferHdr=" + preferHdr
                    + " streamDv=" + streamDetectedDv
                    + " profile=" + streamProbe.dolbyVisionProfile
                    + " hdr10Base=" + streamProbe.hasHdr10BaseLayer
                    + " matroska=" + matroskaLike + " probe=" + streamProbe.summary
                    + " url=" + safeSnippet(url));
            return new Decision(true, true, preferHdr, !preferHdr, true, preferHdr ? "map-hdr" : "map-sdr", preferHdr,
                    PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT, reason);
        }

        if (shouldRouteTv32LocalProxyHevcToSystemHdr(context, url, streamProbe, extraHints)
                && caps.displaySupportsHdr()
                && caps.hevcMain10Decoder) {
            LOG.i("echo-dolby-route route=tv32-local-proxy-hevc-system-hdr player="
                    + PlayerHelper.PLAYER_TYPE_SYSTEM
                    + " caps=" + caps.summary
                    + " videoMime=" + streamProbe.primaryVideoMime
                    + " audioMime=" + streamProbe.primaryAudioMime
                    + " hevc=" + streamProbe.hasHevcVideo
                    + " hdr10=" + streamProbe.hasHdr10
                    + " hdr10Plus=" + streamProbe.hasHdr10Plus
                    + " probe=" + streamProbe.summary
                    + " url=" + safeSnippet(url));
            return new Decision(false, false, false, false, false, "", true,
                    PlayerHelper.PLAYER_TYPE_SYSTEM,
                    "tv32-local-proxy-hevc-system-hdr");
        }

        // Huawei tv32 firmware can route local-proxy VOD through NuPlayer audio offload even
        // for AAC/MP4-like SDR files, causing silent audio plus severe video underruns. Keep
        // HDR/DV on the existing native routes, but decode SDR local-proxy VOD in MPV so audio
        // is rendered as PCM instead of broken firmware passthrough/offload.
        if (shouldRouteTv32LocalProxySdrVodToCompat(context, url, streamProbe, extraHints)) {
            LOG.i("echo-dolby-route route=tv32-local-proxy-sdr-compat player="
                    + PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT
                    + " caps=" + caps.summary
                    + " audioMime=" + streamProbe.primaryAudioMime
                    + " matroska=" + matroskaLike
                    + " probe=" + streamProbe.summary
                    + " url=" + safeSnippet(url));
            return new Decision(false, true, false, true, false, "sdr", false,
                    PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT,
                    "tv32-local-proxy-sdr-audio-safe");
        }

        // 路由3：普通 SDR/HDR10/HDR10+ 且容器系统播放器能打开（MP4/TS）→ 系统播放器原生硬解 + 原生 HDR。
        if (systemCanOpenContainer) {
            LOG.i("echo-dolby-route route=system-native player=" + nativeRequestedPlayerType
                    + " caps=" + caps.summary
                    + " nativeHdrSystem=" + preferNativeHdrSystem
                    + " hdr10=" + streamProbe.hasHdr10 + " hdr10Plus=" + streamProbe.hasHdr10Plus
                    + " matroska=" + matroskaLike
                    + " requiresHdr=" + requiresHdrOutput + " probe=" + streamProbe.summary
                    + " url=" + safeSnippet(url));
            return new Decision(false, false, false, false, false, "", requiresHdrOutput,
                    nativeRequestedPlayerType, "system-native-hdr");
        }

        // 路由4：普通 MKV/WebM（系统播放器打不开）→ 兼容播放器(MPV)。
        // HDR10/HDR10+ 源在 MPV 映射并激发 HDR；SDR 源直通。
        boolean mkvPreferHdr = (streamProbe.hasHdr10 || streamProbe.hasHdr10Plus)
                && caps.displaySupportsHdr();
        if (matroskaLike
                && !looksLikeDolbyVision
                && !streamProbe.hasHdr10
                && !streamProbe.hasHdr10Plus
                && probeTimedOut
                && caps.hevcMain10Decoder) {
            LOG.i("echo-dolby-route route=system-matroska-probe-timeout player=" + PlayerHelper.PLAYER_TYPE_SYSTEM
                    + " caps=" + caps.summary
                    + " matroska=" + matroskaLike
                    + " probe=" + streamProbe.summary
                    + " url=" + safeSnippet(url));
            return new Decision(false, false, false, false, false, "", false,
                    PlayerHelper.PLAYER_TYPE_SYSTEM, "system-matroska-probe-timeout");
        }
        LOG.i("echo-dolby-route route=mkv-compat player=" + PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT
                + " caps=" + caps.summary + " preferHdr=" + mkvPreferHdr
                + " hdr10=" + streamProbe.hasHdr10 + " hdr10Plus=" + streamProbe.hasHdr10Plus
                + " matroska=" + matroskaLike
                + " probe=" + streamProbe.summary + " url=" + safeSnippet(url));
        return new Decision(false, true, mkvPreferHdr, !mkvPreferHdr, false,
                mkvPreferHdr ? "base-hdr" : "sdr", mkvPreferHdr,
                PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT,
                mkvPreferHdr ? "matroska-compat-hdr" : "matroska-compat-sdr");
    }

    private static boolean shouldRouteTv32LocalProxyHevcToSystemHdr(Context context,
                                                                     String url,
                                                                     VideoStreamProbe.Result streamProbe,
                                                                     String... extraHints) {
        if (context == null || !ScreenUtils.isTv32Device(context) || streamProbe == null) {
            return false;
        }
        if (isHlsLike(url) || containsHlsLike(extraHints)) {
            return false;
        }
        if (!isLocalProxyVodLike(url) && !containsLocalProxyVodLike(extraHints)) {
            return false;
        }
        if (streamProbe.hasHdr10 || streamProbe.hasHdr10Plus) {
            return true;
        }
        return streamProbe.hasHevcVideo && streamProbe.hasImmersiveOrCompressedAudio();
    }

    private static boolean shouldRouteTv32LocalProxySdrVodToCompat(Context context,
                                                                    String url,
                                                                    VideoStreamProbe.Result streamProbe,
                                                                    String... extraHints) {
        if (context == null || !ScreenUtils.isTv32Device(context) || streamProbe == null) {
            return false;
        }
        if (streamProbe.hasDolbyVision || streamProbe.hasHdr10 || streamProbe.hasHdr10Plus) {
            return false;
        }
        if (isHlsLike(url) || containsHlsLike(extraHints)) {
            return false;
        }
        return isLocalProxyVodLike(url) || containsLocalProxyVodLike(extraHints);
    }

    private static boolean containsLocalProxyVodLike(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (isLocalProxyVodLike(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHlsLike(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (isHlsLike(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalProxyVodLike(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = decodeForRoute(value).toLowerCase(Locale.US);
        boolean local = lower.startsWith("http://127.0.0.1")
                || lower.startsWith("https://127.0.0.1")
                || lower.startsWith("http://localhost")
                || lower.startsWith("https://localhost");
        return local && lower.contains("/proxy/play/") && !isHlsLike(lower);
    }

    private static boolean isHlsLike(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = decodeForRoute(value).toLowerCase(Locale.US);
        return lower.contains(".m3u8")
                || lower.contains("type=hls")
                || lower.contains("format=hls")
                || lower.contains("/m3u8")
                || lower.contains("index.m3u");
    }

    private static String decodeForRoute(String value) {
        String decoded = value;
        for (int i = 0; i < 2; i++) {
            try {
                String next = java.net.URLDecoder.decode(decoded, "UTF-8");
                if (next == null || next.equals(decoded)) {
                    break;
                }
                decoded = next;
            } catch (Throwable ignored) {
                break;
            }
        }
        return decoded;
    }

    private static boolean isMatroskaLike(String url, String... extraHints) {
        if (containsMatroskaMarker(url)) {
            return true;
        }
        if (extraHints != null) {
            for (String hint : extraHints) {
                if (containsMatroskaMarker(hint)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsMatroskaMarker(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains(".mkv") || lower.contains(".webm");
    }

    private static String safeSnippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 220 ? value.substring(0, 220) : value;
    }
}
