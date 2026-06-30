package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;


import com.github.tvbox.osc.player.SystemPlayerTrackManager;
import com.github.tvbox.osc.player.TrackInfoBean;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import xyz.doikki.videoplayer.player.HttpRangeMediaDataSource;

public final class VideoStreamProbe {
    private static final byte[] MATROSKA_TRACKS_ID_BYTES = new byte[]{0x16, 0x54, (byte) 0xAE, 0x6B};
    private static final ConcurrentHashMap<String, Result> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SUBTITLE_FILE_CACHE = new ConcurrentHashMap<>();
    private static final int PROBE_BYTES = 1024 * 1024;
    private static final int LOCAL_PROXY_PROBE_BYTES = 8 * 1024 * 1024;
    private static final int JAVA64_LOCAL_PROXY_FAST_PROBE_BYTES = 512 * 1024;
    private static final int TV32_LOCAL_PROXY_DEEP_PROBE_BYTES = 24 * 1024 * 1024;
    private static final int MAX_CONTAINER_PROBE_BYTES = TV32_LOCAL_PROXY_DEEP_PROBE_BYTES;
    private static final int MATROSKA_TRACKS_TARGET_BYTES = 256 * 1024;
    private static final int MATROSKA_TRACKS_TARGET_MAX_BYTES = 8 * 1024 * 1024;
    private static final int MATROSKA_TRACKS_TARGET_REOPEN_MARGIN_BYTES = 64 * 1024;
    private static final int SAMPLE_PROBE_BYTES = 192 * 1024;
    private static final int MAX_VIDEO_SAMPLE_PROBE_COUNT = 6;
    private static final long MAIN_THREAD_LOCAL_PROXY_PROBE_TIMEOUT_MS = 2500L;
    private static final ExecutorService PROBE_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final OkHttpClient FAST_LOCAL_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build();

    private VideoStreamProbe() {
    }

