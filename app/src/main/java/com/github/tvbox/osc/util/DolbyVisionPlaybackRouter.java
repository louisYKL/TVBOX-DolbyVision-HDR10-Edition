package com.github.tvbox.osc.util;

import android.content.Context;

import java.util.Map;

/**
 * Selects the single built-in playback pipeline for every video format.
 *
 * Video always stays on Android MediaCodec so the vendor hardware plane can expose
 * HDR, Dolby Vision, and motion processing. Unsupported audio is decoded inside the
 * same player and delivered as stereo PCM; it never changes the video backend.
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
        public final boolean nativeDolbyVision;
        public final int playerType;
        public final String reason;
        public final boolean forcePlayerType;

        private Decision(boolean looksLikeDolbyVision,
                         String outputMode,
                         boolean requiresHdrOutput,
                         boolean nativeDolbyVision,
                         String reason) {
            this.looksLikeDolbyVision = looksLikeDolbyVision;
            this.useCompatPlayer = false;
            this.preferHdrFallback = false;
            this.preferSdrFallback = false;
            this.needsBuiltInMapping = false;
            this.compatMode = outputMode == null ? "" : outputMode;
            this.requiresHdrOutput = requiresHdrOutput;
            this.nativeDolbyVision = nativeDolbyVision;
            this.playerType = PlayerHelper.PLAYER_TYPE_SYSTEM;
            this.reason = reason;
            this.forcePlayerType = true;
        }
    }

    public static Decision resolve(Context context,
                                   int requestedPlayerType,
                                   String url,
                                   Map<String, String> headers,
                                   String... extraHints) {
        VideoStreamProbe.Result streamProbe = VideoStreamProbe.probe(context, url, headers);
        return resolve(context, requestedPlayerType, url, headers, streamProbe, extraHints);
    }

    public static Decision resolve(Context context,
                                   int requestedPlayerType,
                                   String url,
                                   Map<String, String> headers,
                                   VideoStreamProbe.Result streamProbe,
                                   String... extraHints) {
        VideoStreamProbe.Result probe = streamProbe == null
                ? VideoStreamProbe.Result.unknown("preprobe-null")
                : streamProbe;
        VideoColorMetadata metadata = probe.colorMetadata == null
                ? VideoColorMetadata.EMPTY : probe.colorMetadata;
        boolean dolbyVision = probe.probed && probe.hasDolbyVision;
        int profile = metadata.dolbyVisionProfile > 0
                ? metadata.dolbyVisionProfile : probe.dolbyVisionProfile;
        int compatibility = metadata.dolbyVisionCompatibilityId;
        String dvBaseMode = SystemDecoderRoutePolicy.dolbyVisionBaseMode(
                profile, compatibility, metadata.colorTransfer);
        boolean dvHdrBase = dolbyVision && ("base-hdr".equals(dvBaseMode)
                || "base-hlg".equals(dvBaseMode));
        boolean hdr = metadata.hasHdrTransfer() || metadata.getHdrStaticInfo() != null
                || probe.hasHdr10Plus || (probe.hasHdr10 && !dolbyVision)
                || dvHdrBase;
        HdrDeviceSupport.Capabilities capabilities = HdrDeviceSupport.query(context);
        // This is a preflight preference, not confirmation of native output. The renderer
        // checks this stream's profile/level against actual codecs before selecting one.
        // A missing/stale display flag must not strip DV on a capable decoder.
        boolean nativeDolbyVision = dolbyVision && capabilities.codecListDolbyVisionDecoder;
        String outputMode;
        if (nativeDolbyVision) {
            outputMode = "dv-pending";
        } else if (dolbyVision) {
            outputMode = dvBaseMode;
        } else {
            outputMode = hdr ? "base-hdr" : "sdr";
        }
        boolean requiresHdrOutput = (nativeDolbyVision || hdr)
                && !"dv-no-compatible-base".equals(outputMode) && !"base-sdr".equals(outputMode);
        String reason = nativeDolbyVision
                ? "unified-system-native-dolby-vision"
                : (dolbyVision ? "unified-system-dolby-vision-base-layer"
                : (hdr ? "unified-system-hdr" : "unified-system-sdr"));
        LOG.i("echo-dolby-route route=" + reason
                + " player=" + PlayerHelper.PLAYER_TYPE_SYSTEM
                + " profile=" + probe.dolbyVisionProfile
                + " nativeDv=" + nativeDolbyVision
                + " hdr10=" + probe.hasHdr10
                + " hdr10Plus=" + probe.hasHdr10Plus
                + " audio=" + probe.primaryAudioMime
                + " probe=" + probe.summary
                + " url=" + safeSnippet(url));
        return new Decision(dolbyVision, outputMode, requiresHdrOutput, nativeDolbyVision, reason);
    }

    private static String safeSnippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 220 ? value.substring(0, 220) : value;
    }
}
