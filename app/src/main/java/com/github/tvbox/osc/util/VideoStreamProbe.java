package com.github.tvbox.osc.util;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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

public final class VideoStreamProbe {
    private static final ConcurrentHashMap<String, Result> CACHE = new ConcurrentHashMap<>();
    private static final int PROBE_BYTES = 1024 * 1024;
    private static final int LOCAL_PROXY_PROBE_BYTES = 1024 * 1024;
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
        url = unwrapAppStreamProxy(url);
        String cacheKey = buildCacheKey(url, headers);
        Result cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Result result = doProbe(context, url, headers);
        CACHE.put(cacheKey, result);
        return result;
    }

    public static Result probeWithTimeout(Context context, String url, Map<String, String> headers, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || TextUtils.isEmpty(url)) {
            return Result.unknown("sdk-or-url");
        }
        url = unwrapAppStreamProxy(url);
        String cacheKey = buildCacheKey(url, headers);
        Result cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        final String probeUrl = url;
        Future<Result> future = PROBE_EXECUTOR.submit(() -> doProbeOffMain(context, probeUrl, headers));
        try {
            Result result = future.get(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
            if (result == null) {
                result = Result.unknown("timeout-null");
            }
            CACHE.put(cacheKey, result);
            return result;
        } catch (Throwable th) {
            future.cancel(true);
            Result fallback = null;
            if (isLocalProxyVideoUrl(probeUrl)) {
                fallback = probeContainerBytes(probeUrl, headers,
                        "timeout-byte-" + th.getClass().getSimpleName(),
                        LOCAL_PROXY_PROBE_BYTES, false);
            }
            Result result;
            if (fallback != null && fallback.probed) {
                result = new Result(fallback.probed,
                        fallback.hasDolbyVision,
                        fallback.hasHdr10,
                        fallback.hasHdr10Plus,
                        fallback.isMatroska || containsMatroskaMarkerInUrl(probeUrl),
                        fallback.dolbyVisionProfile,
                        fallback.hasHdr10BaseLayer,
                        "timeout-fallback:" + fallback.summary);
            } else {
                result = Result.unknown("timeout-" + th.getClass().getSimpleName());
            }
            return result;
        }
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
        if (isLocalProxyVideoUrl(url)) {
            Result byteResult = probeContainerBytes(url, headers, "local-proxy-byte", LOCAL_PROXY_PROBE_BYTES, false);
            if (byteResult != null
                    && byteResult.probed
                    && (byteResult.hasDolbyVision || byteResult.hasHdr10 || byteResult.hasHdr10Plus)) {
                return new Result(true,
                        byteResult.hasDolbyVision,
                        byteResult.hasHdr10,
                        byteResult.hasHdr10Plus,
                        byteResult.isMatroska || containsMatroskaMarkerInUrl(url),
                        byteResult.dolbyVisionProfile,
                        byteResult.hasHdr10BaseLayer,
                        "local-proxy-byte-fast:" + byteResult.summary);
            }
            Result extractorResult = probeExtractorResult(context, url, headers);
            return mergeLocalProxyProbeResult(url, byteResult, extractorResult);
        }
        return probeExtractorResult(context, url, headers);
    }

    private static Result probeExtractorResult(Context context, String url, Map<String, String> headers) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                extractor.setDataSource(url, headers);
            } else {
                extractor.setDataSource(url);
            }
            int trackCount = extractor.getTrackCount();
            boolean sawVideo = false;
            boolean sawHdr10 = false;
            boolean sawHdr10Plus = false;
            int detectedDolbyVisionProfile = -1;
            String hdrSummary = "";
            List<Integer> videoTracks = new ArrayList<>();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format == null) {
                    continue;
                }
                String mime = safeGetString(format, MediaFormat.KEY_MIME);
                if (TextUtils.isEmpty(mime) || !mime.toLowerCase(Locale.US).startsWith("video/")) {
                    continue;
                }
                sawVideo = true;
                videoTracks.add(i);
                String codecString = safeGetString(format, "codecs-string");
                String formatDump = safeFormatDump(format);
                String combined = ((mime == null ? "" : mime) + " "
                        + (codecString == null ? "" : codecString) + " "
                        + (formatDump == null ? "" : formatDump)).toLowerCase(Locale.US);
                boolean hdr10Plus = containsHdr10PlusTextMarker(combined);
                boolean hdr10 = containsHdr10TextMarker(combined) || hasHdrColorFormat(format);
                int dvProfile = extractDolbyVisionProfile(combined);
                if (dvProfile > 0) {
                    detectedDolbyVisionProfile = dvProfile;
                }
                if (containsDolbyVisionTextMarker(combined)) {
                    boolean hdr10BaseLayer = hdr10 || isDolbyVisionProfileWithHdr10BaseLayer(dvProfile);
                    return new Result(true, true, hdr10BaseLayer, hdr10Plus, containsMatroskaMarker(combined),
                            dvProfile, hdr10BaseLayer, "track=" + i + " " + shrink(combined));
                }
                if (containsDolbyVisionCsdMarker(format)) {
                    boolean hdr10BaseLayer = hdr10 || isDolbyVisionProfileWithHdr10BaseLayer(dvProfile);
                    return new Result(true, true, hdr10BaseLayer, hdr10Plus, containsMatroskaMarker(combined),
                            dvProfile, hdr10BaseLayer, "track=" + i + " csd-dv-marker");
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
                for (Integer track : videoTracks) {
                    if (track != null && !isLocalProxyVideoUrl(url) && containsDolbyVisionSampleMarker(extractor, track)) {
                        return new Result(true, true, sawHdr10 || detectedDolbyVisionProfile != 5, sawHdr10Plus, false,
                                detectedDolbyVisionProfile, sawHdr10 || detectedDolbyVisionProfile != 5,
                                "track=" + track + " hevc-rpu-sample");
                    }
                }
                Result byteResult = probeContainerBytes(url, headers, "extractor-fallback", PROBE_BYTES, true);
                if (byteResult != null && byteResult.probed && (byteResult.hasDolbyVision || byteResult.hasHdr10 || byteResult.hasHdr10Plus)) {
                    if (!byteResult.hasDolbyVision && sawHdr10) {
                        return new Result(true, false, true, sawHdr10Plus || byteResult.hasHdr10Plus, byteResult.isMatroska,
                                TextUtils.isEmpty(hdrSummary) ? byteResult.summary : hdrSummary + " + " + byteResult.summary);
                    }
                    return byteResult;
                }
                boolean byteProbeMatroska = byteResult != null && byteResult.probed && byteResult.isMatroska;
                if (sawHdr10) {
                    return new Result(true, false, true, sawHdr10Plus, byteProbeMatroska, -1, false, hdrSummary);
                }
                return new Result(true, false, false, false, byteProbeMatroska, -1, false,
                        byteProbeMatroska ? "video-track-no-hdr + matroska-container" : "video-track-no-hdr");
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
        }
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
                    dvProfile, hdr10BaseLayer, summary);
        }

        if (byteResult != null && byteResult.probed) {
            if (byteResult.hasHdr10 || byteResult.hasHdr10Plus || byteResult.isMatroska) {
                return new Result(true,
                        byteResult.hasDolbyVision,
                        byteResult.hasHdr10,
                        byteResult.hasHdr10Plus,
                        isMatroska,
                        byteResult.dolbyVisionProfile,
                        byteResult.hasHdr10BaseLayer,
                        "local-proxy-byte-only:" + byteResult.summary);
            }
            return new Result(true, false, false, false, isMatroska, -1, false,
                    "local-proxy-byte-no-hdr:" + byteResult.summary);
        }

        if (extractorResult != null) {
            return new Result(extractorResult.probed, extractorResult.hasDolbyVision,
                    extractorResult.hasHdr10, extractorResult.hasHdr10Plus, isMatroska,
                    extractorResult.dolbyVisionProfile, extractorResult.hasHdr10BaseLayer,
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
            return new Result(result.probed, result.hasDolbyVision, result.hasHdr10, result.hasHdr10Plus,
                    result.isMatroska, result.dolbyVisionProfile, result.hasHdr10BaseLayer,
                    prefix + ":" + result.summary);
        } catch (Throwable th) {
            future.cancel(true);
            Result fallback = null;
            if (isLocalProxyVideoUrl(url)) {
                fallback = probeContainerBytes(url, headers, prefix + ":timeout-byte", LOCAL_PROXY_PROBE_BYTES, false);
            }
            if (fallback != null && fallback.probed) {
                return new Result(fallback.probed,
                        fallback.hasDolbyVision,
                        fallback.hasHdr10,
                        fallback.hasHdr10Plus,
                        fallback.isMatroska || containsMatroskaMarkerInUrl(url),
                        fallback.dolbyVisionProfile,
                        fallback.hasHdr10BaseLayer,
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
            int safeProbeBytes = Math.max(32 * 1024, Math.min(PROBE_BYTES, probeBytes));
            ProbeChunk first = readRange(url, headers, 0L, safeProbeBytes - 1L, safeProbeBytes);
            boolean firstHasDvText = containsDolbyVisionMarker(first.data);
            boolean firstHasDvRpu = containsHevcRpuNal(first.data, first.data.length);
            boolean firstHasHevcContext = containsHevcCodecMarker(first.data);
            boolean localProxyUrl = isLocalProxyVideoUrl(url);
            if (firstHasDvText || firstHasDvRpu) {
                int dvProfile = extractDolbyVisionProfile(first.data);
                boolean dvConfirmed = isConfirmedDolbyVisionByteProbe(firstHasDvText, firstHasDvRpu,
                        firstHasHevcContext, dvProfile, localProxyUrl);
                boolean hdr10BaseLayer = containsHdr10Marker(first.data) || isDolbyVisionProfileWithHdr10BaseLayer(dvProfile);
                if (dvConfirmed) {
                    return new Result(true, true, hdr10BaseLayer, containsHdr10PlusMarker(first.data),
                        containsMatroskaMarker(first.data), dvProfile, hdr10BaseLayer,
                        prefix + ":header-dovi" + profileSuffix(dvProfile, hdr10BaseLayer));
                }
            }
            if (containsHdr10PlusMarker(first.data) || containsHdr10Marker(first.data)) {
                return new Result(true, false, true, containsHdr10PlusMarker(first.data), containsMatroskaMarker(first.data),
                        -1, false, prefix + ":header-hdr");
            }
            long total = first.totalSize;
            boolean firstLooksMatroska = containsMatroskaMarker(first.data);
            if (readTail && total > safeProbeBytes && !localProxyUrl) {
                long start = Math.max(0L, total - safeProbeBytes);
                ProbeChunk tail = readRange(url, headers, start, total - 1L, safeProbeBytes);
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
                                prefix + ":tail-dovi" + profileSuffix(dvProfile, hdr10BaseLayer));
                    }
                }
                if (containsHdr10PlusMarker(tail.data) || containsHdr10Marker(tail.data)) {
                    return new Result(true, false, true, containsHdr10PlusMarker(tail.data),
                            firstLooksMatroska || tailLooksMatroska, -1, false, prefix + ":tail-hdr");
                }
            }
            return new Result(true, false, false, false, firstLooksMatroska, -1, false, prefix + ":byte-probe-no-hdr");
        } catch (Throwable th) {
            return Result.unknown(prefix + ":byte-probe-" + th.getClass().getSimpleName());
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
            Uri nestedUri = Uri.parse(nestedUrl);
            String nestedHost = nestedUri.getHost();
            String nestedPath = nestedUri.getPath();
            if (("127.0.0.1".equals(nestedHost) || "localhost".equalsIgnoreCase(nestedHost))
                    && nestedPath != null
                    && nestedPath.contains("/proxy/play/")) {
                return nestedUrl;
            }
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

    public static final class Result {
        public final boolean probed;
        public final boolean hasDolbyVision;
        public final boolean hasHdr10;
        public final boolean hasHdr10Plus;
        public final boolean isMatroska;
        public final int dolbyVisionProfile;
        public final boolean hasHdr10BaseLayer;
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
            this.probed = probed;
            this.hasDolbyVision = hasDolbyVision;
            this.hasHdr10 = hasHdr10;
            this.hasHdr10Plus = hasHdr10Plus;
            this.isMatroska = isMatroska;
            this.dolbyVisionProfile = dolbyVisionProfile;
            this.hasHdr10BaseLayer = hasHdr10BaseLayer || (hasDolbyVision && hasHdr10);
            this.summary = summary == null ? "" : summary;
        }

        public static Result unknown(String summary) {
            return new Result(false, false, false, false, false, -1, false, summary);
        }

        public static Result unknownContainer(String summary, boolean isMatroska) {
            return new Result(false, false, false, false, isMatroska, -1, false, summary);
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
}