    public static Result probe(Context context, String url, Map<String, String> headers) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || TextUtils.isEmpty(url)) {
            return Result.unknown("sdk-or-url");
        }
        url = PlaybackUrlNormalizer.normalizeHttpUrl(unwrapAppStreamProxy(url));
        String cacheKey = buildCacheKey(url, headers);
        Result cached = getReusableCachedResult(context, url, cacheKey);
        if (cached != null && !shouldIgnoreWeakJava64PlaybackCache(context, url, cached)) {
            return cached;
        }
        if (cached != null) {
            CACHE.remove(cacheKey);
        }
        Result result = doProbe(context, url, headers);
        cacheProbeResult(context, url, cacheKey, result);
        return result;
    }

    public static Result probeWithTimeout(Context context, String url, Map<String, String> headers, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || TextUtils.isEmpty(url)) {
            return Result.unknown("sdk-or-url");
        }
        url = PlaybackUrlNormalizer.normalizeHttpUrl(unwrapAppStreamProxy(url));
        String cacheKey = buildCacheKey(url, headers);
        Result cached = getReusableCachedResult(context, url, cacheKey);
        if (cached != null && !shouldIgnoreWeakJava64PlaybackCache(context, url, cached)) {
            return cached;
        }
        if (cached != null) {
            CACHE.remove(cacheKey);
        }
        Result java64FastProbe = tryJava64LocalProxyMatroskaFastProbe(context, url, headers);
        if (java64FastProbe != null) {
            cacheProbeResult(context, url, cacheKey, java64FastProbe);
            return java64FastProbe;
        }
        Result fastLocalProxyProbe = tryTv32LocalProxyMatroskaFastProbe(context, url, headers, cacheKey);
        if (fastLocalProxyProbe != null) {
            cacheProbeResult(context, url, cacheKey, fastLocalProxyProbe);
            return fastLocalProxyProbe;
        }
        final String probeUrl = url;
        Future<Result> future = PROBE_EXECUTOR.submit(() -> doProbeOffMain(context, probeUrl, headers));
        try {
            Result result = future.get(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
            if (result == null) {
                result = Result.unknown("timeout-null");
            }
            cacheProbeResult(context, url, cacheKey, result);
            return result;
        } catch (Throwable th) {
            future.cancel(true);
            Result fallback = probeTimeoutFallbackOffMain(probeUrl, headers,
                    isLocalProxyVideoUrl(probeUrl)
                            ? "timeout-fallback-local-proxy-byte"
                            : "timeout-fallback-byte",
                    isLocalProxyVideoUrl(probeUrl) ? LOCAL_PROXY_PROBE_BYTES : PROBE_BYTES,
                    isLocalProxyVideoUrl(probeUrl) ? false : true);
            Result result;
            if (fallback != null && fallback.probed) {
                result = copyResult(fallback,
                        true,
                        fallback.isMatroska || containsMatroskaMarkerInUrl(probeUrl),
                        "timeout-fallback:" + fallback.summary);
                cacheProbeResult(context, url, cacheKey, result);
            } else {
                result = Result.unknown("timeout-" + th.getClass().getSimpleName());
            }
            return result;
        }
    }

    public static Result probeForPlaybackPreflight(Context context, String url, Map<String, String> headers, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || TextUtils.isEmpty(url)) {
            return Result.unknown("sdk-or-url");
        }
        url = PlaybackUrlNormalizer.normalizeHttpUrl(unwrapAppStreamProxy(url));
        String cacheKey = buildCacheKey(url, headers);
        Result cached = getReusableCachedResult(context, url, cacheKey);
        if (cached != null && isStrongPlaybackPreflightResult(cached)) {
            return cached;
        }
        if (shouldUseTv32LocalProxyMatroskaFastProbe(context, url)) {
            Result fastPreflight = probeTv32LocalProxyMatroskaPlaybackPreflight(url, headers);
            if (fastPreflight != null && fastPreflight.probed) {
                cacheProbeResult(context, url, cacheKey, fastPreflight);
                return fastPreflight;
            }
        }
        // For tv32 local-proxy URLs without a .mkv suffix, peek at the byte header first.
        // If the content is actually a Matroska container (proxy serving MKV as .mp4), use the
        // fast preflight path instead of the slow MediaExtractor probe that stalls 20+ seconds.
        if (ScreenUtils.isTv32Device(context) && isLocalProxyVideoUrl(url)) {
            Result byteSniff = probeTv32LocalProxyMatroskaPlaybackPreflight(url, headers);
            if (byteSniff != null && byteSniff.probed && byteSniff.isMatroska) {
                cacheProbeResult(context, url, cacheKey, byteSniff);
                return byteSniff;
            }
        }
        return probeWithTimeout(context, url, headers, timeoutMs);
    }

    @Nullable
    private static Result tryTv32LocalProxyMatroskaFastProbe(Context context,
                                                             String url,
                                                             Map<String, String> headers,
                                                             String cacheKey) {
        if (!shouldUseTv32LocalProxyMatroskaFastProbe(context, url)) {
            return null;
        }
        Result safeResult = probeTv32LocalProxyMatroskaSafe(context, url, headers);
        if (safeResult == null) {
            return Result.unknownContainer("tv32-local-proxy-safe:null", true);
        }
        return safeResult;
    }

    private static boolean shouldUseTv32LocalProxyMatroskaFastProbe(Context context, String url) {
        return ScreenUtils.isTv32Device(context)
                && isLocalProxyVideoUrl(url)
                && containsMatroskaMarkerInUrl(url);
    }

    private static Result doProbe(Context context, String url, Map<String, String> headers) {
        if (Looper.myLooper() == Looper.getMainLooper()
                && (url.startsWith("http://") || url.startsWith("https://"))) {
            return probeExtractorOnWorker(context, url, headers,
                    isLocalProxyVideoUrl(url) ? "main-thread-local-proxy" : "main-thread-remote");
        }
        return doProbeOffMain(context, url, headers);
    }

    private static Result doProbeOffMain(Context context, String url, Map<String, String> headers) {
        if (shouldUseTv32LocalProxyMatroskaFastProbe(context, url)) {
            Result safeResult = probeTv32LocalProxyMatroskaSafe(context, url, headers);
            if (safeResult != null) {
                return safeResult;
            }
        }
        if (isLocalProxyVideoUrl(url)) {
            Result byteResult = probeContainerBytes(url, headers, "local-proxy-byte", LOCAL_PROXY_PROBE_BYTES, false);
            if (shouldShortCircuitLocalProxyByteProbe(context, byteResult)) {
                return copyResult(byteResult, true,
                        byteResult.isMatroska || containsMatroskaMarkerInUrl(url),
                        "local-proxy-byte-fast:" + byteResult.summary);
            }
            // Byte header says matroska even though the URL has no .mkv suffix (e.g. proxy
            // rewraps MKV as .mp4). Use the tv32 fast path to avoid MediaExtractor on 32-bit,
            // which stalls for 20+ seconds on large MKV files served through the local proxy.
            if (byteResult != null && byteResult.isMatroska && ScreenUtils.isTv32Device(context)) {
                Result safeResult = probeTv32LocalProxyMatroskaSafe(context, url, headers);
                if (safeResult != null) {
                    return safeResult;
                }
            }
            Result extractorResult = probeExtractorResult(context, url, headers);
            return mergeLocalProxyProbeResult(url, byteResult, extractorResult);
        }
        return probeExtractorResult(context, url, headers);
    }

    private static Result probeExtractorResult(Context context, String url, Map<String, String> headers) {
        HttpRangeMediaDataSource rangeDataSource = null;
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (shouldUseRangeBackedExtractor(context, url)) {
                rangeDataSource = new HttpRangeMediaDataSource(url, headers);
                extractor.setDataSource(rangeDataSource);
                Log.i("TVBox-runtime", "echo-probe-extractor datasource=range url=" + shrink(url));
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                extractor.setDataSource(url, headers);
            } else {
                extractor.setDataSource(url);
            }
            int trackCount = extractor.getTrackCount();
            boolean sawVideo = false;
            boolean sawDolbyVision = false;
            boolean sawHdr10 = false;
            boolean sawHdr10Plus = false;
            boolean hdr10BaseLayerDetected = false;
            int detectedDolbyVisionProfile = -1;
            String primaryVideoMime = null;
            String primaryAudioMime = null;
            boolean hasAvcVideo = false;
            boolean hasHevcVideo = false;
            boolean hasAc3Audio = false;
            boolean hasEac3Audio = false;
            boolean hasDtsAudio = false;
            boolean hasTrueHdAudio = false;
            boolean hasAtmosLikeAudio = false;
            int audioTrackCount = 0;
            String dvSummary = "";
            String hdrSummary = "";
            List<Integer> videoTracks = new ArrayList<>();
            List<SubtitleTrackMetadata> extractorSubtitleTracks = new ArrayList<>();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format == null) {
                    continue;
                }
                String mime = safeGetString(format, MediaFormat.KEY_MIME);
                String normalizedMime = normalizeMime(mime);
                String codecString = safeGetString(format, "codecs-string");
                String formatDump = safeFormatDump(format);
                String combined = ((normalizedMime == null ? "" : normalizedMime) + " "
                        + (codecString == null ? "" : codecString) + " "
                        + (formatDump == null ? "" : formatDump)).toLowerCase(Locale.US);
                SubtitleTrackMetadata subtitleTrack = buildExtractorSubtitleTrack(i, format, normalizedMime, codecString, formatDump);
                if (subtitleTrack != null) {
                    extractorSubtitleTracks.add(subtitleTrack);
                    continue;
                }
                if (!TextUtils.isEmpty(normalizedMime)
                        && !normalizedMime.startsWith("audio/")
                        && !normalizedMime.startsWith("video/")) {
                    Log.i("TVBox-runtime", "echo-probe-track other index=" + i
                            + " mime=" + shrink(normalizedMime)
                            + " codec=" + shrink(codecString)
                            + " lang=" + shrink(firstNonEmpty(
                            safeGetString(format, MediaFormat.KEY_LANGUAGE),
                            safeGetString(format, "language"),
                            safeGetString(format, "language-bcp47")))
                            + " title=" + shrink(firstNonEmpty(
                            safeGetString(format, "title"),
                            safeGetString(format, "display-title"),
                            safeGetString(format, "track-name"),
                            safeGetString(format, "handler-name"),
                            safeGetString(format, "handler_name")))
                            + " dump=" + shrink(formatDump));
                }
                if (!TextUtils.isEmpty(normalizedMime) && normalizedMime.startsWith("audio/")) {
                    audioTrackCount++;
                    if (primaryAudioMime == null) {
                        primaryAudioMime = normalizedMime;
                    }
                    boolean eac3 = containsEac3AudioMarker(combined);
                    boolean ac3 = containsAc3AudioMarker(combined);
                    boolean dts = containsDtsAudioMarker(combined);
                    boolean trueHd = containsTrueHdAudioMarker(combined);
                    boolean atmos = containsAtmosAudioMarker(combined);
                    hasEac3Audio = hasEac3Audio || eac3;
                    hasAc3Audio = hasAc3Audio || ac3;
                    hasDtsAudio = hasDtsAudio || dts;
                    hasTrueHdAudio = hasTrueHdAudio || trueHd;
                    hasAtmosLikeAudio = hasAtmosLikeAudio || atmos;
                    continue;
                }
                if (TextUtils.isEmpty(normalizedMime) || !normalizedMime.startsWith("video/")) {
                    continue;
                }
                sawVideo = true;
                if (primaryVideoMime == null) {
                    primaryVideoMime = normalizedMime;
                }
                videoTracks.add(i);
                hasAvcVideo = hasAvcVideo || containsAvcCodecMarker(combined);
                hasHevcVideo = hasHevcVideo || containsHevcCodecTextMarker(combined);
                boolean hdr10Plus = containsHdr10PlusTextMarker(combined);
                boolean hdr10 = containsHdr10TextMarker(combined) || hasHdrColorFormat(format);
                int dvProfile = extractDolbyVisionProfile(combined);
                if (dvProfile > 0) {
                    detectedDolbyVisionProfile = dvProfile;
                }
                if (containsDolbyVisionTextMarker(combined)) {
                    sawDolbyVision = true;
                    boolean hdr10BaseLayer = hdr10 || isDolbyVisionProfileWithHdr10BaseLayer(dvProfile);
                    hdr10BaseLayerDetected = hdr10BaseLayerDetected || hdr10BaseLayer;
                    if (TextUtils.isEmpty(dvSummary)) {
                        dvSummary = "track=" + i + " " + shrink(combined);
                    }
                }
                if (containsDolbyVisionCsdMarker(format)) {
                    sawDolbyVision = true;
                    boolean hdr10BaseLayer = hdr10 || isDolbyVisionProfileWithHdr10BaseLayer(dvProfile);
                    hdr10BaseLayerDetected = hdr10BaseLayerDetected || hdr10BaseLayer;
                    if (TextUtils.isEmpty(dvSummary)) {
                        dvSummary = "track=" + i + " csd-dv-marker";
                    }
                }
                if (hdr10Plus || hdr10) {
                    sawHdr10 = true;
                    sawHdr10Plus = sawHdr10Plus || hdr10Plus;
                    if (TextUtils.isEmpty(hdrSummary)) {
                        hdrSummary = "track=" + i + " hdr=" + shrink(combined);
                    }
                }
            }
            if (sawVideo) {
                if (!sawDolbyVision) {
                    for (Integer track : videoTracks) {
                        if (track != null && !isLocalProxyVideoUrl(url) && containsDolbyVisionSampleMarker(extractor, track)) {
                            sawDolbyVision = true;
                            hdr10BaseLayerDetected = hdr10BaseLayerDetected
                                    || sawHdr10
                                    || isDolbyVisionProfileWithHdr10BaseLayer(detectedDolbyVisionProfile);
                            if (TextUtils.isEmpty(dvSummary)) {
                                dvSummary = "track=" + track + " hevc-rpu-sample";
                            }
                            break;
                        }
                    }
                }
                Result byteResult = probeContainerBytes(url, headers, "extractor-fallback", PROBE_BYTES, true);
                boolean byteProbeMatroska = byteResult != null && byteResult.probed && byteResult.isMatroska;
                boolean hasDolbyVision = sawDolbyVision || (byteResult != null && byteResult.hasDolbyVision);
                boolean hasHdr10 = sawHdr10 || (byteResult != null && byteResult.hasHdr10);
                boolean hasHdr10Plus = sawHdr10Plus || (byteResult != null && byteResult.hasHdr10Plus);
                int dvProfile = detectedDolbyVisionProfile > 0
                        ? detectedDolbyVisionProfile
                        : (byteResult == null ? -1 : byteResult.dolbyVisionProfile);
                boolean hdr10BaseLayer = hdr10BaseLayerDetected
                        || (byteResult != null && byteResult.hasHdr10BaseLayer)
                        || (hasDolbyVision && hasHdr10);
                List<SubtitleTrackMetadata> mergedSubtitleTracks = mergeSubtitleTracks(
                        extractorSubtitleTracks,
                        byteResult == null ? null : byteResult.subtitleTracks);
                Log.i("TVBox-runtime", "echo-probe-subtitle merged extractor=" + extractorSubtitleTracks.size()
                        + " container=" + (byteResult == null || byteResult.subtitleTracks == null ? 0 : byteResult.subtitleTracks.size())
                        + " total=" + mergedSubtitleTracks.size());
                String summary = buildProbeSummary(hasDolbyVision, hasHdr10,
                        TextUtils.isEmpty(dvSummary) ? null : dvSummary,
                        TextUtils.isEmpty(hdrSummary) ? null : hdrSummary,
                        byteResult == null ? null : byteResult.summary,
                        byteProbeMatroska ? "matroska-container" : "video-track-no-hdr");
                return new Result(true, hasDolbyVision, hasHdr10, hasHdr10Plus, byteProbeMatroska,
                        dvProfile, hdr10BaseLayer,
                        primaryVideoMime, primaryAudioMime,
                        hasAvcVideo, hasHevcVideo,
                        hasAc3Audio, hasEac3Audio, hasDtsAudio, hasTrueHdAudio,
                        hasAtmosLikeAudio, audioTrackCount,
                        mergedSubtitleTracks,
                        summary);
            }
            return Result.unknown("no-video-track");
        } catch (Throwable th) {
            if (th instanceof android.os.NetworkOnMainThreadException) {
                return Result.unknown("extractor-NetworkOnMainThread");
            }
            Result byteResult = probeContainerBytes(url, headers, "extractor-error-" + th.getClass().getSimpleName(), PROBE_BYTES, true);
            if (byteResult != null && byteResult.probed) {
                return byteResult;
            }
            return Result.unknown("extractor-" + th.getClass().getSimpleName());
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            if (rangeDataSource != null) {
                try {
                    rangeDataSource.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Nullable
    private static Result probeTv32LocalProxyMatroskaSafe(Context context,
                                                          String url,
                                                          Map<String, String> headers) {
        Result byteResult = probeContainerBytes(url, headers, "tv32-local-proxy-byte", LOCAL_PROXY_PROBE_BYTES, false);
        if (byteResult == null) {
            return null;
        }
        Result extractorResult = probeExtractorResult(context, url, headers);
        Result merged = mergeLocalProxyProbeResult(url, byteResult, extractorResult);
        if (shouldEscalateTv32LocalProxySubtitleProbe(merged, extractorResult)) {
            Result deepByteResult = probeContainerBytes(url, headers,
                    "tv32-local-proxy-byte-deep",
                    TV32_LOCAL_PROXY_DEEP_PROBE_BYTES,
                    false);
            Result deepMerged = mergeLocalProxyProbeResult(url, deepByteResult, extractorResult);
            if (isStrongerProbeResult(deepMerged, merged)) {
                Log.i("TVBox-runtime", "echo-probe-subtitle deep-upgrade old="
                        + subtitleTrackCount(merged)
                        + " new=" + subtitleTrackCount(deepMerged)
                        + " summary=" + shrink(deepMerged == null ? "" : deepMerged.summary));
                merged = deepMerged;
            }
        }
        return copyResult(merged,
                merged.probed,
                merged.isMatroska || containsMatroskaMarkerInUrl(url),
                "tv32-local-proxy-safe:" + merged.summary);
    }

    @Nullable
    private static Result probeTv32LocalProxyMatroskaPlaybackPreflight(String url,
                                                                       Map<String, String> headers) {
        Result byteResult = probeContainerBytes(url, headers, "tv32-preflight-byte", LOCAL_PROXY_PROBE_BYTES, false);
        if (byteResult == null || !byteResult.probed) {
            return byteResult;
        }
        Result preflight = copyResult(byteResult,
                true,
                byteResult.isMatroska || containsMatroskaMarkerInUrl(url),
                "tv32-preflight:" + byteResult.summary);
        if (TextUtils.isEmpty(preflight.primaryAudioMime)
                && !TextUtils.isEmpty(byteResult.primaryAudioMime)) {
            return preflight;
        }
        return preflight;
    }

    @Nullable
    private static Result tryJava64LocalProxyMatroskaFastProbe(Context context,
                                                               String url,
                                                               Map<String, String> headers) {
        if (!shouldUseJava64LocalProxyMatroskaFastProbe(context, url)) {
            return null;
        }
        Result byteResult = probeTimeoutFallbackOffMain(url, headers,
                "java64-local-proxy-fast",
                JAVA64_LOCAL_PROXY_FAST_PROBE_BYTES,
                false);
        if (byteResult == null || !byteResult.probed) {
            return null;
        }
        if (!byteResult.hasDolbyVision && !byteResult.hasHdr10 && !byteResult.hasHdr10Plus) {
            return null;
        }
        return copyResult(byteResult,
                true,
                byteResult.isMatroska || containsMatroskaMarkerInUrl(url),
                "java64-fast:" + byteResult.summary);
    }

    private static boolean shouldUseJava64LocalProxyMatroskaFastProbe(Context context, String url) {
        return isJava64TouchPhone(context)
                && isLocalProxyVideoUrl(url)
                && containsMatroskaMarkerInUrl(url);
    }

    private static boolean shouldUseRangeBackedExtractor(Context context, String url) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ScreenUtils.isTv32Device(context)
                && isLocalProxyVideoUrl(url)
                && containsMatroskaMarkerInUrl(url);
    }

    private static Result mergeLocalProxyProbeResult(String url, Result byteResult, Result extractorResult) {
        boolean urlMatroska = containsMatroskaMarkerInUrl(url);
        boolean byteMatroska = byteResult != null && byteResult.isMatroska;
        boolean extractorMatroska = extractorResult != null && extractorResult.isMatroska;
        boolean isMatroska = urlMatroska || byteMatroska || extractorMatroska;

        if (extractorResult != null && extractorResult.probed) {
            boolean hdr10 = extractorResult.hasHdr10
                    || (byteResult != null && byteResult.hasHdr10);
            boolean hdr10Plus = extractorResult.hasHdr10Plus
                    || (byteResult != null && byteResult.hasHdr10Plus);
            boolean hasDolbyVision = extractorResult.hasDolbyVision
                    || (byteResult != null && byteResult.hasDolbyVision);
            int dvProfile = extractorResult.dolbyVisionProfile > 0
                    ? extractorResult.dolbyVisionProfile
                    : (byteResult == null ? -1 : byteResult.dolbyVisionProfile);
            boolean hdr10BaseLayer = extractorResult.hasHdr10BaseLayer
                    || (byteResult != null && byteResult.hasHdr10BaseLayer)
                    || (hasDolbyVision && hdr10);
            String summary = "local-proxy-merge:"
                    + extractorResult.summary
                    + (byteResult != null && !TextUtils.isEmpty(byteResult.summary)
                    ? "+" + byteResult.summary : "");
            return new Result(true, hasDolbyVision, hdr10, hdr10Plus, isMatroska,
                    dvProfile, hdr10BaseLayer,
                    extractorResult.primaryVideoMime,
                    extractorResult.primaryAudioMime,
                    extractorResult.hasAvcVideo,
                    extractorResult.hasHevcVideo,
                    extractorResult.hasAc3Audio,
                    extractorResult.hasEac3Audio,
                    extractorResult.hasDtsAudio,
                    extractorResult.hasTrueHdAudio,
                    extractorResult.hasAtmosLikeAudio,
                    extractorResult.audioTrackCount,
                    mergeSubtitleTracks(extractorResult.subtitleTracks, byteResult == null ? null : byteResult.subtitleTracks),
                    summary);
        }

        if (byteResult != null && byteResult.probed) {
            if (byteResult.hasHdr10 || byteResult.hasHdr10Plus || byteResult.isMatroska) {
                return copyResult(byteResult, true, isMatroska,
                        "local-proxy-byte-only:" + byteResult.summary);
            }
            return new Result(true, false, false, false, isMatroska, -1, false,
                    "local-proxy-byte-no-hdr:" + byteResult.summary);
        }

        if (extractorResult != null) {
            return copyResult(extractorResult, extractorResult.probed, isMatroska,
                    "local-proxy-extractor-only:" + extractorResult.summary);
        }
        return Result.unknownContainer("local-proxy-unavailable", isMatroska);
    }

    private static Result probeExtractorOnWorker(final Context context,
                                                 final String url,
                                                 final Map<String, String> headers,
                                                 final String prefix) {
        Future<Result> future = PROBE_EXECUTOR.submit(() -> doProbeOffMain(context, url, headers));
        try {
            Result result = future.get(MAIN_THREAD_LOCAL_PROXY_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null) {
                return Result.unknown(prefix + ":probe-null");
            }
            return copyResult(result, result.probed, result.isMatroska, prefix + ":" + result.summary);
        } catch (Throwable th) {
            future.cancel(true);
            Result fallback = probeTimeoutFallbackOffMain(url, headers,
                    prefix + ":timeout-byte",
                    isLocalProxyVideoUrl(url) ? LOCAL_PROXY_PROBE_BYTES : PROBE_BYTES,
                    isLocalProxyVideoUrl(url) ? false : true);
            if (fallback != null && fallback.probed) {
                return copyResult(fallback, fallback.probed,
                        fallback.isMatroska || containsMatroskaMarkerInUrl(url),
                        prefix + ":" + fallback.summary);
            }
            return Result.unknown(prefix + ":probe-timeout");
        }
    }

    private static Result probeContainerBytes(String url, Map<String, String> headers, String prefix) {
        return probeContainerBytes(url, headers, prefix, PROBE_BYTES, true);
    }

    private static Result probeContainerBytes(String url, Map<String, String> headers, String prefix, int probeBytes, boolean readTail) {
        if (TextUtils.isEmpty(url) || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return null;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.unknown(prefix + ":skip-main-thread");
        }
        try {
            int safeProbeBytes = Math.max(32 * 1024, Math.min(MAX_CONTAINER_PROBE_BYTES, probeBytes));
            ProbeChunk first = readRange(url, headers, 0L, safeProbeBytes - 1L, safeProbeBytes);
            List<SubtitleTrackMetadata> firstSubtitleTracks = parseMatroskaSubtitleTracks(first.data);
            MatroskaTrackSummary firstTrackSummary = parseMatroskaTrackSummary(first.data);
            Log.i("TVBox-runtime", "echo-probe-subtitle container-first count=" + firstSubtitleTracks.size()
                    + " prefix=" + prefix + " bytes=" + safeProbeBytes);
            long total = first.totalSize;
            boolean localProxyUrl = isLocalProxyVideoUrl(url);
            boolean weakInitialSubtitleProbe = firstSubtitleTracks.size() <= 1;
            boolean weakInitialTrackSummary = !hasUsefulMatroskaTrackSummary(firstTrackSummary);
            MatroskaTracksChunk targetedTracks = maybeReadTargetedMatroskaTracksChunk(
                    url, headers, first.data, total, safeProbeBytes, localProxyUrl,
                    weakInitialSubtitleProbe, weakInitialTrackSummary);
            if (targetedTracks != null) {
                firstSubtitleTracks = mergeSubtitleTracks(firstSubtitleTracks, targetedTracks.subtitleTracks);
                firstTrackSummary = mergeMatroskaTrackSummary(firstTrackSummary, targetedTracks.trackSummary);
                Log.i("TVBox-runtime", "echo-probe-subtitle container-targeted count="
                        + targetedTracks.subtitleTracks.size()
                        + " offset=" + targetedTracks.absoluteOffset
                        + " bytes=" + targetedTracks.length
                        + " prefix=" + prefix);
            }
            boolean firstHasDvText = containsDolbyVisionMarker(first.data);
            boolean firstHasDvRpu = containsHevcRpuNal(first.data, first.data.length);
            boolean firstHasHevcContext = containsHevcCodecMarker(first.data);
            if (firstHasDvText || firstHasDvRpu) {
                int dvProfile = extractDolbyVisionProfile(first.data);
                boolean dvConfirmed = isConfirmedDolbyVisionByteProbe(firstHasDvText, firstHasDvRpu,
                        firstHasHevcContext, dvProfile, localProxyUrl);
                boolean hdr10BaseLayer = containsHdr10Marker(first.data) || isDolbyVisionProfileWithHdr10BaseLayer(dvProfile);
                if (dvConfirmed) {
                    return new Result(true, true, hdr10BaseLayer, containsHdr10PlusMarker(first.data),
                        containsMatroskaMarker(first.data), dvProfile, hdr10BaseLayer,
                        firstTrackSummary.primaryVideoMime, firstTrackSummary.primaryAudioMime,
                        firstTrackSummary.hasAvcVideo, firstTrackSummary.hasHevcVideo,
                        firstTrackSummary.hasAc3Audio, firstTrackSummary.hasEac3Audio,
                        firstTrackSummary.hasDtsAudio, firstTrackSummary.hasTrueHdAudio,
                        firstTrackSummary.hasAtmosLikeAudio, firstTrackSummary.audioTrackCount,
                        firstSubtitleTracks,
                        prefix + ":header-dovi" + profileSuffix(dvProfile, hdr10BaseLayer));
                }
            }
            if (containsHdr10PlusMarker(first.data) || containsHdr10Marker(first.data)) {
                return new Result(true, false, true, containsHdr10PlusMarker(first.data), containsMatroskaMarker(first.data),
                        -1, false,
                        firstTrackSummary.primaryVideoMime, firstTrackSummary.primaryAudioMime,
                        firstTrackSummary.hasAvcVideo, firstTrackSummary.hasHevcVideo,
                        firstTrackSummary.hasAc3Audio, firstTrackSummary.hasEac3Audio,
                        firstTrackSummary.hasDtsAudio, firstTrackSummary.hasTrueHdAudio,
                        firstTrackSummary.hasAtmosLikeAudio, firstTrackSummary.audioTrackCount,
                        firstSubtitleTracks,
                        prefix + ":header-hdr");
            }
            boolean firstLooksMatroska = containsMatroskaMarker(first.data);
            if (readTail && total > safeProbeBytes && !localProxyUrl) {
                long start = Math.max(0L, total - safeProbeBytes);
                ProbeChunk tail = readRange(url, headers, start, total - 1L, safeProbeBytes);
                List<SubtitleTrackMetadata> mergedSubtitleTracks = mergeSubtitleTracks(firstSubtitleTracks, parseMatroskaSubtitleTracks(tail.data));
                MatroskaTrackSummary mergedTrackSummary = mergeMatroskaTrackSummary(firstTrackSummary, parseMatroskaTrackSummary(tail.data));
                boolean tailLooksMatroska = containsMatroskaMarker(tail.data);
                boolean tailHasDvText = containsDolbyVisionMarker(tail.data);
                boolean tailHasDvRpu = containsHevcRpuNal(tail.data, tail.data.length);
                boolean tailHasHevcContext = containsHevcCodecMarker(tail.data);
                if (tailHasDvText || tailHasDvRpu) {
                    int dvProfile = extractDolbyVisionProfile(tail.data);
                    boolean hdr10BaseLayer = containsHdr10Marker(tail.data) || containsHdr10Marker(first.data)
                            || isDolbyVisionProfileWithHdr10BaseLayer(dvProfile);
                    boolean dvConfirmed = isConfirmedDolbyVisionByteProbe(tailHasDvText, tailHasDvRpu,
                            tailHasHevcContext, dvProfile, false);
                    if (dvConfirmed) {
                        return new Result(true, true, hdr10BaseLayer, containsHdr10PlusMarker(tail.data),
                                firstLooksMatroska || tailLooksMatroska, dvProfile, hdr10BaseLayer,
                                mergedTrackSummary.primaryVideoMime, mergedTrackSummary.primaryAudioMime,
                                mergedTrackSummary.hasAvcVideo, mergedTrackSummary.hasHevcVideo,
                                mergedTrackSummary.hasAc3Audio, mergedTrackSummary.hasEac3Audio,
                                mergedTrackSummary.hasDtsAudio, mergedTrackSummary.hasTrueHdAudio,
                                mergedTrackSummary.hasAtmosLikeAudio, mergedTrackSummary.audioTrackCount,
                                mergedSubtitleTracks,
                                prefix + ":tail-dovi" + profileSuffix(dvProfile, hdr10BaseLayer));
                    }
                }
                if (containsHdr10PlusMarker(tail.data) || containsHdr10Marker(tail.data)) {
                    return new Result(true, false, true, containsHdr10PlusMarker(tail.data),
                            firstLooksMatroska || tailLooksMatroska, -1, false,
                            mergedTrackSummary.primaryVideoMime, mergedTrackSummary.primaryAudioMime,
                            mergedTrackSummary.hasAvcVideo, mergedTrackSummary.hasHevcVideo,
                            mergedTrackSummary.hasAc3Audio, mergedTrackSummary.hasEac3Audio,
                            mergedTrackSummary.hasDtsAudio, mergedTrackSummary.hasTrueHdAudio,
                            mergedTrackSummary.hasAtmosLikeAudio, mergedTrackSummary.audioTrackCount,
                            mergedSubtitleTracks,
                            prefix + ":tail-hdr");
                }
                firstSubtitleTracks = mergedSubtitleTracks;
                firstTrackSummary = mergedTrackSummary;
            }
            return new Result(true, false, false, false, firstLooksMatroska, -1, false,
                    firstTrackSummary.primaryVideoMime, firstTrackSummary.primaryAudioMime,
                    firstTrackSummary.hasAvcVideo, firstTrackSummary.hasHevcVideo,
                    firstTrackSummary.hasAc3Audio, firstTrackSummary.hasEac3Audio,
                    firstTrackSummary.hasDtsAudio, firstTrackSummary.hasTrueHdAudio,
                    firstTrackSummary.hasAtmosLikeAudio, firstTrackSummary.audioTrackCount,
                    firstSubtitleTracks,
                    prefix + ":byte-probe-no-hdr");
        } catch (Throwable th) {
            return Result.unknown(prefix + ":byte-probe-" + th.getClass().getSimpleName());
        }
    }

    private static Result probeTimeoutFallbackOffMain(final String url,
                                                      final Map<String, String> headers,
                                                      final String prefix,
                                                      final int probeBytes,
                                                      final boolean readTail) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return probeContainerBytes(url, headers, prefix, probeBytes, readTail);
        }
        Future<Result> future = PROBE_EXECUTOR.submit(() -> probeContainerBytes(url, headers, prefix, probeBytes, readTail));
        try {
            return future.get(isLocalProxyVideoUrl(url) ? 2200L : 3000L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            future.cancel(true);
            return null;
        }
    }

    @Nullable
    private static Result getReusableCachedResult(Context context, String url, String cacheKey) {
        Result cached = CACHE.get(cacheKey);
        if (shouldReprobeWeakTv32LocalProxyResult(context, url, cached)) {
            CACHE.remove(cacheKey);
            return null;
        }
        return cached;
    }

    private static void cacheProbeResult(Context context,
                                         String url,
                                         String cacheKey,
                                         @Nullable Result result) {
        if (result == null) {
            CACHE.remove(cacheKey);
            return;
        }
        if (shouldReprobeWeakTv32LocalProxyResult(context, url, result)) {
            CACHE.remove(cacheKey);
            return;
        }
        if (shouldIgnoreWeakJava64PlaybackCache(context, url, result)) {
            CACHE.remove(cacheKey);
            return;
        }
        CACHE.put(cacheKey, result);
    }

    private static boolean shouldReprobeWeakTv32LocalProxyResult(Context context,
                                                                 String url,
                                                                 @Nullable Result result) {
        if (result == null
                || !ScreenUtils.isTv32Device(context)
                || !isLocalProxyVideoUrl(url)
                || !containsMatroskaMarkerInUrl(url)) {
            return false;
        }
        boolean weakSubtitleMetadata = result.subtitleTracks == null || result.subtitleTracks.size() <= 1;
        boolean weakVideoMetadata = TextUtils.isEmpty(result.primaryVideoMime)
                && !result.hasAvcVideo
                && !result.hasHevcVideo;
        boolean weakAudioMetadata = TextUtils.isEmpty(result.primaryAudioMime)
                && result.audioTrackCount <= 0
                && !result.hasImmersiveOrCompressedAudio();
        return weakSubtitleMetadata && (weakVideoMetadata || weakAudioMetadata);
    }

    private static boolean isStrongPlaybackPreflightResult(@Nullable Result result) {
        if (result == null || !result.probed) {
            return false;
        }
        if (result.hasDolbyVision || result.hasHdr10 || result.hasHdr10Plus) {
            return true;
        }
        return result.isMatroska
                && (!TextUtils.isEmpty(result.primaryVideoMime)
                || !TextUtils.isEmpty(result.primaryAudioMime)
                || result.hasHevcVideo
                || result.hasAvcVideo
                || result.audioTrackCount > 0);
    }

    private static boolean shouldIgnoreWeakJava64PlaybackCache(Context context,
                                                               String url,
                                                               @Nullable Result result) {
        if (result == null
                || !isJava64TouchPhone(context)
                || !isLocalProxyVideoUrl(url)
                || !containsMatroskaMarkerInUrl(url)) {
            return false;
        }
        if (!result.probed) {
            return true;
        }
        boolean timeoutLike = !TextUtils.isEmpty(result.summary)
                && result.summary.toLowerCase(Locale.US).contains("timeout");
        boolean weakHdrMetadata = !result.hasDolbyVision
                && !result.hasHdr10
                && !result.hasHdr10Plus;
        boolean weakTrackMetadata = TextUtils.isEmpty(result.primaryVideoMime)
                && TextUtils.isEmpty(result.primaryAudioMime)
                && !result.hasAvcVideo
                && !result.hasHevcVideo
                && result.audioTrackCount <= 0
                && !result.hasImmersiveOrCompressedAudio();
        return timeoutLike || (weakHdrMetadata && weakTrackMetadata);
    }

    private static boolean shouldEscalateTv32LocalProxySubtitleProbe(@Nullable Result merged,
                                                                     @Nullable Result extractorResult) {
        if (merged == null || !merged.probed || !merged.isMatroska) {
            return false;
        }
        int mergedSubtitleCount = subtitleTrackCount(merged);
        int mergedUsableTextSubtitleCount = countUsableTextSubtitleTracks(merged == null ? null : merged.subtitleTracks);
        boolean extractorHasSubtitleTracks = extractorResult != null
                && extractorResult.subtitleTracks != null
                && !extractorResult.subtitleTracks.isEmpty();
        if (extractorHasSubtitleTracks) {
            return false;
        }
        if (mergedUsableTextSubtitleCount > 1) {
            return false;
        }
        if (mergedSubtitleCount <= 1
                || mergedUsableTextSubtitleCount == 0
                || hasOnlyWeakGenericChineseSubtitle(merged == null ? null : merged.subtitleTracks)) {
            Log.i("TVBox-runtime", "echo-probe-subtitle escalate-deep count="
                    + mergedSubtitleCount
                    + " usable=" + mergedUsableTextSubtitleCount
                    + " summary=" + shrink(merged.summary));
            return true;
        }
        boolean weakVideoMetadata = TextUtils.isEmpty(merged.primaryVideoMime)
                && !merged.hasAvcVideo
                && !merged.hasHevcVideo;
        boolean weakAudioMetadata = TextUtils.isEmpty(merged.primaryAudioMime)
                && merged.audioTrackCount <= 0
                && !merged.hasImmersiveOrCompressedAudio();
        return weakVideoMetadata || weakAudioMetadata;
    }

    private static int countUsableTextSubtitleTracks(@Nullable List<SubtitleTrackMetadata> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SubtitleTrackMetadata track : tracks) {
            if (track != null && !track.bitmap) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasOnlyWeakGenericChineseSubtitle(@Nullable List<SubtitleTrackMetadata> tracks) {
        if (tracks == null || tracks.size() != 1) {
            return false;
        }
        SubtitleTrackMetadata track = tracks.get(0);
        if (track == null || track.bitmap) {
            return true;
        }
        String language = safeLowerLocal(firstNonEmpty(track.languageBcp47, track.language));
        String title = safeLowerLocal(track.title);
        return TextUtils.isEmpty(title)
                && ("zh".equals(language)
                || "chi".equals(language)
                || "zho".equals(language)
                || "cmn".equals(language));
    }

    private static boolean isStrongerProbeResult(@Nullable Result candidate, @Nullable Result baseline) {
        if (candidate == null || !candidate.probed) {
            return false;
        }
        if (baseline == null || !baseline.probed) {
            return true;
        }
        int candidateSubtitleCount = subtitleTrackCount(candidate);
        int baselineSubtitleCount = subtitleTrackCount(baseline);
        if (candidateSubtitleCount != baselineSubtitleCount) {
            return candidateSubtitleCount > baselineSubtitleCount;
        }
        return probeMetadataScore(candidate) > probeMetadataScore(baseline);
    }

    private static int subtitleTrackCount(@Nullable Result result) {
        return result == null || result.subtitleTracks == null ? 0 : result.subtitleTracks.size();
    }

    private static int probeMetadataScore(@Nullable Result result) {
        if (result == null) {
            return Integer.MIN_VALUE;
        }
        int score = subtitleTrackCount(result) * 100;
        if (!TextUtils.isEmpty(result.primaryVideoMime)) score += 25;
        if (!TextUtils.isEmpty(result.primaryAudioMime)) score += 25;
        if (result.hasAvcVideo) score += 8;
        if (result.hasHevcVideo) score += 8;
        if (result.audioTrackCount > 0) score += 12;
        if (result.hasImmersiveOrCompressedAudio()) score += 10;
        return score;
    }

    private static int scoreMatroskaTracksChunk(@Nullable MatroskaTracksChunk chunk) {
        if (chunk == null) {
            return Integer.MIN_VALUE;
        }
        int score = chunk.subtitleTracks.size() * 140;
        MatroskaTrackSummary summary = chunk.trackSummary;
        if (summary != null) {
            score += summary.trackEntryCount * 18;
            score += summary.videoTrackCount * 20;
            score += summary.audioTrackCount * 16;
            score += summary.subtitleTrackCount * 26;
            if (!TextUtils.isEmpty(summary.primaryVideoMime)) score += 36;
            if (!TextUtils.isEmpty(summary.primaryAudioMime)) score += 36;
            if (summary.hasHevcVideo) score += 12;
            if (summary.hasAvcVideo) score += 8;
            if (summary.hasImmersiveOrCompressedAudio()) score += 10;
        }
        if (chunk.declaredTotalEnd > 0
                && chunk.declaredTotalEnd < 2048
                && chunk.subtitleTracks.size() <= 1
                && (summary == null || summary.trackEntryCount <= 1)) {
            score -= 260;
        }
        return score;
    }

    private static boolean shouldShortCircuitLocalProxyByteProbe(Context context, Result byteResult) {
        if (byteResult == null || !byteResult.probed) {
            return false;
        }
        if (ScreenUtils.isTv32Device(context)) {
            boolean weakSubtitleMetadata = byteResult.subtitleTracks == null
                    || byteResult.subtitleTracks.size() <= 1;
            boolean weakAudioMetadata = TextUtils.isEmpty(byteResult.primaryAudioMime)
                    && byteResult.audioTrackCount <= 0
                    && !byteResult.hasImmersiveOrCompressedAudio();
            boolean weakVideoMetadata = TextUtils.isEmpty(byteResult.primaryVideoMime)
                    && !byteResult.hasAvcVideo
                    && !byteResult.hasHevcVideo;
            // tv32 这类 HDR/DV Matroska 在 local-proxy 只看字节头时，
            // 很容易只拿到 dovi/header、缺失音频元数据，或一个假的单字幕提示，导致：
            // 1. 音频能力判断错误
            // 2. 字幕列表不完整
            // 3. 系统链被错误地当成“只有一个 chi 字幕”
            // 因此 tv32 上这类流必须继续跑 extractor 合并，不允许在 byte probe 提前返回。
            if (byteResult.hasDolbyVision
                    || byteResult.hasHdr10
                    || byteResult.hasHdr10Plus
                    || weakSubtitleMetadata
                    || weakAudioMetadata
                    || weakVideoMetadata) {
                return false;
            }
        }
        if (isJava64TouchPhone(context)
                && (byteResult.hasDolbyVision || byteResult.hasHdr10 || byteResult.hasHdr10Plus)) {
            boolean weakAudioMetadata = TextUtils.isEmpty(byteResult.primaryAudioMime)
                    && byteResult.audioTrackCount <= 0
                    && !byteResult.hasImmersiveOrCompressedAudio();
            boolean weakVideoMetadata = TextUtils.isEmpty(byteResult.primaryVideoMime)
                    && !byteResult.hasAvcVideo
                    && !byteResult.hasHevcVideo;
            boolean weakTrackMetadata = weakAudioMetadata || weakVideoMetadata;
            if (weakTrackMetadata) {
                return false;
            }
        }
        if (byteResult.hasDolbyVision) {
            // Local proxy DV byte sniff often only sees dovi headers and misses the
            // actual base-layer / codec / audio track metadata. Returning early here
            // pushes tv32 into the wrong compat mode and wrong passthrough decision.
            if (byteResult.dolbyVisionProfile <= 0 && !byteResult.hasHdr10BaseLayer) {
                return false;
            }
            return true;
        }
        if (byteResult.hasHdr10 || byteResult.hasHdr10Plus) {
            return true;
        }
        return byteResult.isMatroska;
    }

    private static boolean isJava64TouchPhone(Context context) {
        try {
            if (context == null) {
                return false;
            }
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                return false;
            }
            if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                    || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                return false;
            }
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null && tm.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE) {
                    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? android.os.Process.is64Bit()
                            : Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
                }
            } catch (Throwable ignored) {
            }
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? android.os.Process.is64Bit()
                    : Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLocalProxyVideoUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
                return false;
            }
            if (path == null) {
                return false;
            }
            String lower = url.toLowerCase(Locale.US);
            return path.contains("/proxy/play/")
                    || ("/proxy".equals(path) && lower.contains("go=stream"))
                    || lower.contains(".mkv")
                    || lower.contains(".mp4")
                    || lower.contains(".webm");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ProbeChunk readRange(String url, Map<String, String> headers, long start, long end) throws Exception {
        return readRange(url, headers, start, end, PROBE_BYTES);
    }

    private static ProbeChunk readRange(String url, Map<String, String> headers, long start, long end, int maxBytes) throws Exception {
        url = unwrapAppStreamProxy(url);
        boolean localProxy = isLocalProxyVideoUrl(url);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + start + "-" + end)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close");
        HashMap<String, String> cleanHeaders = new HashMap<>();
        if (headers != null) {
            cleanHeaders.putAll(headers);
        }
        for (Map.Entry<String, String> entry : cleanHeaders.entrySet()) {
            if (!TextUtils.isEmpty(entry.getKey()) && !TextUtils.isEmpty(entry.getValue())) {
                builder.header(entry.getKey(), entry.getValue().trim());
            }
        }
        Response response = (localProxy ? FAST_LOCAL_CLIENT : CLIENT).newCall(builder.build()).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            byte[] data = readLimited(response.body().byteStream(), maxBytes);
            long total = parseTotalFromContentRange(response.header("Content-Range"));
            if (total < 0L) {
                long length = response.body().contentLength();
                total = length > 0L ? length : -1L;
            }
            return new ProbeChunk(data, total);
        } finally {
            response.close();
        }
    }

    private static String unwrapAppStreamProxy(String url) {
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    || !"/proxy".equals(path)
                    || !"stream".equalsIgnoreCase(uri.getQueryParameter("go"))) {
                return url;
            }
            String nestedUrl = uri.getQueryParameter("url");
            if (TextUtils.isEmpty(nestedUrl)) {
                return url;
            }
            return PlaybackUrlNormalizer.normalizeHttpUrl(nestedUrl);
        } catch (Throwable ignored) {
        }
        return url;
    }

    private static byte[] readLimited(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int remaining = limit;
        while (remaining > 0) {
            int read = stream.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    private static long parseTotalFromContentRange(String value) {
        if (TextUtils.isEmpty(value)) {
            return -1L;
        }
        try {
            int slash = value.lastIndexOf('/');
            if (slash < 0 || slash + 1 >= value.length()) {
                return -1L;
            }
            String total = value.substring(slash + 1).trim();
            if ("*".equals(total)) {
                return -1L;
            }
            return Long.parseLong(total);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static boolean containsDolbyVisionMarker(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        String text = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        return containsDolbyVisionTextMarker(text)
                || text.contains("dvc1")
                || text.contains("dvh1")
                || text.contains("dvhe")
                || text.contains("dvcc")
                || text.contains("dvvc")
                || text.contains("dvv1")
                || text.contains("dovi configuration")
                || text.contains("dolby vision")
                || text.contains("杜比视界")
                || text.contains("杜比世界")
                || text.contains("dolby_vision")
                || text.contains("dolby-vision")
                || text.contains("dv profile")
                || text.contains("dv_profile")
                || text.contains("dolbyvision");
    }

    private static boolean containsMatroskaMarker(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        if (data.length >= 4
                && (data[0] & 0xff) == 0x1a
                && (data[1] & 0xff) == 0x45
                && (data[2] & 0xff) == 0xdf
                && (data[3] & 0xff) == 0xa3) {
            return true;
        }
        String text = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        return containsMatroskaMarker(text);
    }

    private static boolean containsMatroskaMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
            return lower.contains(".mkv")
                || lower.contains("matroska")
                || lower.contains("webm")
                || lower.contains("video/x-matroska")
                || lower.contains("video/webm");
    }

    private static boolean containsMatroskaMarkerInUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            String decoded = java.net.URLDecoder.decode(url, "UTF-8");
            return containsMatroskaMarker(decoded);
        } catch (Throwable ignored) {
            return containsMatroskaMarker(url);
        }
    }

    private static boolean containsDolbyVisionTextMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("video/dolby-vision")
                || text.contains("dovi configuration")
                || lower.contains("杜比视界")
                || lower.contains("杜比世界")
                || lower.contains("杜比视觉")
                || lower.contains("dolby vision")
                || lower.contains("dolby_vision")
                || lower.contains("dolby-vision")
                || lower.contains("dolbyvision")
                || lower.contains("dv profile")
                || lower.contains("dv_profile")
                || lower.contains("dv-profile")
                || lower.contains("dvhe")
                || lower.contains("dvh1");
    }

    private static int extractDolbyVisionProfile(byte[] data) {
        if (data == null || data.length == 0) {
            return -1;
        }
        String text = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        int fromText = extractDolbyVisionProfile(text);
        if (fromText > 0) {
            return fromText;
        }
        int fromConfig = extractDolbyVisionProfileFromConfigRecord(data, data.length);
        return fromConfig > 0 ? fromConfig : -1;
    }

    private static int extractDolbyVisionProfile(String text) {
        if (TextUtils.isEmpty(text)) {
            return -1;
        }
        String lower = text.toLowerCase(Locale.US);
        int value = firstIntAfter(lower, "dv_profile");
        if (value > 0) return value;
        value = firstIntAfter(lower, "dv profile");
        if (value > 0) return value;
        value = firstIntAfter(lower, "dv-profile");
        if (value > 0) return value;
        value = firstIntAfter(lower, "profile=");
        if (value > 0 && lower.contains("dovi")) return value;
        value = firstIntAfter(lower, "dvhe.");
        if (value > 0) return value / 10;
        value = firstIntAfter(lower, "dvh1.");
        if (value > 0) return value / 10;
        if (lower.contains("profile 5") || lower.contains("profile: 5")) return 5;
        if (lower.contains("profile 7") || lower.contains("profile: 7")) return 7;
        if (lower.contains("profile 8") || lower.contains("profile: 8")) return 8;
        return -1;
    }

    private static int extractDolbyVisionProfileFromConfigRecord(byte[] data, int length) {
        int safeLength = Math.min(length, data.length);
        for (int i = 0; i + 4 < safeLength; i++) {
            if (data[i] == 'd'
                    && (data[i + 1] == 'v' || data[i + 1] == 'V')
                    && (data[i + 2] == 'c' || data[i + 2] == 'C' || data[i + 2] == 'v' || data[i + 2] == 'V')
                    && (data[i + 3] == 'C' || data[i + 3] == 'c' || data[i + 3] == '1')) {
                int payload = i + 4;
                if (payload + 1 < safeLength) {
                    int profile = (data[payload + 1] & 0x7f) >> 1;
                    if (profile > 0 && profile <= 12) {
                        return profile;
                    }
                }
            }
        }
        return -1;
    }

    private static int firstIntAfter(String lower, String marker) {
        if (TextUtils.isEmpty(lower) || TextUtils.isEmpty(marker)) {
            return -1;
        }
        int index = lower.indexOf(marker);
        if (index < 0) {
            return -1;
        }
        int cursor = index + marker.length();
        while (cursor < lower.length()) {
            char ch = lower.charAt(cursor);
            if (ch >= '0' && ch <= '9') {
                int start = cursor;
                while (cursor < lower.length()) {
                    ch = lower.charAt(cursor);
                    if (ch < '0' || ch > '9') {
                        break;
                    }
                    cursor++;
                }
                try {
                    return Integer.parseInt(lower.substring(start, cursor));
                } catch (Throwable ignored) {
                    return -1;
                }
            }
            if (ch != ' ' && ch != ':' && ch != '=' && ch != '_' && ch != '-' && ch != '.') {
                return -1;
            }
            cursor++;
        }
        return -1;
    }

    private static boolean isDolbyVisionProfileWithHdr10BaseLayer(int dvProfile) {
        return dvProfile == 7 || dvProfile == 8;
    }

    private static String profileSuffix(int dvProfile, boolean hdr10BaseLayer) {
        if (dvProfile <= 0 && !hdr10BaseLayer) {
            return "";
        }
        return " profile=" + dvProfile + " hdr10Base=" + hdr10BaseLayer;
    }

    private static boolean containsHdr10TextMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("hdr10")
                || lower.contains("smpte2084")
                || lower.contains("smpte 2084")
                || lower.contains("arib-std-b67")
                || lower.contains("bt2020")
                || lower.contains("bt.2020")
                || lower.contains("color-transfer=6")
                || lower.contains("color-transfer=7")
                || lower.contains("color-standard=6")
                || lower.contains("hdr-static-info");
    }

    private static boolean containsHdr10PlusTextMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("hdr10+")
                || lower.contains("hdr10plus")
                || lower.contains("hdr10_plus")
                || lower.contains("hdr10-plus")
                || lower.contains("smpte2094")
                || lower.contains("smpte 2094");
    }

    private static boolean hasHdrColorFormat(MediaFormat format) {
        if (format == null) {
            return false;
        }
        if (format.containsKey("hdr-static-info")) {
            return true;
        }
        int transfer = safeGetInteger(format, "color-transfer", -1);
        int standard = safeGetInteger(format, "color-standard", -1);
        return transfer == 6 || transfer == 7 || standard == 6;
    }

    private static int safeGetInteger(MediaFormat format, String key, int fallback) {
        try {
            if (format != null && format.containsKey(key)) {
                return format.getInteger(key);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static boolean containsDolbyVisionCsdMarker(MediaFormat format) {
        return containsDolbyVisionCsdMarker(format, "csd-0")
                || containsDolbyVisionCsdMarker(format, "csd-1")
                || containsDolbyVisionCsdMarker(format, "csd-2");
    }

    private static boolean containsDolbyVisionCsdMarker(MediaFormat format, String key) {
        if (format == null || TextUtils.isEmpty(key) || !format.containsKey(key)) {
            return false;
        }
        try {
            ByteBuffer buffer = format.getByteBuffer(key);
            if (buffer == null) {
                return false;
            }
            ByteBuffer copy = buffer.duplicate();
            byte[] data = new byte[copy.remaining()];
            copy.get(data);
            return containsDolbyVisionMarker(data) || containsHevcRpuNal(data, data.length);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsDolbyVisionSampleMarker(MediaExtractor extractor, int videoTrack) {
        try {
            extractor.selectTrack(videoTrack);
        } catch (Throwable ignored) {
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocate(SAMPLE_PROBE_BYTES);
            byte[] sample = new byte[SAMPLE_PROBE_BYTES];
            int samples = 0;
            while (samples < MAX_VIDEO_SAMPLE_PROBE_COUNT) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size <= 0) {
                    break;
                }
                int copy = Math.min(size, SAMPLE_PROBE_BYTES);
                buffer.position(0);
                buffer.get(sample, 0, copy);
                if (containsHevcRpuNal(sample, copy) || containsDolbyVisionMarker(sample, copy)) {
                    return true;
                }
                samples++;
                if (!extractor.advance()) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                extractor.unselectTrack(videoTrack);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean containsDolbyVisionMarker(byte[] data, int length) {
        if (data == null || length <= 0) {
            return false;
        }
        int safeLength = Math.min(length, data.length);
        String text = new String(data, 0, safeLength, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        return text.contains("dvc1")
                || text.contains("dvh1")
                || text.contains("dvhe")
                || text.contains("dvcc")
                || text.contains("dvvc")
                || text.contains("dvv1")
                || text.contains("dovi configuration")
                || text.contains("dolby vision");
    }

    private static boolean isConfirmedDolbyVisionByteProbe(boolean hasDvTextMarker,
                                                           boolean hasDvRpuMarker,
                                                           boolean hasHevcContext,
                                                           int dvProfile,
                                                           boolean localProxyByteProbe) {
        if (localProxyByteProbe) {
            if (dvProfile > 0 && hasHevcContext) {
                return true;
            }
            return hasDvRpuMarker && hasHevcContext;
        }
        if (dvProfile > 0) {
            return true;
        }
        if (hasDvRpuMarker) {
            return hasHevcContext || hasDvTextMarker;
        }
        if (!hasDvTextMarker) {
            return false;
        }
        return !localProxyByteProbe;
    }

    private static boolean containsHevcCodecMarker(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        String text = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        return containsHevcCodecTextMarker(text);
    }

    private static boolean containsHevcCodecTextMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        return text.contains("video/hevc")
                || text.contains("hev1")
                || text.contains("hvc1")
                || text.contains("hevc")
                || text.contains("h265")
                || text.contains("v_mpegh/iso/hevc")
                || text.contains("v_mpegh/iso/hevc");
    }

    private static boolean containsHdr10Marker(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        String text = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        return containsHdr10TextMarker(text) || containsMatroskaHdrColorMarker(data);
    }

    private static boolean containsHdr10PlusMarker(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        String text = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        return containsHdr10PlusTextMarker(text);
    }

    private static boolean containsHevcRpuNal(byte[] data, int length) {
        if (data == null || length < 6) {
            return false;
        }
        int safeLength = Math.min(length, data.length);
        if (containsAnnexBHevcRpuNal(data, safeLength)) {
            return true;
        }
        return containsLengthPrefixedHevcRpuNal(data, safeLength, 4)
                || containsLengthPrefixedHevcRpuNal(data, safeLength, 2);
    }

    private static boolean containsMatroskaHdrColorMarker(byte[] data) {
        if (data == null || data.length < 8 || !containsMatroskaMarker(data)) {
            return false;
        }
        boolean bt2020Primaries = false;
        boolean bt2020Matrix = false;
        boolean pqOrHlgTransfer = false;
        boolean masteringMetadata = false;
        for (int i = 0; i + 4 < data.length; i++) {
            int id = ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
            if (id == 0x55BA) { // TransferCharacteristics
                long value = readEbmlUIntElement(data, i + 2);
                if (value == 16L || value == 18L) {
                    pqOrHlgTransfer = true;
                }
            } else if (id == 0x55BB) { // Primaries
                long value = readEbmlUIntElement(data, i + 2);
                if (value == 9L) {
                    bt2020Primaries = true;
                }
            } else if (id == 0x55B1) { // MatrixCoefficients
                long value = readEbmlUIntElement(data, i + 2);
                if (value == 9L) {
                    bt2020Matrix = true;
                }
            } else if (id == 0x55BC || id == 0x55BD || id == 0x55D0) {
                masteringMetadata = true;
            }
            if (pqOrHlgTransfer || (masteringMetadata && (bt2020Primaries || bt2020Matrix))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static MatroskaTracksChunk maybeReadTargetedMatroskaTracksChunk(String url,
                                                                            Map<String, String> headers,
                                                                            byte[] firstData,
                                                                            long totalSize,
                                                                            int firstProbeBytes,
                                                                            boolean localProxyUrl,
                                                                            boolean weakInitialSubtitleProbe,
                                                                            boolean weakInitialTrackSummary) {
        if (firstData == null || firstData.length < 16 || totalSize <= 0L) {
            return null;
        }
        boolean aggressiveReopen = localProxyUrl && (weakInitialSubtitleProbe || weakInitialTrackSummary);
        List<Long> candidateOffsets = findMatroskaTracksAbsoluteOffsets(firstData, aggressiveReopen);
        if (candidateOffsets.isEmpty()) {
            return null;
        }
        int firstReadableBytes = Math.max(0, firstData.length);
        MatroskaTracksChunk bestChunk = null;
        int bestScore = Integer.MIN_VALUE;
        for (Long candidateOffset : candidateOffsets) {
            if (candidateOffset == null || candidateOffset < 0L) {
                continue;
            }
            MatroskaTracksChunk candidate = readTargetedMatroskaTracksChunkAt(
                    url,
                    headers,
                    totalSize,
                    localProxyUrl,
                    aggressiveReopen,
                    firstReadableBytes,
                    candidateOffset);
            if (candidate == null) {
                continue;
            }
            int score = scoreMatroskaTracksChunk(candidate);
            Log.i("TVBox-runtime", "echo-probe-subtitle targeted-candidate offset="
                    + candidate.absoluteOffset
                    + " score=" + score
                    + " subtitles=" + candidate.subtitleTracks.size()
                    + " entries=" + candidate.trackSummary.trackEntryCount
                    + " declaredEnd=" + candidate.declaredTotalEnd);
            if (bestChunk == null || score > bestScore) {
                bestChunk = candidate;
                bestScore = score;
            }
        }
        return bestChunk;
    }

    @Nullable
    private static MatroskaTracksChunk readTargetedMatroskaTracksChunkAt(String url,
                                                                         Map<String, String> headers,
                                                                         long totalSize,
                                                                         boolean localProxyUrl,
                                                                         boolean aggressiveReopen,
                                                                         int firstReadableBytes,
                                                                         long tracksOffset) {
        if (tracksOffset == 0L) {
            return null;
        }
        long remainingBytesInFirstChunk = firstReadableBytes - tracksOffset;
        if (tracksOffset < firstReadableBytes
                && !aggressiveReopen
                && firstReadableBytes >= Math.min(MATROSKA_TRACKS_TARGET_BYTES, Math.max(0L, remainingBytesInFirstChunk))
                && remainingBytesInFirstChunk >= MATROSKA_TRACKS_TARGET_REOPEN_MARGIN_BYTES) {
            return null;
        }
        if (localProxyUrl && tracksOffset < firstReadableBytes) {
            if (!aggressiveReopen
                    && remainingBytesInFirstChunk >= MATROSKA_TRACKS_TARGET_REOPEN_MARGIN_BYTES) {
                return null;
            }
        }
        long safeStart = Math.max(0L, tracksOffset);
        long maxReadable = Math.max(0L, totalSize - safeStart);
        if (maxReadable <= 0L) {
            return null;
        }
        int targetBytes = (int) Math.min(
                aggressiveReopen ? MATROSKA_TRACKS_TARGET_MAX_BYTES : MATROSKA_TRACKS_TARGET_BYTES,
                maxReadable);
        if (targetBytes < 4) {
            return null;
        }
        ProbeChunk target;
        try {
            target = readRange(url, headers, safeStart, safeStart + targetBytes - 1L, targetBytes);
        } catch (Throwable ignored) {
            return null;
        }
        byte[] targetedData = target.data;
        if (targetedData == null || targetedData.length < 4) {
            return null;
        }
        int expandedBytes = targetedData.length;
        int declaredTotalEnd = -1;
        EbmlElement tracksElement = readEbmlElementHeader(targetedData, 0, targetedData.length);
        if (tracksElement != null && tracksElement.id == 0x1654AE6BL) {
            declaredTotalEnd = tracksElement.declaredTotalEnd;
            boolean truncated = declaredTotalEnd < 0 || declaredTotalEnd > targetedData.length;
            if (truncated) {
                long desiredBytesLong = declaredTotalEnd > 0
                        ? declaredTotalEnd
                        : Math.min(maxReadable, MATROSKA_TRACKS_TARGET_MAX_BYTES);
                int desiredBytes = (int) Math.min(
                        Math.max(targetedData.length, desiredBytesLong),
                        Math.min(maxReadable, (long) MATROSKA_TRACKS_TARGET_MAX_BYTES));
                if (desiredBytes > targetedData.length) {
                    try {
                        ProbeChunk expanded = readRange(url, headers,
                                safeStart,
                                safeStart + desiredBytes - 1L,
                                desiredBytes);
                        if (expanded.data != null && expanded.data.length > targetedData.length) {
                            targetedData = expanded.data;
                            expandedBytes = targetedData.length;
                            tracksElement = readEbmlElementHeader(targetedData, 0, targetedData.length);
                            if (tracksElement != null) {
                                declaredTotalEnd = tracksElement.declaredTotalEnd;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            Log.i("TVBox-runtime", "echo-probe-subtitle targeted-tracks offset=" + safeStart
                    + " initial=" + (target.data == null ? 0 : target.data.length)
                    + " expanded=" + expandedBytes
                    + " declaredEnd=" + declaredTotalEnd
                    + " localProxy=" + localProxyUrl);
        } else {
            Log.i("TVBox-runtime", "echo-probe-subtitle targeted-tracks invalid offset=" + safeStart
                    + " bytes=" + targetedData.length
                    + " localProxy=" + localProxyUrl);
        }
        List<SubtitleTrackMetadata> subtitleTracks = parseMatroskaSubtitleTracks(targetedData);
        MatroskaTrackSummary trackSummary = parseMatroskaTrackSummary(targetedData);
        if ((subtitleTracks == null || subtitleTracks.isEmpty()) && !hasUsefulMatroskaTrackSummary(trackSummary)) {
            return null;
        }
        return new MatroskaTracksChunk(
                safeStart,
                targetedData.length,
                declaredTotalEnd,
                subtitleTracks == null ? Collections.emptyList() : subtitleTracks,
                trackSummary == null ? MatroskaTrackSummary.EMPTY : trackSummary);
    }

    private static List<SubtitleTrackMetadata> parseMatroskaSubtitleTracks(byte[] data) {
        if (data == null || data.length < 16) {
            return Collections.emptyList();
        }
        ArrayList<SubtitleTrackMetadata> tracks = new ArrayList<>();
        try {
            if (looksLikeMatroskaTracksChunk(data)) {
                EbmlElement tracksElement = readEbmlElementHeader(data, 0, data.length);
                if (tracksElement != null && tracksElement.id == 0x1654AE6BL) {
                    parseMatroskaTracksElement(data, tracksElement.dataStart, tracksElement.dataEnd, tracks);
                }
                return tracks;
            }
            if (!containsMatroskaMarker(data)) {
                return Collections.emptyList();
            }
            int[] segmentRange = findMatroskaSegmentRange(data);
            int scanStart = segmentRange == null ? 0 : segmentRange[0];
            int scanEnd = segmentRange == null ? data.length : Math.min(data.length, segmentRange[1]);
            for (int offset = scanStart; offset < scanEnd; ) {
                EbmlElement element = readEbmlElementHeader(data, offset, scanEnd);
                if (element == null || element.totalEnd <= offset) {
                    break;
                }
                if (element.id == 0x1654AE6B) { // Tracks
                    parseMatroskaTracksElement(data, element.dataStart, element.dataEnd, tracks);
                    break;
                }
                offset = element.totalEnd;
            }
        } catch (Throwable ignored) {
        }
        return tracks;
    }

    private static MatroskaTrackSummary parseMatroskaTrackSummary(byte[] data) {
        if (data == null || data.length < 16) {
            return MatroskaTrackSummary.EMPTY;
        }
        MatroskaTrackSummary summary = new MatroskaTrackSummary();
        try {
            if (looksLikeMatroskaTracksChunk(data)) {
                EbmlElement tracksElement = readEbmlElementHeader(data, 0, data.length);
                if (tracksElement != null && tracksElement.id == 0x1654AE6BL) {
                    parseMatroskaTrackSummaryElement(data, tracksElement.dataStart, tracksElement.dataEnd, summary);
                }
                return summary.freeze();
            }
            if (!containsMatroskaMarker(data)) {
                return MatroskaTrackSummary.EMPTY;
            }
            int[] segmentRange = findMatroskaSegmentRange(data);
            int scanStart = segmentRange == null ? 0 : segmentRange[0];
            int scanEnd = segmentRange == null ? data.length : Math.min(data.length, segmentRange[1]);
            for (int offset = scanStart; offset < scanEnd; ) {
                EbmlElement element = readEbmlElementHeader(data, offset, scanEnd);
                if (element == null || element.totalEnd <= offset) {
                    break;
                }
                if (element.id == 0x1654AE6B) { // Tracks
                    parseMatroskaTrackSummaryElement(data, element.dataStart, element.dataEnd, summary);
                    break;
                }
                offset = element.totalEnd;
            }
        } catch (Throwable ignored) {
        }
        return summary.freeze();
    }

    private static void parseMatroskaTracksElement(byte[] data,
                                                   int start,
                                                   int end,
                                                   List<SubtitleTrackMetadata> out) {
        for (int offset = start; offset < end; ) {
            EbmlElement element = readEbmlElementHeader(data, offset, end);
            if (element == null || element.totalEnd <= offset) {
                break;
            }
            if (element.id == 0xAE) { // TrackEntry
                SubtitleTrackMetadata track = parseMatroskaSubtitleTrackEntry(data, element.dataStart, element.dataEnd);
                if (track != null) {
                    out.add(track);
                }
            }
            offset = element.totalEnd;
        }
    }

    private static void parseMatroskaTrackSummaryElement(byte[] data,
                                                         int start,
                                                         int end,
                                                         MatroskaTrackSummary summary) {
        if (summary == null) {
            return;
        }
        for (int offset = start; offset < end; ) {
            EbmlElement element = readEbmlElementHeader(data, offset, end);
            if (element == null || element.totalEnd <= offset) {
                break;
            }
            if (element.id == 0xAE) { // TrackEntry
                accumulateMatroskaTrackSummary(data, element.dataStart, element.dataEnd, summary);
            }
            offset = element.totalEnd;
        }
    }

    private static SubtitleTrackMetadata parseMatroskaSubtitleTrackEntry(byte[] data, int start, int end) {
        int trackNumber = -1;
        long trackType = -1L;
        String language = "";
        String languageBcp47 = "";
        String title = "";
        String codecId = "";
        long codecDelayNs = 0L;
        for (int offset = start; offset < end; ) {
            EbmlElement element = readEbmlElementHeader(data, offset, end);
            if (element == null || element.totalEnd <= offset) {
                break;
            }
            switch ((int) element.id) {
                case 0xD7: // TrackNumber
                    trackNumber = (int) readEbmlUnsignedValue(data, element.dataStart, element.dataEnd);
                    break;
                case 0x83: // TrackType
                    trackType = readEbmlUnsignedValue(data, element.dataStart, element.dataEnd);
                    break;
                case 0x86: // CodecID
                    codecId = readEbmlString(data, element.dataStart, element.dataEnd);
                    break;
                case 0x22B59C: // Language
                    language = readEbmlString(data, element.dataStart, element.dataEnd);
                    break;
                case 0x22B59D: // LanguageBCP47
                    languageBcp47 = readEbmlString(data, element.dataStart, element.dataEnd);
                    break;
                case 0x536E: // Name
                    title = readEbmlString(data, element.dataStart, element.dataEnd);
                    break;
                default:
                    if (element.id == 0x56AAL) { // CodecDelay
                        codecDelayNs = Math.max(0L, readEbmlUnsignedValue(data, element.dataStart, element.dataEnd));
                    }
                    break;
            }
            offset = element.totalEnd;
        }
        if (trackType != 0x11L) { // subtitle
            return null;
        }
        return new SubtitleTrackMetadata(
                trackNumber,
                -1,
                language,
                languageBcp47,
                title,
                codecId,
                codecId,
                isBitmapSubtitleCodec(codecId),
                codecDelayNs);
    }

    private static void accumulateMatroskaTrackSummary(byte[] data,
                                                       int start,
                                                       int end,
                                                       MatroskaTrackSummary summary) {
        if (summary == null) {
            return;
        }
        long trackType = -1L;
        String codecId = "";
        String title = "";
        summary.trackEntryCount++;
        for (int offset = start; offset < end; ) {
            EbmlElement element = readEbmlElementHeader(data, offset, end);
            if (element == null || element.totalEnd <= offset) {
                break;
            }
            switch ((int) element.id) {
                case 0x83: // TrackType
                    trackType = readEbmlUnsignedValue(data, element.dataStart, element.dataEnd);
                    break;
                case 0x86: // CodecID
                    codecId = readEbmlString(data, element.dataStart, element.dataEnd);
                    break;
                case 0x536E: // Name
                    title = readEbmlString(data, element.dataStart, element.dataEnd);
                    break;
                default:
                    break;
            }
            offset = element.totalEnd;
        }
        String combined = (safeTrim(codecId) + " " + safeTrim(title)).toLowerCase(Locale.US);
        if (trackType == 0x01L) { // video
            summary.videoTrackCount++;
            String videoMime = normalizeMatroskaVideoMime(codecId);
            if (TextUtils.isEmpty(summary.primaryVideoMime) && !TextUtils.isEmpty(videoMime)) {
                summary.primaryVideoMime = videoMime;
            }
            summary.hasAvcVideo = summary.hasAvcVideo || containsAvcCodecMarker(combined);
            summary.hasHevcVideo = summary.hasHevcVideo || containsHevcCodecTextMarker(combined);
            return;
        }
        if (trackType == 0x11L) { // subtitle
            summary.subtitleTrackCount++;
            return;
        }
        if (trackType != 0x02L) { // audio
            return;
        }
        summary.audioTrackCount++;
        String audioMime = normalizeMatroskaAudioMime(codecId, combined);
        if (TextUtils.isEmpty(summary.primaryAudioMime) && !TextUtils.isEmpty(audioMime)) {
            summary.primaryAudioMime = audioMime;
        }
        boolean eac3 = containsEac3AudioMarker(combined);
        boolean ac3 = containsAc3AudioMarker(combined);
        boolean dts = containsDtsAudioMarker(combined);
        boolean trueHd = containsTrueHdAudioMarker(combined);
        boolean atmos = containsAtmosAudioMarker(combined);
        summary.hasEac3Audio = summary.hasEac3Audio || eac3;
        summary.hasAc3Audio = summary.hasAc3Audio || ac3;
        summary.hasDtsAudio = summary.hasDtsAudio || dts;
        summary.hasTrueHdAudio = summary.hasTrueHdAudio || trueHd;
        summary.hasAtmosLikeAudio = summary.hasAtmosLikeAudio || atmos;
    }

    private static String normalizeMatroskaVideoMime(String codecId) {
        String lower = safeTrim(codecId).toLowerCase(Locale.US);
        if (lower.contains("v_mpegh/iso/hevc") || lower.contains("hevc") || lower.contains("h265")) {
            return "video/hevc";
        }
        if (lower.contains("v_mpeg4/iso/avc") || lower.contains("avc") || lower.contains("h264")) {
            return "video/avc";
        }
        if (lower.contains("vp9")) {
            return "video/x-vnd.on2.vp9";
        }
        if (lower.contains("av1")) {
            return "video/av01";
        }
        return "";
    }

    private static String normalizeMatroskaAudioMime(String codecId, String combinedLower) {
        String lower = safeTrim(codecId).toLowerCase(Locale.US);
        String combined = combinedLower == null ? "" : combinedLower;
        if (lower.contains("a_eac3") || containsEac3AudioMarker(combined)) {
            return "audio/eac3";
        }
        if (lower.contains("a_ac3") || containsAc3AudioMarker(combined)) {
            return "audio/ac3";
        }
        if (lower.contains("a_truehd") || containsTrueHdAudioMarker(combined)) {
            return "audio/truehd";
        }
        if (lower.contains("a_dts") || containsDtsAudioMarker(combined)) {
            return "audio/vnd.dts";
        }
        if (lower.contains("a_aac")) {
            return "audio/mp4a-latm";
        }
        if (lower.contains("a_opus")) {
            return "audio/opus";
        }
        if (lower.contains("a_vorbis")) {
            return "audio/vorbis";
        }
        if (lower.contains("a_flac")) {
            return "audio/flac";
        }
        if (lower.contains("a_pcm") || lower.contains("pcm")) {
            return "audio/raw";
        }
        if (lower.contains("a_mpeg") || lower.contains("mp3")) {
            return "audio/mpeg";
        }
        return "";
    }

    private static MatroskaTrackSummary mergeMatroskaTrackSummary(MatroskaTrackSummary primary,
                                                                  MatroskaTrackSummary secondary) {
        if (primary == null || primary == MatroskaTrackSummary.EMPTY) {
            return secondary == null ? MatroskaTrackSummary.EMPTY : secondary;
        }
        if (secondary == null || secondary == MatroskaTrackSummary.EMPTY) {
            return primary;
        }
        MatroskaTrackSummary merged = new MatroskaTrackSummary();
        merged.primaryVideoMime = !TextUtils.isEmpty(primary.primaryVideoMime)
                ? primary.primaryVideoMime : secondary.primaryVideoMime;
        merged.primaryAudioMime = !TextUtils.isEmpty(primary.primaryAudioMime)
                ? primary.primaryAudioMime : secondary.primaryAudioMime;
        merged.trackEntryCount = Math.max(primary.trackEntryCount, secondary.trackEntryCount);
        merged.videoTrackCount = Math.max(primary.videoTrackCount, secondary.videoTrackCount);
        merged.subtitleTrackCount = Math.max(primary.subtitleTrackCount, secondary.subtitleTrackCount);
        merged.hasAvcVideo = primary.hasAvcVideo || secondary.hasAvcVideo;
        merged.hasHevcVideo = primary.hasHevcVideo || secondary.hasHevcVideo;
        merged.hasAc3Audio = primary.hasAc3Audio || secondary.hasAc3Audio;
        merged.hasEac3Audio = primary.hasEac3Audio || secondary.hasEac3Audio;
        merged.hasDtsAudio = primary.hasDtsAudio || secondary.hasDtsAudio;
        merged.hasTrueHdAudio = primary.hasTrueHdAudio || secondary.hasTrueHdAudio;
        merged.hasAtmosLikeAudio = primary.hasAtmosLikeAudio || secondary.hasAtmosLikeAudio;
        merged.audioTrackCount = Math.max(primary.audioTrackCount, secondary.audioTrackCount);
        return merged.freeze();
    }

    private static boolean hasUsefulMatroskaTrackSummary(@Nullable MatroskaTrackSummary summary) {
        return summary != null
                && (summary.trackEntryCount > 1
                || summary.videoTrackCount > 0
                || summary.subtitleTrackCount > 0
                || !TextUtils.isEmpty(summary.primaryVideoMime)
                || !TextUtils.isEmpty(summary.primaryAudioMime)
                || summary.hasAvcVideo
                || summary.hasHevcVideo
                || summary.hasAc3Audio
                || summary.hasEac3Audio
                || summary.hasDtsAudio
                || summary.hasTrueHdAudio
                || summary.hasAtmosLikeAudio
                || summary.audioTrackCount > 0);
    }

    private static boolean looksLikeMatroskaTracksChunk(byte[] data) {
        if (data == null || data.length < MATROSKA_TRACKS_ID_BYTES.length) {
            return false;
        }
        for (int i = 0; i < MATROSKA_TRACKS_ID_BYTES.length; i++) {
            if (data[i] != MATROSKA_TRACKS_ID_BYTES[i]) {
                return false;
            }
        }
        return true;
    }

    private static int[] findMatroskaSegmentRange(byte[] data) {
        for (int i = 0; i + 4 < data.length; i++) {
            if ((data[i] & 0xff) == 0x18
                    && (data[i + 1] & 0xff) == 0x53
                    && (data[i + 2] & 0xff) == 0x80
                    && (data[i + 3] & 0xff) == 0x67) {
                EbmlVarInt size = readEbmlVarInt(data, i + 4, data.length, false);
                if (size == null) {
                    return new int[]{i + 4, data.length};
                }
                int dataStart = i + 4 + size.length;
                int dataEnd = size.value < 0 ? data.length : (int) Math.min(data.length, dataStart + size.value);
                return new int[]{Math.max(0, dataStart), Math.max(Math.max(0, dataStart), dataEnd)};
            }
        }
        return null;
    }

    private static long findMatroskaTracksAbsoluteOffset(byte[] data) {
        List<Long> offsets = findMatroskaTracksAbsoluteOffsets(data, false);
        return offsets.isEmpty() ? -1L : offsets.get(0);
    }

    private static List<Long> findMatroskaTracksAbsoluteOffsets(byte[] data, boolean includePatternFallback) {
        LinkedHashSet<Long> offsets = new LinkedHashSet<>();
        int[] segmentRange = findMatroskaSegmentRange(data);
        if (segmentRange == null) {
            if (looksLikeMatroskaTracksChunk(data)) {
                offsets.add(0L);
            }
            return new ArrayList<>(offsets);
        }
        int scanStart = segmentRange[0];
        int scanEnd = Math.min(data.length, segmentRange[1]);
        for (int offset = scanStart; offset < scanEnd; ) {
            EbmlElement element = readEbmlElementHeader(data, offset, scanEnd);
            if (element == null || element.totalEnd <= offset) {
                break;
            }
            if (element.id == 0x1654AE6BL) {
                offsets.add((long) offset);
            }
            if (element.id == 0x114D9B74L) { // SeekHead
                List<Long> seekOffsets = resolveTracksOffsetsFromSeekHead(data, scanStart, element.dataStart, element.dataEnd);
                if (seekOffsets != null && !seekOffsets.isEmpty()) {
                    offsets.addAll(seekOffsets);
                }
            }
            offset = element.totalEnd;
        }
        if ((offsets.isEmpty() || includePatternFallback) && data != null) {
            offsets.addAll(findMatroskaTracksPatternOffsets(data, 6));
        }
        return new ArrayList<>(offsets);
    }

    private static long findMatroskaTracksPatternOffset(byte[] data) {
        List<Long> offsets = findMatroskaTracksPatternOffsets(data, 1);
        return offsets.isEmpty() ? -1L : offsets.get(0);
    }

    private static List<Long> findMatroskaTracksPatternOffsets(byte[] data, int limit) {
        ArrayList<Long> offsets = new ArrayList<>();
        if (data == null || data.length < MATROSKA_TRACKS_ID_BYTES.length) {
            return offsets;
        }
        int[] segmentRange = findMatroskaSegmentRange(data);
        int scanStart = segmentRange == null
                ? 0
                : Math.max(0, segmentRange[0] - MATROSKA_TRACKS_ID_BYTES.length);
        int scanEnd = segmentRange == null ? data.length : Math.min(data.length, segmentRange[1]);
        for (int i = scanStart; i + MATROSKA_TRACKS_ID_BYTES.length <= scanEnd; i++) {
            boolean match = true;
            for (int j = 0; j < MATROSKA_TRACKS_ID_BYTES.length; j++) {
                if (data[i + j] != MATROSKA_TRACKS_ID_BYTES[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                offsets.add((long) i);
                if (offsets.size() >= Math.max(1, limit)) {
                    break;
                }
            }
        }
        return offsets;
    }

    private static long resolveTracksOffsetFromSeekHead(byte[] data,
                                                        int segmentDataStart,
                                                        int start,
                                                        int end) {
        List<Long> offsets = resolveTracksOffsetsFromSeekHead(data, segmentDataStart, start, end);
        return offsets.isEmpty() ? -1L : offsets.get(0);
    }

    private static List<Long> resolveTracksOffsetsFromSeekHead(byte[] data,
                                                               int segmentDataStart,
                                                               int start,
                                                               int end) {
        ArrayList<Long> offsets = new ArrayList<>();
        for (int offset = start; offset < end; ) {
            EbmlElement element = readEbmlElementHeader(data, offset, end);
            if (element == null || element.totalEnd <= offset) {
                break;
            }
            if (element.id == 0x4DBBL) { // Seek
                long candidate = readTracksOffsetFromSeekEntry(data, segmentDataStart, element.dataStart, element.dataEnd);
                if (candidate >= 0L) {
                    offsets.add(candidate);
                }
            }
            offset = element.totalEnd;
        }
        return offsets;
    }

    private static long readTracksOffsetFromSeekEntry(byte[] data,
                                                      int segmentDataStart,
                                                      int start,
                                                      int end) {
        boolean tracksId = false;
        long seekPosition = -1L;
        for (int offset = start; offset < end; ) {
            EbmlElement element = readEbmlElementHeader(data, offset, end);
            if (element == null || element.totalEnd <= offset) {
                break;
            }
            if (element.id == 0x53ABL) { // SeekID
                tracksId = matchesElementBytes(data, element.dataStart, element.dataEnd, MATROSKA_TRACKS_ID_BYTES);
            } else if (element.id == 0x53ACL) { // SeekPosition
                seekPosition = readEbmlUnsignedValue(data, element.dataStart, element.dataEnd);
            }
            offset = element.totalEnd;
        }
        if (!tracksId || seekPosition < 0L) {
            return -1L;
        }
        return Math.max(0L, (long) segmentDataStart + seekPosition);
    }

    private static boolean matchesElementBytes(byte[] data, int start, int end, byte[] expected) {
        if (data == null || expected == null || expected.length == 0) {
            return false;
        }
        int length = end - start;
        if (length != expected.length || start < 0 || end > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[start + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static EbmlElement readEbmlElementHeader(byte[] data, int offset, int limit) {
        if (data == null || offset < 0 || offset >= limit || limit > data.length) {
            return null;
        }
        EbmlVarInt idVar = readEbmlVarInt(data, offset, limit, true);
        if (idVar == null) {
            return null;
        }
        EbmlVarInt sizeVar = readEbmlVarInt(data, offset + idVar.length, limit, false);
        if (sizeVar == null) {
            return null;
        }
        int dataStart = offset + idVar.length + sizeVar.length;
        int declaredDataEnd;
        int declaredTotalEnd;
        if (sizeVar.value < 0) {
            declaredDataEnd = -1;
            declaredTotalEnd = -1;
        } else {
            long rawEnd = (long) dataStart + sizeVar.value;
            declaredDataEnd = rawEnd > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rawEnd;
            declaredTotalEnd = declaredDataEnd;
        }
        int dataEnd;
        if (sizeVar.value < 0) {
            dataEnd = limit;
        } else {
            long rawEnd = (long) dataStart + sizeVar.value;
            dataEnd = (int) Math.min(limit, Math.max(dataStart, rawEnd));
        }
        return new EbmlElement(idVar.value, dataStart, dataEnd, dataEnd, declaredDataEnd, declaredTotalEnd);
    }

    private static EbmlVarInt readEbmlVarInt(byte[] data, int offset, int limit, boolean keepMarkerBit) {
        if (data == null || offset < 0 || offset >= limit || limit > data.length) {
            return null;
        }
        int first = data[offset] & 0xff;
        if (first == 0) {
            return null;
        }
        int mask = 0x80;
        int length = 1;
        while (length <= 8 && (first & mask) == 0) {
            mask >>= 1;
            length++;
        }
        if (length > 8 || offset + length > limit) {
            return null;
        }
        long value = keepMarkerBit ? first : (first & (mask - 1));
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xff);
        }
        if (!keepMarkerBit) {
            long unknownValueMask = (1L << (length * 7)) - 1L;
            if (value == unknownValueMask) {
                return new EbmlVarInt(-1L, length);
            }
        }
        return new EbmlVarInt(value, length);
    }

    private static long readEbmlUnsignedValue(byte[] data, int start, int end) {
        if (data == null || start < 0 || end > data.length || start >= end) {
            return -1L;
        }
        long value = 0L;
        int length = Math.min(8, end - start);
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[start + i] & 0xff);
        }
        return value;
    }

    private static String readEbmlString(byte[] data, int start, int end) {
        if (data == null || start < 0 || end > data.length || start >= end) {
            return "";
        }
        int safeEnd = end;
        while (safeEnd > start && data[safeEnd - 1] == 0) {
            safeEnd--;
        }
        if (safeEnd <= start) {
            return "";
        }
        return safeTrim(new String(data, start, safeEnd - start, StandardCharsets.UTF_8));
    }

    private static boolean isBitmapSubtitleCodec(String codecId) {
        String lower = safeTrim(codecId).toLowerCase(Locale.US);
        return lower.contains("pgs")
                || lower.contains("hdmv/pgs")
                || lower.contains("vobsub")
                || lower.contains("dvd")
                || lower.contains("dvb")
                || lower.contains("subpicture");
    }

    private static List<SubtitleTrackMetadata> mergeSubtitleTracks(List<SubtitleTrackMetadata> primary,
                                                                   List<SubtitleTrackMetadata> secondary) {
        if ((primary == null || primary.isEmpty()) && (secondary == null || secondary.isEmpty())) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, SubtitleTrackMetadata> merged = new LinkedHashMap<>();
        mergeSubtitleTrackList(merged, primary);
        mergeSubtitleTrackList(merged, secondary);
        return new ArrayList<>(merged.values());
    }

    private static void mergeSubtitleTrackList(Map<String, SubtitleTrackMetadata> merged,
                                               List<SubtitleTrackMetadata> source) {
        if (merged == null || source == null || source.isEmpty()) {
            return;
        }
        for (SubtitleTrackMetadata item : source) {
            if (item == null) {
                continue;
            }
            String key = buildSubtitleTrackMergeKey(item);
            SubtitleTrackMetadata existing = merged.get(key);
            if (existing == null) {
                merged.put(key, item);
            } else {
                merged.put(key, preferRicherSubtitleTrack(existing, item));
            }
        }
    }

    private static SubtitleTrackMetadata preferRicherSubtitleTrack(SubtitleTrackMetadata left,
                                                                   SubtitleTrackMetadata right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        int leftScore = subtitleTrackMetadataScore(left);
        int rightScore = subtitleTrackMetadataScore(right);
        return rightScore > leftScore ? right : left;
    }

    private static int subtitleTrackMetadataScore(SubtitleTrackMetadata item) {
        if (item == null) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (!TextUtils.isEmpty(item.languageBcp47)) score += 40;
        if (!TextUtils.isEmpty(item.language)) score += 20;
        if (!TextUtils.isEmpty(item.title)) score += 30;
        if (!TextUtils.isEmpty(item.codecId)) score += 10;
        if (!TextUtils.isEmpty(item.mimeType)) score += 12;
        if (item.trackNumber > 0) score += 5;
        if (item.extractorTrackIndex >= 0) score += 24;
        return score;
    }

    private static String buildSubtitleTrackMergeKey(SubtitleTrackMetadata item) {
        if (item == null) {
            return "";
        }
        String language = safeTrim(!TextUtils.isEmpty(item.languageBcp47) ? item.languageBcp47 : item.language).toLowerCase(Locale.US);
        String title = safeTrim(item.title).toLowerCase(Locale.US);
        String codec = safeTrim(firstNonEmpty(item.codecId, item.mimeType)).toLowerCase(Locale.US);
        return item.trackNumber + "|" + item.extractorTrackIndex + "|" + language + "|" + title + "|" + codec;
    }

    public static List<TrackInfoBean> toSubtitleTrackBeans(@Nullable Result result) {
        if (result == null || result.subtitleTracks == null || result.subtitleTracks.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<TrackInfoBean> beans = new ArrayList<>();
        int order = 0;
        for (SubtitleTrackMetadata track : result.subtitleTracks) {
            if (track == null) {
                continue;
            }
            TrackInfoBean bean = new TrackInfoBean();
            bean.trackId = track.trackNumber > 0
                    ? track.trackNumber
                    : (track.extractorTrackIndex >= 0 ? 10000 + track.extractorTrackIndex : 10000 + order);
            bean.renderId = 3;
            bean.trackGroupId = 1;
            bean.extractorTrackIndex = track.extractorTrackIndex;
            bean.groupIndex = order;
            bean.index = order;
            bean.selected = false;
            bean.metadataOnly = true;
            bean.rawLanguage = safeTrim(!TextUtils.isEmpty(track.languageBcp47) ? track.languageBcp47 : track.language);
            bean.rawTitle = safeTrim(track.title);
            bean.rawCodec = safeTrim(track.codecId);
            bean.rawMimeType = safeTrim(firstNonEmpty(track.mimeType, track.codecId));
            bean.unreliableMetadata = TextUtils.isEmpty(bean.rawTitle)
                    && ("zh".equalsIgnoreCase(bean.rawLanguage)
                    || "chi".equalsIgnoreCase(bean.rawLanguage)
                    || "zho".equalsIgnoreCase(bean.rawLanguage)
                    || "cmn".equalsIgnoreCase(bean.rawLanguage));
            bean.autoSelectBlocked = track.bitmap || track.extractorTrackIndex < 0;
            bean.language = SystemPlayerTrackManager.getFriendlyLanguage(
                    firstNonEmpty(bean.rawLanguage, track.language),
                    firstNonEmpty(bean.rawTitle, bean.rawCodec, bean.rawMimeType));
            String detail = SystemPlayerTrackManager.buildTrackDetail(
                    firstNonEmpty(bean.rawTitle, bean.rawCodec, bean.rawMimeType));
            bean.name = SystemPlayerTrackManager.buildDisplayName("字幕", order + 1, bean.language, detail);
            if (track.bitmap) {
                bean.rawCodec = firstNonEmpty(bean.rawCodec, "pgs");
                bean.rawMimeType = firstNonEmpty(bean.rawMimeType, "pgs");
            }
            beans.add(bean);
            order++;
        }
        return beans;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static long readEbmlUIntElement(byte[] data, int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            return -1L;
        }
        int first = data[offset] & 0xff;
        int mask = 0x80;
        int lengthSize = 1;
        while (lengthSize <= 8 && (first & mask) == 0) {
            mask >>= 1;
            lengthSize++;
        }
        if (lengthSize > 8 || offset + lengthSize > data.length) {
            return -1L;
        }
        long length = first & (mask - 1);
        for (int i = 1; i < lengthSize; i++) {
            length = (length << 8) | (data[offset + i] & 0xff);
        }
        if (length <= 0L || length > 8L || offset + lengthSize + length > data.length) {
            return -1L;
        }
        long value = 0L;
        int valueOffset = offset + lengthSize;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[valueOffset + i] & 0xff);
        }
        return value;
    }

    private static boolean containsAnnexBHevcRpuNal(byte[] data, int length) {
        int i = 0;
        while (i + 5 < length) {
            int startCode = startCodeLengthAt(data, i, length);
            if (startCode > 0) {
                int nalOffset = i + startCode;
                if (nalOffset + 1 < length && hevcNalType(data[nalOffset]) == 62) {
                    return true;
                }
                i = nalOffset + 2;
                continue;
            }
            i++;
        }
        return false;
    }

    private static int startCodeLengthAt(byte[] data, int offset, int length) {
        if (offset + 3 < length
                && data[offset] == 0
                && data[offset + 1] == 0
                && data[offset + 2] == 0
                && data[offset + 3] == 1) {
            return 4;
        }
        if (offset + 2 < length
                && data[offset] == 0
                && data[offset + 1] == 0
                && data[offset + 2] == 1) {
            return 3;
        }
        return 0;
    }

    private static boolean containsLengthPrefixedHevcRpuNal(byte[] data, int length, int lengthSize) {
        int offset = 0;
        int parsedNalCount = 0;
        while (offset + lengthSize + 2 <= length && parsedNalCount < 128) {
            int nalLength = readUnsignedLength(data, offset, lengthSize);
            offset += lengthSize;
            if (nalLength <= 0 || nalLength > length - offset) {
                return false;
            }
            if (hevcNalType(data[offset]) == 62) {
                return true;
            }
            offset += nalLength;
            parsedNalCount++;
        }
        return false;
    }

    private static int readUnsignedLength(byte[] data, int offset, int lengthSize) {
        int value = 0;
        for (int i = 0; i < lengthSize; i++) {
            value = (value << 8) | (data[offset + i] & 0xff);
        }
        return value;
    }

    private static int hevcNalType(byte firstNalHeaderByte) {
        return (firstNalHeaderByte & 0x7e) >> 1;
    }

    private static String safeFormatDump(MediaFormat format) {
        try {
            return format.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeGetString(MediaFormat format, String key) {
        if (format == null || TextUtils.isEmpty(key)) {
            return null;
        }
        try {
            if (!format.containsKey(key)) {
                return null;
            }
            return format.getString(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int safeGetInt(MediaFormat format, String key, int fallback) {
        if (format == null || TextUtils.isEmpty(key)) {
            return fallback;
        }
        try {
            if (!format.containsKey(key)) {
                return fallback;
            }
            return format.getInteger(key);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long safeGetLong(MediaFormat format, String key, long fallback) {
        if (format == null || TextUtils.isEmpty(key)) {
            return fallback;
        }
        try {
            if (!format.containsKey(key)) {
                return fallback;
            }
            return format.getLong(key);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @Nullable
    private static SubtitleTrackMetadata buildExtractorSubtitleTrack(int extractorTrackIndex,
                                                                     MediaFormat format,
                                                                     String normalizedMime,
                                                                     String codecString,
                                                                     String formatDump) {
        if (!isSubtitleMime(normalizedMime, codecString, formatDump)) {
            return null;
        }
        int trackNumber = safeGetInt(format, "track-id", safeGetInt(format, "track-number", -1));
        String language = firstNonEmpty(
                safeGetString(format, MediaFormat.KEY_LANGUAGE),
                safeGetString(format, "language"),
                safeGetString(format, "language-bcp47"));
        String languageBcp47 = firstNonEmpty(
                safeGetString(format, "language-bcp47"),
                safeGetString(format, "language"));
        String title = firstNonEmpty(
                safeGetString(format, "title"),
                safeGetString(format, "display-title"),
                safeGetString(format, "track-name"),
                safeGetString(format, "handler-name"),
                safeGetString(format, "handler_name"));
        String codecId = firstNonEmpty(codecString, normalizedMime);
        String mimeType = firstNonEmpty(normalizedMime, codecId);
        boolean bitmap = isBitmapSubtitleCodec(firstNonEmpty(mimeType, formatDump));
        Log.i("TVBox-runtime", "echo-probe-subtitle extractor index=" + extractorTrackIndex
                + " track=" + trackNumber
                + " mime=" + shrink(mimeType)
                + " lang=" + shrink(languageBcp47)
                + " title=" + shrink(title)
                + " bitmap=" + bitmap);
        return new SubtitleTrackMetadata(trackNumber,
                extractorTrackIndex,
                language,
                languageBcp47,
                title,
                codecId,
                mimeType,
                bitmap,
                0L);
    }

    private static boolean isSubtitleMime(String normalizedMime, String codecString, String formatDump) {
        String combined = firstNonEmpty(normalizedMime, "") + " "
                + firstNonEmpty(codecString, "") + " "
                + firstNonEmpty(formatDump, "");
        String lower = combined.toLowerCase(Locale.US);
        return lower.contains("subrip")
                || lower.contains("subtitle")
                || lower.contains("utf8_text")
                || lower.contains("utf-8_text")
                || lower.contains("text/utf8")
                || lower.contains("text/plain")
                || lower.contains("text/vtt")
                || lower.contains("text/x-ssa")
                || lower.contains("ssa")
                || lower.contains("ass")
                || lower.contains("cea-608")
                || lower.contains("cea608")
                || lower.contains("cea-708")
                || lower.contains("cea708")
                || lower.contains("dvb_subtitle")
                || lower.contains("dvd_subtitle")
                || lower.contains("pgs")
                || lower.contains("subpicture");
    }

    @Nullable
    public static String resolveMappedSubtitlePath(Context context,
                                                   String url,
                                                   Map<String, String> headers,
                                                   @Nullable TrackInfoBean track) {
        if (context == null || track == null || !track.metadataOnly) {
            return track == null ? null : track.mappedSubtitlePath;
        }
        if (SystemPlayerTrackManager.isBitmapSubtitleTrack(track)) {
            return null;
        }
        if (!TextUtils.isEmpty(track.mappedSubtitlePath)) {
            File mapped = new File(track.mappedSubtitlePath);
            if (mapped.exists() && mapped.length() > 0L) {
                return mapped.getAbsolutePath();
            }
        }
        String normalizedUrl = PlaybackUrlNormalizer.normalizeHttpUrl(unwrapAppStreamProxy(url));
        if (TextUtils.isEmpty(normalizedUrl)) {
            return null;
        }
        String cacheKey = buildSubtitleFileCacheKey(normalizedUrl, headers, track);
        String cachedPath = SUBTITLE_FILE_CACHE.get(cacheKey);
        if (!TextUtils.isEmpty(cachedPath)) {
            File cachedFile = new File(cachedPath);
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                track.mappedSubtitlePath = cachedFile.getAbsolutePath();
                return cachedFile.getAbsolutePath();
            }
        }
        File outputFile = buildMappedSubtitleFile(context, cacheKey);
        if (outputFile.exists() && outputFile.length() > 0L) {
            String path = outputFile.getAbsolutePath();
            SUBTITLE_FILE_CACHE.put(cacheKey, path);
            track.mappedSubtitlePath = path;
            return path;
        }
        MediaExtractor extractor = new MediaExtractor();
        HttpRangeMediaDataSource rangeDataSource = null;
        try {
            if (shouldUseRangeBackedExtractor(context, normalizedUrl)) {
                rangeDataSource = new HttpRangeMediaDataSource(normalizedUrl,
                        headers == null ? Collections.emptyMap() : headers);
                extractor.setDataSource(rangeDataSource);
            } else if (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
                extractor.setDataSource(normalizedUrl, headers == null ? Collections.emptyMap() : headers);
            } else {
                extractor.setDataSource(normalizedUrl);
            }
            int extractorTrackIndex = resolveBestExtractorSubtitleTrackIndex(extractor, track);
            if (extractorTrackIndex < 0 || extractorTrackIndex >= extractor.getTrackCount()) {
                return null;
            }
            MediaFormat format = extractor.getTrackFormat(extractorTrackIndex);
            if (format == null) {
                return null;
            }
            String mimeType = normalizeMime(firstNonEmpty(
                    safeGetString(format, MediaFormat.KEY_MIME),
                    track.rawMimeType,
                    track.rawCodec));
            if (!isSubtitleMime(mimeType, track.rawCodec, safeFormatDump(format))
                    || SystemPlayerTrackManager.isBitmapSubtitleTrack(track)) {
                return null;
            }
            long trackDurationUs = safeGetLong(format, "durationUs", -1L);
            List<SubtitleCue> cues = readSubtitleCues(extractor,
                    extractorTrackIndex,
                    mimeType,
                    firstNonEmpty(track.rawCodec, mimeType),
                    trackDurationUs);
            if (cues.isEmpty()) {
                return null;
            }
            String content = buildMappedSubtitleContent(cues);
            if (!FileUtils.writeSimple(content.getBytes(StandardCharsets.UTF_8), outputFile)) {
                return null;
            }
            String path = outputFile.getAbsolutePath();
            SUBTITLE_FILE_CACHE.put(cacheKey, path);
            track.mappedSubtitlePath = path;
            Log.i("TVBox-runtime", "echo-subtitle-map success track=" + track.trackId
                    + " extractor=" + extractorTrackIndex
                    + " cues=" + cues.size()
                    + " path=" + path);
            return path;
        } catch (Throwable th) {
            Log.w("TVBox-runtime", "echo-subtitle-map failed track=" + track.trackId
                    + " extractor=" + track.extractorTrackIndex, th);
            return null;
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            if (rangeDataSource != null) {
                try {
                    rangeDataSource.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static File buildMappedSubtitleFile(Context context, String cacheKey) {
        File dir = new File(context.getCacheDir(), "mapped-internal-subtitles");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String name = Integer.toHexString(cacheKey.hashCode()) + ".srt";
        return new File(dir, name);
    }

    private static String buildSubtitleFileCacheKey(String url,
                                                    Map<String, String> headers,
                                                    TrackInfoBean track) {
        return buildCacheKey(url, headers)
                + "#sub#" + track.trackId
                + "#" + track.extractorTrackIndex
                + "#" + safeTrim(track.rawLanguage)
                + "#" + safeTrim(track.rawTitle)
                + "#" + safeTrim(firstNonEmpty(track.rawCodec, track.rawMimeType));
    }

    private static List<SubtitleCue> readSubtitleCues(MediaExtractor extractor,
                                                      int extractorTrackIndex,
                                                      String mimeType,
                                                      String codecHint,
                                                      long trackDurationUs) {
        ArrayList<Long> startTimesUs = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
        try {
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        } catch (Throwable ignored) {
        }
        extractor.selectTrack(extractorTrackIndex);
        try {
            while (true) {
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0L) {
                    break;
                }
                if (extractor.getSampleTrackIndex() != extractorTrackIndex) {
                    if (!extractor.advance()) {
                        break;
                    }
                    continue;
                }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                byte[] bytes = new byte[size];
                buffer.position(0);
                buffer.get(bytes, 0, size);
                String text = decodeSubtitleSample(bytes, mimeType, codecHint);
                if (!TextUtils.isEmpty(text)) {
                    startTimesUs.add(sampleTimeUs);
                    texts.add(text);
                }
                if (!extractor.advance()) {
                    break;
                }
            }
        } finally {
            try {
                extractor.unselectTrack(extractorTrackIndex);
            } catch (Throwable ignored) {
            }
        }
        if (texts.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<SubtitleCue> cues = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            long startUs = Math.max(0L, startTimesUs.get(i));
            long endUs;
            if (i + 1 < startTimesUs.size() && startTimesUs.get(i + 1) > startUs) {
                endUs = Math.max(startUs + 500_000L, startTimesUs.get(i + 1) - 1_000L);
            } else if (trackDurationUs > startUs) {
                endUs = Math.max(startUs + 2_000_000L, Math.min(trackDurationUs, startUs + 5_000_000L));
            } else {
                endUs = startUs + 4_000_000L;
            }
            cues.add(new SubtitleCue(startUs, endUs, texts.get(i)));
        }
        return cues;
    }

    private static int resolveBestExtractorSubtitleTrackIndex(MediaExtractor extractor,
                                                              @Nullable TrackInfoBean targetTrack) {
        if (extractor == null || targetTrack == null) {
            return -1;
        }
        int trackCount;
        try {
            trackCount = extractor.getTrackCount();
        } catch (Throwable ignored) {
            return -1;
        }
        if (targetTrack.extractorTrackIndex >= 0 && targetTrack.extractorTrackIndex < trackCount) {
            try {
                MediaFormat existing = extractor.getTrackFormat(targetTrack.extractorTrackIndex);
                if (existing != null && isSubtitleFormatCompatible(existing, targetTrack)) {
                    return targetTrack.extractorTrackIndex;
                }
            } catch (Throwable ignored) {
            }
        }
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format;
            try {
                format = extractor.getTrackFormat(i);
            } catch (Throwable ignored) {
                continue;
            }
            if (format == null) {
                continue;
            }
            int score = scoreExtractorSubtitleTrack(format, targetTrack);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        if (bestIndex >= 0) {
            Log.i("TVBox-runtime", "echo-subtitle-map matcher track=" + targetTrack.trackId
                    + " extractor=" + bestIndex + " score=" + bestScore
                    + " lang=" + shrink(targetTrack.rawLanguage)
                    + " title=" + shrink(targetTrack.rawTitle)
                    + " codec=" + shrink(firstNonEmpty(targetTrack.rawCodec, targetTrack.rawMimeType)));
        }
        return bestScore >= 120 ? bestIndex : -1;
    }

    private static boolean isSubtitleFormatCompatible(MediaFormat format, TrackInfoBean targetTrack) {
        return scoreExtractorSubtitleTrack(format, targetTrack) >= 120;
    }

    private static int scoreExtractorSubtitleTrack(MediaFormat format, TrackInfoBean targetTrack) {
        if (format == null || targetTrack == null) {
            return Integer.MIN_VALUE;
        }
        String mime = normalizeMime(firstNonEmpty(
                safeGetString(format, MediaFormat.KEY_MIME),
                safeGetString(format, "mime")));
        String codec = firstNonEmpty(
                safeGetString(format, "codecs-string"),
                safeGetString(format, "codec-string"),
                mime);
        String dump = safeFormatDump(format);
        if (!isSubtitleMime(mime, codec, dump)) {
            return Integer.MIN_VALUE;
        }
        int score = 100;
        int trackNumber = safeGetInt(format, "track-id", safeGetInt(format, "track-number", -1));
        if (trackNumber > 0 && trackNumber == targetTrack.trackId) {
            score += 260;
        }
        String rawLanguage = safeLowerLocal(firstNonEmpty(
                safeGetString(format, "language-bcp47"),
                safeGetString(format, MediaFormat.KEY_LANGUAGE),
                safeGetString(format, "language")));
        String rawTitle = safeLowerLocal(firstNonEmpty(
                safeGetString(format, "title"),
                safeGetString(format, "display-title"),
                safeGetString(format, "track-name"),
                safeGetString(format, "handler-name"),
                safeGetString(format, "handler_name")));
        String targetLanguage = safeLowerLocal(firstNonEmpty(targetTrack.rawLanguage, targetTrack.language));
        String targetTitle = safeLowerLocal(targetTrack.rawTitle);
        String targetCodec = safeLowerLocal(firstNonEmpty(targetTrack.rawCodec, targetTrack.rawMimeType));
        if (!TextUtils.isEmpty(targetLanguage) && !TextUtils.isEmpty(rawLanguage)) {
            if (rawLanguage.equals(targetLanguage)) {
                score += 80;
            } else if (isChineseLikeLanguage(rawLanguage)
                    && isChineseLikeLanguage(targetLanguage)) {
                score += 48;
            }
        }
        if (!TextUtils.isEmpty(targetTitle) && !TextUtils.isEmpty(rawTitle)) {
            if (rawTitle.equals(targetTitle)) {
                score += 70;
            } else if (rawTitle.contains(targetTitle) || targetTitle.contains(rawTitle)) {
                score += 40;
            }
        }
        if (!TextUtils.isEmpty(targetCodec)) {
            String lowerCodec = safeLowerLocal(firstNonEmpty(codec, mime));
            if (lowerCodec.equals(targetCodec)) {
                score += 45;
            } else if (lowerCodec.contains(targetCodec) || targetCodec.contains(lowerCodec)) {
                score += 20;
            }
        }
        if (SystemPlayerTrackManager.isBitmapSubtitleTrack(targetTrack)) {
            if (isBitmapSubtitleCodec(firstNonEmpty(codec, mime, dump))) {
                score += 20;
            } else {
                score -= 60;
            }
        } else if (isBitmapSubtitleCodec(firstNonEmpty(codec, mime, dump))) {
            score -= 80;
        }
        return score;
    }

    private static String safeLowerLocal(String value) {
        return TextUtils.isEmpty(value) ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static boolean isChineseLikeLanguage(String value) {
        String lower = safeLowerLocal(value);
        return lower.contains("zh")
                || lower.contains("chi")
                || lower.contains("zho")
                || lower.contains("cmn")
                || lower.contains("hans")
                || lower.contains("hant")
                || lower.contains("chinese")
                || lower.contains("mandarin")
                || lower.contains("中文")
                || lower.contains("汉语")
                || lower.contains("国语");
    }

    private static String decodeSubtitleSample(byte[] bytes, String mimeType, String codecHint) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String text = new String(bytes, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String lowerMime = firstNonEmpty(mimeType, codecHint).toLowerCase(Locale.US);
        if (lowerMime.contains("ssa") || lowerMime.contains("ass")) {
            text = extractAssDialogueText(text);
        }
        text = text.replace("\\N", "\n").replace("\\n", "\n");
        text = text.replaceAll("\\{[^}]*\\}", "");
        return safeTrim(text);
    }

    private static String extractAssDialogueText(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("Dialogue:")) {
            trimmed = trimmed.substring("Dialogue:".length()).trim();
        }
        int commas = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ',') {
                commas++;
                if (commas >= 8 && i + 1 < trimmed.length()) {
                    return trimmed.substring(i + 1).trim();
                }
            }
        }
        return trimmed;
    }

    private static String buildMappedSubtitleContent(List<SubtitleCue> cues) {
        StringBuilder builder = new StringBuilder(Math.max(256, cues.size() * 48));
        int index = 1;
        for (SubtitleCue cue : cues) {
            if (cue == null || TextUtils.isEmpty(cue.text)) {
                continue;
            }
            builder.append(index++)
                    .append('\n')
                    .append(formatSrtTime(cue.startUs))
                    .append(" --> ")
                    .append(formatSrtTime(cue.endUs))
                    .append('\n')
                    .append(cue.text)
                    .append("\n\n");
        }
        return builder.toString();
    }

    private static String formatSrtTime(long timeUs) {
        long safeUs = Math.max(0L, timeUs);
        long totalMs = safeUs / 1000L;
        long hours = totalMs / 3_600_000L;
        long minutes = (totalMs % 3_600_000L) / 60_000L;
        long seconds = (totalMs % 60_000L) / 1_000L;
        long millis = totalMs % 1_000L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    private static String buildCacheKey(String url, Map<String, String> headers) {
        StringBuilder builder = new StringBuilder(url);
        if (headers != null && !headers.isEmpty()) {
            builder.append('#').append(headers.hashCode());
        }
        return builder.toString();
    }

    private static String shrink(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private static Result copyResult(Result base, boolean probed, boolean isMatroska, String summary) {
        if (base == null) {
            return new Result(probed, false, false, false, isMatroska, -1, false, summary);
        }
        return new Result(probed,
                base.hasDolbyVision,
                base.hasHdr10,
                base.hasHdr10Plus,
                isMatroska,
                base.dolbyVisionProfile,
                base.hasHdr10BaseLayer,
                base.primaryVideoMime,
                base.primaryAudioMime,
                base.hasAvcVideo,
                base.hasHevcVideo,
                base.hasAc3Audio,
                base.hasEac3Audio,
                base.hasDtsAudio,
                base.hasTrueHdAudio,
                base.hasAtmosLikeAudio,
                base.audioTrackCount,
                base.subtitleTracks,
                summary);
    }

    private static String normalizeMime(String mime) {
        return TextUtils.isEmpty(mime) ? null : mime.trim().toLowerCase(Locale.US);
    }

    private static String buildProbeSummary(boolean hasDolbyVision,
                                            boolean hasHdr10,
                                            String dvSummary,
                                            String hdrSummary,
                                            String byteSummary,
                                            String fallbackSummary) {
        StringBuilder builder = new StringBuilder();
        if (hasDolbyVision && !TextUtils.isEmpty(dvSummary)) {
            builder.append(dvSummary);
        } else if (hasHdr10 && !TextUtils.isEmpty(hdrSummary)) {
            builder.append(hdrSummary);
        } else if (!TextUtils.isEmpty(fallbackSummary)) {
            builder.append(fallbackSummary);
        }
        if (!TextUtils.isEmpty(byteSummary)) {
            if (builder.length() > 0) {
                builder.append(" + ");
            }
            builder.append(byteSummary);
        }
        return builder.length() > 0 ? builder.toString() : "probe-no-summary";
    }

    private static boolean containsAvcCodecMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("video/avc")
                || lower.contains("avc1")
                || lower.contains("avc3")
                || lower.contains("h264")
                || lower.contains("v_mpeg4/iso/avc");
    }

    private static boolean containsAc3AudioMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        if (containsEac3AudioMarker(lower)) {
            return false;
        }
        return lower.contains("audio/ac3")
                || lower.contains("ac-3")
                || lower.contains(" ac3")
                || lower.startsWith("ac3");
    }

    private static boolean containsEac3AudioMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("audio/eac3")
                || lower.contains("audio/e-ac-3")
                || lower.contains("e-ac-3")
                || lower.contains("ec-3")
                || lower.contains("eac3")
                || lower.contains("ddp");
    }

    private static boolean containsDtsAudioMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("audio/vnd.dts")
                || lower.contains("audio/dts")
                || lower.contains("dts-hd")
                || lower.contains("dtshd")
                || lower.contains(" dts");
    }

    private static boolean containsTrueHdAudioMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("truehd")
                || lower.contains("true-hd")
                || lower.contains("audio/truehd")
                || lower.contains("audio/true-hd")
                || lower.contains("mlp fba")
                || lower.contains("mlp");
    }

    private static boolean containsAtmosAudioMarker(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("atmos")
                || lower.contains("dolby atmos")
                || lower.contains("joc");
    }

    public static final class Result {
        public final boolean probed;
        public final boolean hasDolbyVision;
        public final boolean hasHdr10;
        public final boolean hasHdr10Plus;
        public final boolean isMatroska;
        public final int dolbyVisionProfile;
        public final boolean hasHdr10BaseLayer;
        public final String primaryVideoMime;
        public final String primaryAudioMime;
        public final boolean hasAvcVideo;
        public final boolean hasHevcVideo;
        public final boolean hasAc3Audio;
        public final boolean hasEac3Audio;
        public final boolean hasDtsAudio;
        public final boolean hasTrueHdAudio;
        public final boolean hasAtmosLikeAudio;
        public final int audioTrackCount;
        public final List<SubtitleTrackMetadata> subtitleTracks;
        public final String summary;

        private Result(boolean probed, boolean hasDolbyVision, boolean hasHdr10, boolean hasHdr10Plus, boolean isMatroska, String summary) {
            this(probed, hasDolbyVision, hasHdr10, hasHdr10Plus, isMatroska, -1,
                    hasDolbyVision && hasHdr10, summary);
        }

        private Result(boolean probed,
                       boolean hasDolbyVision,
                       boolean hasHdr10,
                       boolean hasHdr10Plus,
                       boolean isMatroska,
                       int dolbyVisionProfile,
                       boolean hasHdr10BaseLayer,
                       String summary) {
            this(probed, hasDolbyVision, hasHdr10, hasHdr10Plus, isMatroska, dolbyVisionProfile,
                    hasHdr10BaseLayer,
                    null, null,
                    false, false,
                    false, false, false, false, false, 0,
                    null,
                    summary);
        }

        private Result(boolean probed,
                       boolean hasDolbyVision,
                       boolean hasHdr10,
                       boolean hasHdr10Plus,
                       boolean isMatroska,
                       int dolbyVisionProfile,
                       boolean hasHdr10BaseLayer,
                       String primaryVideoMime,
                       String primaryAudioMime,
                       boolean hasAvcVideo,
                       boolean hasHevcVideo,
                       boolean hasAc3Audio,
                       boolean hasEac3Audio,
                       boolean hasDtsAudio,
                       boolean hasTrueHdAudio,
                       boolean hasAtmosLikeAudio,
                       int audioTrackCount,
                       List<SubtitleTrackMetadata> subtitleTracks,
                       String summary) {
            this.probed = probed;
            this.hasDolbyVision = hasDolbyVision;
            this.hasHdr10 = hasHdr10;
            this.hasHdr10Plus = hasHdr10Plus;
            this.isMatroska = isMatroska;
            this.dolbyVisionProfile = dolbyVisionProfile;
            this.hasHdr10BaseLayer = hasHdr10BaseLayer || (hasDolbyVision && hasHdr10);
            this.primaryVideoMime = normalizeMime(primaryVideoMime);
            this.primaryAudioMime = normalizeMime(primaryAudioMime);
            this.hasAvcVideo = hasAvcVideo;
            this.hasHevcVideo = hasHevcVideo;
            this.hasAc3Audio = hasAc3Audio;
            this.hasEac3Audio = hasEac3Audio;
            this.hasDtsAudio = hasDtsAudio;
            this.hasTrueHdAudio = hasTrueHdAudio;
            this.hasAtmosLikeAudio = hasAtmosLikeAudio;
            this.audioTrackCount = Math.max(0, audioTrackCount);
            if (subtitleTracks == null || subtitleTracks.isEmpty()) {
                this.subtitleTracks = Collections.emptyList();
            } else {
                this.subtitleTracks = Collections.unmodifiableList(new ArrayList<>(subtitleTracks));
            }
            this.summary = summary == null ? "" : summary;
        }

        public static Result unknown(String summary) {
            return new Result(false, false, false, false, false, -1, false, summary);
        }

        public static Result unknownContainer(String summary, boolean isMatroska) {
            return new Result(false, false, false, false, isMatroska, -1, false, summary);
        }

        public boolean hasImmersiveOrCompressedAudio() {
            return hasAc3Audio
                    || hasEac3Audio
                    || hasDtsAudio
                    || hasTrueHdAudio
                    || hasAtmosLikeAudio;
        }
    }

    private static final class MatroskaTrackSummary {
        private static final MatroskaTrackSummary EMPTY = new MatroskaTrackSummary().freeze();

        private String primaryVideoMime = "";
        private String primaryAudioMime = "";
        private int trackEntryCount;
        private int videoTrackCount;
        private boolean hasAvcVideo;
        private boolean hasHevcVideo;
        private int subtitleTrackCount;
        private boolean hasAc3Audio;
        private boolean hasEac3Audio;
        private boolean hasDtsAudio;
        private boolean hasTrueHdAudio;
        private boolean hasAtmosLikeAudio;
        private int audioTrackCount;

        private boolean hasImmersiveOrCompressedAudio() {
            return hasAc3Audio || hasEac3Audio || hasDtsAudio || hasTrueHdAudio || hasAtmosLikeAudio;
        }

        private MatroskaTrackSummary freeze() {
            primaryVideoMime = firstNonEmpty(primaryVideoMime);
            primaryAudioMime = firstNonEmpty(primaryAudioMime);
            trackEntryCount = Math.max(0, trackEntryCount);
            videoTrackCount = Math.max(0, videoTrackCount);
            subtitleTrackCount = Math.max(0, subtitleTrackCount);
            audioTrackCount = Math.max(0, audioTrackCount);
            return this;
        }
    }

    private static final class MatroskaTracksChunk {
        private final long absoluteOffset;
        private final int length;
        private final int declaredTotalEnd;
        private final List<SubtitleTrackMetadata> subtitleTracks;
        private final MatroskaTrackSummary trackSummary;

        private MatroskaTracksChunk(long absoluteOffset,
                                    int length,
                                    int declaredTotalEnd,
                                    List<SubtitleTrackMetadata> subtitleTracks,
                                    MatroskaTrackSummary trackSummary) {
            this.absoluteOffset = Math.max(0L, absoluteOffset);
            this.length = Math.max(0, length);
            this.declaredTotalEnd = declaredTotalEnd;
            if (subtitleTracks == null || subtitleTracks.isEmpty()) {
                this.subtitleTracks = Collections.emptyList();
            } else {
                this.subtitleTracks = Collections.unmodifiableList(new ArrayList<>(subtitleTracks));
            }
            this.trackSummary = trackSummary == null
                    ? MatroskaTrackSummary.EMPTY
                    : trackSummary;
        }
    }

    public static final class SubtitleTrackMetadata {
        public final int trackNumber;
        public final int extractorTrackIndex;
        public final String language;
        public final String languageBcp47;
        public final String title;
        public final String codecId;
        public final String mimeType;
        public final boolean bitmap;
        public final long codecDelayNs;

        private SubtitleTrackMetadata(int trackNumber,
                                      int extractorTrackIndex,
                                      String language,
                                      String languageBcp47,
                                      String title,
                                      String codecId,
                                      String mimeType,
                                      boolean bitmap,
                                      long codecDelayNs) {
            this.trackNumber = trackNumber;
            this.extractorTrackIndex = extractorTrackIndex;
            this.language = safeTrim(language);
            this.languageBcp47 = safeTrim(languageBcp47);
            this.title = safeTrim(title);
            this.codecId = safeTrim(codecId);
            this.mimeType = safeTrim(mimeType);
            this.bitmap = bitmap;
            this.codecDelayNs = Math.max(0L, codecDelayNs);
        }
    }

    private static final class ProbeChunk {
        private final byte[] data;
        private final long totalSize;

        private ProbeChunk(byte[] data, long totalSize) {
            this.data = data == null ? new byte[0] : data;
            this.totalSize = totalSize;
        }
    }

    private static final class EbmlVarInt {
        private final long value;
        private final int length;

        private EbmlVarInt(long value, int length) {
            this.value = value;
            this.length = length;
        }
    }

    private static final class EbmlElement {
        private final long id;
        private final int dataStart;
        private final int dataEnd;
        private final int totalEnd;
        private final int declaredDataEnd;
        private final int declaredTotalEnd;

        private EbmlElement(long id,
                            int dataStart,
                            int dataEnd,
                            int totalEnd,
                            int declaredDataEnd,
                            int declaredTotalEnd) {
            this.id = id;
            this.dataStart = dataStart;
            this.dataEnd = dataEnd;
            this.totalEnd = totalEnd;
            this.declaredDataEnd = declaredDataEnd;
            this.declaredTotalEnd = declaredTotalEnd;
        }
    }

    private static final class SubtitleCue {
        private final long startUs;
        private final long endUs;
        private final String text;

        private SubtitleCue(long startUs, long endUs, String text) {
            this.startUs = startUs;
            this.endUs = Math.max(startUs + 1_000L, endUs);
            this.text = safeTrim(text);
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
