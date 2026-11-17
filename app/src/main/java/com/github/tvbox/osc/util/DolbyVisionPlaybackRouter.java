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
        /** True when this DV stream has neither an end-to-end native route nor a verified base layer. */
        public final boolean unsupportedDolbyVision;
        public final int playerType;
        public final String reason;
        public final boolean forcePlayerType;

        private Decision(boolean looksLikeDolbyVision,
                         String outputMode,
                         boolean requiresHdrOutput,
                         boolean nativeDolbyVision,
                         boolean unsupportedDolbyVision,
                         String reason) {
            this.looksLikeDolbyVision = looksLikeDolbyVision;
            this.useCompatPlayer = false;
            this.preferHdrFallback = false;
            this.preferSdrFallback = false;
            this.needsBuiltInMapping = false;
            this.compatMode = outputMode == null ? "" : outputMode;
            this.requiresHdrOutput = requiresHdrOutput;
            this.nativeDolbyVision = nativeDolbyVision;
            this.unsupportedDolbyVision = unsupportedDolbyVision;
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
        // Some MediaExtractor/proxy implementations expose the HDR10 base-layer
        // marker but omit dvcC/dvvC compatibility ID. Preserve that evidence as a
        // compatibility route. Profile 5 is intentionally excluded because its
        // IPTPQc2 signal is not an HDR10 YCbCr base.
        boolean hdr10BaseEvidence = dolbyVision
                && profile != 5
                // A bounded TV preflight can prove the HDR10 base from the container
                // color marker while it has not yet populated the extractor's hasHdr10
                // flag. Treat that explicit base-layer field as equivalent evidence.
                && (probe.hasHdr10 || probe.hasHdr10BaseLayer)
                && (profile <= 0 || profile == 7 || profile == 8)
                // Profile 7/8 are HEVC DV profiles. For an unknown profile, the
                // hasHdr10BaseLayer flag is the only reliable evidence available from
                // the bounded probe; the renderer will still validate the actual codec.
                && (probe.hasHevcVideo || "video/hevc".equals(probe.primaryVideoMime)
                || profile == 7 || profile == 8 || probe.hasHdr10BaseLayer);
        if ("dv-no-compatible-base".equals(dvBaseMode) && hdr10BaseEvidence) {
            dvBaseMode = "base-hdr";
        }
        boolean dvHdrBase = dolbyVision && ("base-hdr".equals(dvBaseMode)
                || "base-hlg".equals(dvBaseMode));
        boolean hdr = metadata.hasHdrTransfer() || metadata.getHdrStaticInfo() != null
                || probe.hasHdr10Plus || (probe.hasHdr10 && !dolbyVision)
                || dvHdrBase;
        HdrDeviceSupport.Capabilities capabilities = HdrDeviceSupport.query(context);
        // Native DV is an end-to-end contract: an exposed decoder alone is insufficient when
        // the display path cannot accept Dolby Vision.  Otherwise the renderer can select a
        // native DV candidate and produce a black video surface with audio still running.
        // The renderer still checks this stream's profile/level against the selected codec.
        boolean nativeDolbyVision = dolbyVision && capabilities.supportsNativeDolbyVision();
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
        boolean unsupportedDolbyVision = dolbyVision
                && !nativeDolbyVision
                && "dv-no-compatible-base".equals(outputMode)
                // An unknown profile is commonly the result of the bounded timeout
                // probe. Do not turn that incomplete metadata into a false user-facing
                // "no compatible layer" error; the renderer can inspect the real Format.
                && profile > 0;
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
        return new Decision(dolbyVision, outputMode, requiresHdrOutput, nativeDolbyVision,
                unsupportedDolbyVision, reason);
    }

    private static String safeSnippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 220 ? value.substring(0, 220) : value;
    }
}
