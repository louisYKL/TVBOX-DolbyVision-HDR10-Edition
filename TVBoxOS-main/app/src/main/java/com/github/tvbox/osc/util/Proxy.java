package com.github.tvbox.osc.util;
import android.net.Uri;
import android.text.TextUtils;
import com.github.catvod.crawler.SpiderDebug;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.parser.SuperParse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class Proxy {
    private static final Pattern M3U8_URI_ATTRIBUTE_PATTERN = Pattern.compile("URI=(\"([^\"]*)\"|'([^']*)')");
    private static final int LOCAL_PROXY_STREAM_MAX_RETRIES = 4;
    private static final long LOCAL_PROXY_STREAM_RETRY_DELAY_MS = 150L;
    private static final long LOCAL_PROXY_FETCH_CHUNK_SIZE = 32L * 1024L * 1024L;
    private static final long LOCAL_PROXY_MAX_RANGE_DISCARD = 8L * 1024L * 1024L;
    private static final int LOCAL_PROXY_MAX_PREMATURE_EOF = 12;
    private static volatile OkHttpClient localProxyStreamClient;
    private static final String[] PASSTHROUGH_REQUEST_HEADERS = new String[]{
            "Range",
            "User-Agent",
            "Referer",
            "Origin",
            "Accept",
            "Accept-Language",
            "Cookie"
    };
    private static final String[] PASSTHROUGH_RESPONSE_HEADERS = new String[]{
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "Cache-Control",
            "Content-Disposition",
            "Content-Encoding",
            "Expires",
            "Last-Modified",
            "ETag",
            "Location"
    };

    public static Object[] proxy(Map<String, String> params) {
        try {
            String what = params.get("go");
            assert what != null;
            if (what.equals("live")) {
                return itv(params);
            }
            else if (what.equals("stream")) {
                return passthroughStream(params);
            }
            else if (what.equals("bom")) {
                return removeBOMFromM3U8(params);
            }
            else if (what.equals("ad")) {
                //TODO
                return null;
            }
            else if (what.equals("SuperParse")) {
                return SuperParse.loadHtml(params.get("flag"), params.get("url"));
            }

        } catch (Throwable ignored) {

        }
        return null;
    }
    public static Object[] itv(Map<String, String> params) throws Exception {
        try {
            Object[] result = new Object[3];
            String url = params.get("url");
            String type = params.get("type");
            if (url == null || type == null) {
                return null;
            }
            url = URLDecoder.decode(url,"UTF-8");
            Map<String, String> requestHeaders = extractProxyHeaders(params);

            OkHttpClient client = OkGoHelper.ItvClient;
            assert type != null;
            if (type.equals("m3u8")) {
                String redirectUrl = getRedirectedUrl(url, requestHeaders);

                Request request = buildRequest(redirectUrl, requestHeaders);
                try (Response response = executeRequest(client, request)) {
                    if (response.isSuccessful()) {
                        assert response.body() != null;
                        String respContent = response.body().string();
                        String m3u8Content = processM3u8Content(respContent, redirectUrl, requestHeaders);
                        result[0] = 200;
                        result[1] = "application/vnd.apple.mpegurl";
                        result[2] = new ByteArrayInputStream(m3u8Content.getBytes());
                    } else {
                        throw new IOException("M3U8 Request failed with code: " + response.code());
                    }
                }
            } else if (type.equals("ts")) {
                Request request = buildRequest(url, requestHeaders);
                Response response = executeRequest(client, request);
                if (response.isSuccessful() && response.body() != null) {
                    result[0] = 200;
                    result[1] = normalizePassthroughContentType(url, response.header("Content-Type"));
                    result[2] = new ResponseClosingInputStream(response.body().byteStream(), response);
                } else {
                    if (response != null) {
                        response.close();
                    }
                    throw new IOException("TS Request failed with code: " + (response == null ? "unknown" : response.code()));
                }
            } else {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
            return result;
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    public static Object[] removeBOMFromM3U8(Map<String, String> params) throws Exception {
        try {
            Object[] result = new Object[3];
            String url = params.get("url");
            url = URLDecoder.decode(url,"UTF-8");
            Map<String, String> requestHeaders = extractProxyHeaders(params);

            OkHttpClient client = OkGoHelper.ItvClient;
            String redirectUrl = getRedirectedUrl(url, requestHeaders);

            Request request = buildRequest(redirectUrl, requestHeaders);
            try (Response response = executeRequest(client, request)) {
                if (response.isSuccessful()) {
                    assert response.body() != null;
                    String m3u8Content = response.body().string();
                    // 检查并去除 UTF-8 BOM 头（BOM 为 \uFEFF）
                    if (m3u8Content.startsWith("\ufeff")) {
                        m3u8Content = m3u8Content.substring(1);
                    }
                    result[0] = 200;
                    result[1] = "application/vnd.apple.mpegurl";
                    result[2] = new ByteArrayInputStream(processM3u8Content(m3u8Content, redirectUrl, requestHeaders).getBytes());
                } else {
                    throw new IOException("M3U8 Request failed with code: " + response.code());
                }
            }
            return result;
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    public static Object[] passthroughStream(Map<String, String> params) throws Exception {
        try {
            String url = params.get("url");
            if (url == null) {
                return null;
            }
            url = URLDecoder.decode(url, "UTF-8");
            boolean localProxyPlayUrl = isLocalProxyPlayUrl(url);
            Map<String, String> requestHeaders = extractProxyHeaders(params);
            mergeRequestHeadersFromSession(params, requestHeaders);
            if (localProxyPlayUrl) {
                // Some spider localhost proxies don't implement HTTP keep-alive correctly for repeated
                // range reads on large MKV files. For those streams force a fresh connection and disable
                // content codings so the wrapper can normalize byte ranges deterministically.
                requestHeaders.put("Connection", "close");
                requestHeaders.put("Accept-Encoding", "identity");
                return passthroughLocalProxyStream(url, requestHeaders);
            }
            Response response = executeStreamRequest(url, requestHeaders, localProxyPlayUrl);
            return buildPassthroughResult(url, requestHeaders, response);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private static Object[] passthroughLocalProxyStream(String url, Map<String, String> requestHeaders) throws Exception {
        Response response = executeStreamRequest(url, requestHeaders, true);
        if (!response.isSuccessful() || response.body() == null) {
            if (response != null) {
                response.close();
            }
            throw new IOException("Local proxy stream request failed with code: " + (response == null ? "unknown" : response.code()));
        }
        RangeRequestInfo requestRange = parseRangeRequest(findHeaderValue(requestHeaders, "Range"));
        ContentRangeInfo contentRangeInfo = parseContentRange(response.header("Content-Range"));
        long totalLength = resolveTotalLength(response, contentRangeInfo, requestRange);
        if (requestRange != null && totalLength > 0L) {
            long rangeStart;
            long rangeEnd;
            if (requestRange.suffix) {
                long suffixLength = Math.min(requestRange.suffixLength, totalLength);
                rangeStart = Math.max(0L, totalLength - suffixLength);
                rangeEnd = totalLength - 1L;
            } else {
                rangeStart = requestRange.start;
                rangeEnd = requestRange.hasEnd
                        ? Math.min(requestRange.end, totalLength - 1L)
                        : totalLength - 1L;
            }
            if (rangeStart >= totalLength || rangeStart < 0L || rangeEnd < rangeStart) {
                response.close();
                return buildRangeNotSatisfiable(totalLength);
            }
            long firstChunkEnd = Math.min(rangeEnd, rangeStart + LOCAL_PROXY_FETCH_CHUNK_SIZE - 1L);
            if (contentRangeInfo == null || contentRangeInfo.length() > LOCAL_PROXY_FETCH_CHUNK_SIZE) {
                response.close();
                response = openChunkResponse(url, requestHeaders, rangeStart, firstChunkEnd);
                if (!response.isSuccessful() || response.body() == null) {
                    if (response != null) {
                        response.close();
                    }
                    throw new IOException("Local proxy range reopen failed: " + rangeStart + "-" + firstChunkEnd);
                }
                contentRangeInfo = parseContentRange(response.header("Content-Range"));
                totalLength = resolveTotalLength(response, contentRangeInfo, requestRange);
            }
            return buildStreamingRangeResult(url, requestHeaders, response, rangeStart, rangeEnd, totalLength, contentRangeInfo, requestRange.hasEnd);
        }
        boolean matroskaLike = isMatroskaLike(url);
        if (totalLength > LOCAL_PROXY_FETCH_CHUNK_SIZE || matroskaLike) {
            response.close();
            long targetEnd = totalLength > 0L ? totalLength - 1L : LOCAL_PROXY_FETCH_CHUNK_SIZE - 1L;
            long firstChunkEnd = Math.min(targetEnd, LOCAL_PROXY_FETCH_CHUNK_SIZE - 1L);
            response = openChunkResponse(url, requestHeaders, 0L, firstChunkEnd);
            if (!response.isSuccessful() || response.body() == null) {
                if (response != null) {
                    response.close();
                }
                throw new IOException("Local proxy full stream chunk open failed: 0-" + firstChunkEnd);
            }
            contentRangeInfo = parseContentRange(response.header("Content-Range"));
            totalLength = Math.max(totalLength, resolveTotalLength(response, contentRangeInfo));
            if (totalLength > 0L) {
                return buildStreamingFullResult(url, requestHeaders, response, totalLength, contentRangeInfo);
            }
        }
        return buildPassthroughResult(url, requestHeaders, response);
    }

    private static Object[] buildStreamingFullResult(String url,
                                                     Map<String, String> requestHeaders,
                                                     Response response,
                                                     long totalLength,
                                                     ContentRangeInfo contentRangeInfo) throws IOException {
        if (!response.isSuccessful() || response.body() == null) {
            if (response != null) {
                response.close();
            }
            throw new IOException("Full stream request failed with code: " + (response == null ? "unknown" : response.code()));
        }
        long targetEnd = totalLength - 1L;
        long firstPayloadStart = contentRangeInfo != null ? contentRangeInfo.start : 0L;
        long firstRequestedEnd = contentRangeInfo != null
                ? Math.min(contentRangeInfo.end, targetEnd)
                : Math.min(targetEnd, LOCAL_PROXY_FETCH_CHUNK_SIZE - 1L);
        long firstAvailableEnd = contentRangeInfo != null
                ? firstRequestedEnd
                : Math.min(targetEnd, firstPayloadStart + Math.max(0L, response.body().contentLength()) - 1L);
        if (firstAvailableEnd < firstPayloadStart) {
            firstAvailableEnd = firstRequestedEnd;
        }
        Object[] result = new Object[4];
        result[0] = 200;
        result[1] = normalizePassthroughContentType(url, response.header("Content-Type"));
        result[2] = new ReconnectingRangeInputStream(
                url,
                requestHeaders,
                response,
                0L,
                targetEnd,
                totalLength,
                LOCAL_PROXY_FETCH_CHUNK_SIZE,
                firstPayloadStart,
                Math.min(firstRequestedEnd, firstAvailableEnd)
        );
        Map<String, String> responseHeaders = new HashMap<>();
        for (String headerName : PASSTHROUGH_RESPONSE_HEADERS) {
            copyHeader(response, responseHeaders, headerName);
        }
        responseHeaders.put("Content-Length", String.valueOf(totalLength));
        responseHeaders.put("Accept-Ranges", "bytes");
        responseHeaders.remove("Content-Range");
        responseHeaders.remove("Transfer-Encoding");
        SpiderDebug.log("passthroughLocalProxyFullChunked code=200 type=" + result[1]
                + " range=0-" + targetEnd + "/" + totalLength
                + " firstChunkEnd=" + firstRequestedEnd
                + " firstAvailableEnd=" + firstAvailableEnd
                + " localProxy=true");
        result[3] = responseHeaders;
        return result;
    }

    private static Object[] buildStreamingRangeResult(String url,
                                                      Map<String, String> requestHeaders,
                                                      Response response,
                                                      long start,
                                                      long end,
                                                      long totalLength,
                                                      ContentRangeInfo contentRangeInfo,
                                                      boolean clientHadExplicitEnd) throws IOException {
        if (!response.isSuccessful() || response.body() == null) {
            if (response != null) {
                response.close();
            }
            throw new IOException("Range stream request failed with code: " + (response == null ? "unknown" : response.code()));
        }
        Object[] result = new Object[4];
        // This method is only used when the client sent a Range header. Even
        // bytes=0- must stay 206 so Android's HTTP data source keeps seek
        // support enabled for large Matroska container probing.
        result[0] = 206;
        result[1] = normalizePassthroughContentType(url, response.header("Content-Type"));
        long normalizedLength = end - start + 1L;
        long firstPayloadStart = contentRangeInfo != null ? contentRangeInfo.start : start;
        boolean usingWholeResponseAfterSmallIgnoredRange = false;
        long firstAvailableLength = response.body().contentLength();
        if (contentRangeInfo == null
                && response.code() != 206
                && (start > 0L || firstAvailableLength < 0L || firstAvailableLength > normalizedLength)) {
            if (start <= LOCAL_PROXY_MAX_RANGE_DISCARD && firstAvailableLength > normalizedLength) {
                firstPayloadStart = 0L;
                usingWholeResponseAfterSmallIgnoredRange = true;
                SpiderDebug.log("passthroughLocalProxyRange allowSmallIgnoredInitialRange start=" + start
                        + " end=" + end
                        + " bodyLength=" + firstAvailableLength
                        + " code=" + response.code()
                        + " localProxy=true");
            } else {
                response.close();
                SpiderDebug.log("passthroughLocalProxyRange upstreamIgnoredInitialRange start=" + start
                        + " end=" + end
                        + " bodyLength=" + firstAvailableLength
                        + " code=" + response.code()
                        + " localProxy=true");
                throw new IOException("Local proxy upstream ignored initial Range " + start + "-" + end + " for " + url);
            }
        }
        InputStream stream = new ReconnectingRangeInputStream(
                url,
                requestHeaders,
                response,
                start,
                end,
                totalLength,
                LOCAL_PROXY_FETCH_CHUNK_SIZE,
                firstPayloadStart,
                contentRangeInfo != null
                        ? Math.min(contentRangeInfo.end, end)
                        : (usingWholeResponseAfterSmallIgnoredRange ? end : Math.min(end, start + LOCAL_PROXY_FETCH_CHUNK_SIZE - 1L))
        );
        result[2] = stream;
        Map<String, String> responseHeaders = new HashMap<>();
        for (String headerName : PASSTHROUGH_RESPONSE_HEADERS) {
            copyHeader(response, responseHeaders, headerName);
        }
        responseHeaders.put("Content-Length", String.valueOf(normalizedLength));
        responseHeaders.put("Accept-Ranges", "bytes");
        if ((int) result[0] == 206) {
            String rangeHeader = contentRangeInfo != null
                    ? "bytes " + start + "-" + end + "/" + Math.max(totalLength, contentRangeInfo.total)
                    : "bytes " + start + "-" + end + "/" + totalLength;
            responseHeaders.put("Content-Range", rangeHeader);
        } else {
            responseHeaders.remove("Content-Range");
        }
        responseHeaders.remove("Transfer-Encoding");
        SpiderDebug.log("passthroughLocalProxyRange code=" + result[0]
                + " type=" + result[1]
                + " range=" + start + "-" + end + "/" + totalLength
                + " explicitEnd=" + clientHadExplicitEnd
                + " chunkedBackend=" + (end - start + 1L > LOCAL_PROXY_FETCH_CHUNK_SIZE)
                + " firstChunkEnd=" + (contentRangeInfo == null ? null : contentRangeInfo.end)
                + " localProxy=true");
        result[3] = responseHeaders;
        return result;
    }

    private static Object[] buildPassthroughResult(String url, Map<String, String> requestHeaders, Response response) throws IOException {
        if (!response.isSuccessful() || response.body() == null) {
            if (response != null) {
                response.close();
            }
            throw new IOException("Stream request failed with code: " + (response == null ? "unknown" : response.code()));
        }
        Object[] result = new Object[4];
        boolean hasRangeRequest = !TextUtils.isEmpty(findHeaderValue(requestHeaders, "Range"));
        ContentRangeInfo contentRangeInfo = parseContentRange(response.header("Content-Range"));
        result[0] = normalizePassthroughStatusCode(response.code(), hasRangeRequest, contentRangeInfo);
        String contentType = response.header("Content-Type");
        result[1] = normalizePassthroughContentType(url, contentType);
        long normalizedLength = normalizePassthroughContentLength(response, hasRangeRequest, contentRangeInfo);
        InputStream stream = response.body().byteStream();
        if (normalizedLength >= 0) {
            stream = new BoundedInputStream(stream, normalizedLength);
        }
        result[2] = new ResponseClosingInputStream(stream, response);
        Map<String, String> responseHeaders = new HashMap<>();
        for (String headerName : PASSTHROUGH_RESPONSE_HEADERS) {
            copyHeader(response, responseHeaders, headerName);
        }
        if (normalizedLength >= 0) {
            responseHeaders.put("Content-Length", String.valueOf(normalizedLength));
        } else {
            responseHeaders.remove("Content-Length");
        }
        if ((int) result[0] == 206 && contentRangeInfo != null) {
            responseHeaders.put("Content-Range", contentRangeInfo.rawValue);
        } else if (!hasRangeRequest) {
            responseHeaders.remove("Content-Range");
        }
        responseHeaders.remove("Transfer-Encoding");
        if (!responseHeaders.containsKey("Accept-Ranges")) {
            responseHeaders.put("Accept-Ranges", "bytes");
        }
        SpiderDebug.log("passthroughStream code=" + result[0]
                + " upstreamCode=" + response.code()
                + " type=" + result[1]
                + " rangeRequest=" + hasRangeRequest
                + " range=" + (contentRangeInfo == null ? null : contentRangeInfo.rawValue)
                + " length=" + normalizedLength
                + " localProxy=" + isLocalProxyPlayUrl(url));
        result[3] = responseHeaders;
        return result;
    }

    private static Response executeRequest(OkHttpClient client, Request request) throws IOException {
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            System.err.println("网络请求异常：" + e.getMessage());
            throw e; // 重新抛出异常，让外层处理
        }
    }

    private static Response executeStreamRequest(String url, Map<String, String> requestHeaders, boolean localProxyPlayUrl) throws IOException {
        IOException lastException = null;
        int maxAttempts = localProxyPlayUrl ? LOCAL_PROXY_STREAM_MAX_RETRIES : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Response response = executeRequest(getStreamClient(localProxyPlayUrl), buildRequest(url, requestHeaders));
                if (localProxyPlayUrl) {
                    SpiderDebug.log("passthroughStream response attempt=" + attempt
                            + " code=" + response.code()
                            + " rangeReq=" + findHeaderValue(requestHeaders, "Range")
                            + " rangeResp=" + response.header("Content-Range")
                            + " length=" + (response.body() == null ? -1L : response.body().contentLength()));
                }
                return response;
            } catch (IOException e) {
                lastException = e;
                SpiderDebug.log("passthroughStream retry " + attempt + "/" + maxAttempts
                        + " url=" + url + " err=" + e.getMessage());
                if (attempt >= maxAttempts) {
                    throw e;
                }
                try {
                    Thread.sleep(LOCAL_PROXY_STREAM_RETRY_DELAY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastException == null ? new IOException("Unknown stream proxy failure") : lastException;
    }

    private static OkHttpClient getStreamClient(boolean localProxyPlayUrl) {
        if (!localProxyPlayUrl) {
            return OkGoHelper.ItvClient;
        }
        OkHttpClient client = localProxyStreamClient;
        if (client != null) {
            return client;
        }
        synchronized (Proxy.class) {
            client = localProxyStreamClient;
            if (client == null) {
                OkHttpClient baseClient = OkGoHelper.ItvClient;
                OkHttpClient.Builder builder = baseClient != null
                        ? baseClient.newBuilder()
                        : new OkHttpClient.Builder();
                // Seek-heavy local proxy playback is much more stable when each range reopen avoids
                // OkHttp's long-lived HTTP/2 stream reuse.
                builder.protocols(Collections.singletonList(Protocol.HTTP_1_1));
                builder.connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS));
                builder.retryOnConnectionFailure(true);
                client = builder.build();
                localProxyStreamClient = client;
            }
        }
        return client;
    }

    private static Response openChunkResponse(String url, Map<String, String> requestHeaders, long start, long end) throws IOException {
        HashMap<String, String> chunkHeaders = new HashMap<>();
        if (requestHeaders != null) {
            chunkHeaders.putAll(requestHeaders);
        }
        if (end >= start) {
            chunkHeaders.put("Range", "bytes=" + start + "-" + end);
        } else {
            chunkHeaders.put("Range", "bytes=" + start + "-");
        }
        return executeStreamRequest(url, chunkHeaders, true);
    }

    private static String processM3u8Content(String m3u8Content, String m3u8Url, Map<String, String> headers) {
        if (TextUtils.isEmpty(m3u8Content)) {
            return "";
        }
        String[] m3u8Lines = m3u8Content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder processedM3u8 = new StringBuilder();

        for (String rawLine : m3u8Lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (TextUtils.isEmpty(line)) {
                processedM3u8.append("\n");
                continue;
            }
            if (line.startsWith("#")) {
                processedM3u8.append(rewriteM3u8DirectiveLine(rawLine, m3u8Url, headers)).append("\n");
            } else {
                processedM3u8.append(joinUrl(m3u8Url, line, headers)).append("\n");
            }
        }
        return processedM3u8.toString();
    }

    private static String rewriteM3u8DirectiveLine(String rawLine, String baseUrl, Map<String, String> headers) {
        if (TextUtils.isEmpty(rawLine) || !rawLine.contains("URI=")) {
            return rawLine;
        }
        Matcher matcher = M3U8_URI_ATTRIBUTE_PATTERN.matcher(rawLine);
        StringBuffer buffer = new StringBuffer();
        boolean replaced = false;
        while (matcher.find()) {
            String target = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String rewritten = joinUrl(baseUrl, target, headers);
            String quote = matcher.group(1) != null && matcher.group(1).startsWith("'") ? "'" : "\"";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("URI=" + quote + rewritten + quote));
            replaced = true;
        }
        if (!replaced) {
            return rawLine;
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String joinUrl(String base, String url, Map<String, String> headers) {
        if (base == null) base = "";
        if (url == null) url = "";
        try {
            URI baseUri = new URI(base.trim());
            url = url.trim();
            URI urlUri = new URI(url);
            String resolvedUrl;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                resolvedUrl = urlUri.toString();
            } else if (url.startsWith("://")) {
                resolvedUrl = new URI(baseUri.getScheme() + url).toString();
            } else if (url.startsWith("//")) {
                resolvedUrl = new URI(baseUri.getScheme() + ":" + url).toString();
            } else {
                resolvedUrl = baseUri.resolve(url).toString();
            }
            return buildLiveProxyUrl(resolvedUrl, headers);
        } catch (Exception e) {
            e.printStackTrace();
            return url;
        }
    }

    private static String buildLiveProxyUrl(String resolvedUrl, Map<String, String> headers) throws Exception {
        String localAddress = ControlManager.get().getAddress(true);
        if (TextUtils.isEmpty(localAddress) || TextUtils.isEmpty(resolvedUrl)) {
            return resolvedUrl;
        }
        String type = looksLikeM3u8(resolvedUrl) ? "m3u8" : "ts";
        return localAddress + "proxy?go=live&type=" + type + "&url="
                + URLEncoder.encode(resolvedUrl, "UTF-8") + buildHeaderQuery(headers);
    }

    private static boolean looksLikeM3u8(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".m3u8")
                || lower.contains("format=m3u8")
                || lower.contains("type=m3u8")
                || lower.contains("application/vnd.apple.mpegurl");
    }

    public static String getRedirectedUrl(String url, Map<String, String> headers) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .followRedirects(false) // 不自动跟随重定向
                .build();

        Request request = buildRequest(url, headers);

        try (Response response = client.newCall(request).execute()) {
            if (response.isRedirect()) { // 判断是否为重定向
                return response.header("Location"); // 获取重定向后的地址
            }
            return url; // 如果没有重定向，返回原 URL
        }
    }

    public static String getM3U8Content(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();

        OkHttpClient client = OkGoHelper.ItvClient;
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.body().string(); // 获取 m3u8 文件内容
            } else {
                throw new IOException("请求失败，HTTP 状态码: " + response.code());
            }
        }
    }

    private static Request buildRequest(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (isInternalPlaybackHeader(entry.getKey())) {
                    continue;
                }
                builder.header(entry.getKey(), entry.getValue().trim());
            }
        }
        return builder.build();
    }

    private static Map<String, String> extractProxyHeaders(Map<String, String> params) {
        HashMap<String, String> headers = new HashMap<>();
        try {
            String rawHeader = params.get("header");
            if (rawHeader != null && !rawHeader.trim().isEmpty()) {
                String decoded = URLDecoder.decode(rawHeader, "UTF-8");
                org.json.JSONObject headerJson = new org.json.JSONObject(decoded);
                java.util.Iterator<String> iterator = headerJson.keys();
                while (iterator.hasNext()) {
                    String key = iterator.next();
                    String value = headerJson.optString(key, "");
                    if (isInternalPlaybackHeader(key)) {
                        continue;
                    }
                    if (!value.trim().isEmpty()) {
                        headers.put(key, value.trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return headers;
    }

    private static void mergeRequestHeadersFromSession(Map<String, String> params, Map<String, String> targetHeaders) {
        if (params == null || targetHeaders == null) {
            return;
        }
        for (String headerName : PASSTHROUGH_REQUEST_HEADERS) {
            String value = findHeaderValue(params, headerName);
            if (value != null && !value.trim().isEmpty()) {
                targetHeaders.put(headerName, value.trim());
            }
        }
    }

    private static String findHeaderValue(Map<String, String> params, String headerName) {
        if (params == null || headerName == null) {
            return null;
        }
        String direct = params.get(headerName);
        if (direct != null) {
            return direct;
        }
        String lower = params.get(headerName.toLowerCase());
        if (lower != null) {
            return lower;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isInternalPlaybackHeader(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        String lower = key.trim().toLowerCase(Locale.US);
        return lower.startsWith("x-tvbox-probe-");
    }

    private static String buildHeaderQuery(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        try {
            org.json.JSONObject headerJson = new org.json.JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                    continue;
                }
                headerJson.put(entry.getKey(), entry.getValue().trim());
            }
            if (headerJson.length() == 0) {
                return "";
            }
            return "&header=" + URLEncoder.encode(headerJson.toString(), "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void copyHeader(Response response, Map<String, String> target, String name) {
        if (response == null || target == null || name == null) {
            return;
        }
        String value = response.header(name);
        if (value != null && !value.trim().isEmpty()) {
            target.put(name, value.trim());
        }
    }

    private static int normalizePassthroughStatusCode(int upstreamCode, boolean hasRangeRequest, ContentRangeInfo contentRangeInfo) {
        if (contentRangeInfo == null) {
            return upstreamCode;
        }
        if (upstreamCode == 206) {
            return 206;
        }
        if (hasRangeRequest) {
            return 206;
        }
        if (contentRangeInfo.isPartial()) {
            return 206;
        }
        return upstreamCode;
    }

    private static long normalizePassthroughContentLength(Response response, boolean hasRangeRequest, ContentRangeInfo contentRangeInfo) {
        if (contentRangeInfo != null) {
            long rangeLength = contentRangeInfo.length();
            if (rangeLength >= 0 && (hasRangeRequest || contentRangeInfo.isPartial() || response.code() == 206)) {
                return rangeLength;
            }
            if (!hasRangeRequest
                    && contentRangeInfo.start == 0L
                    && contentRangeInfo.total > 0L
                    && contentRangeInfo.end + 1L >= contentRangeInfo.total) {
                return contentRangeInfo.total;
            }
        }
        if (response == null || response.body() == null) {
            return -1L;
        }
        long bodyLength = response.body().contentLength();
        return bodyLength >= 0 ? bodyLength : -1L;
    }

    private static boolean isLocalProxyPlayUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && path != null
                    && path.contains("/proxy/play/")) {
                return true;
            }
        } catch (Exception ignored) {
        }
        String lower = url.toLowerCase(Locale.US);
        return (lower.startsWith("http://127.0.0.1")
                || lower.startsWith("https://127.0.0.1")
                || lower.startsWith("http://localhost")
                || lower.startsWith("https://localhost"))
                && lower.contains("/proxy/play/");
    }

    private static boolean isMatroskaLike(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".mkv") || lower.contains(".webm");
    }

    private static RangeRequestInfo parseRangeRequest(String headerValue) {
        if (TextUtils.isEmpty(headerValue)) {
            return null;
        }
        try {
            String value = headerValue.trim();
            if (!value.startsWith("bytes=")) {
                return null;
            }
            String spec = value.substring("bytes=".length()).trim();
            String[] parts = spec.split("-", 2);
            if (parts.length != 2) {
                return null;
            }
            String rawStart = parts[0] == null ? "" : parts[0].trim();
            String rawEnd = parts[1] == null ? "" : parts[1].trim();
            if (TextUtils.isEmpty(rawStart)) {
                if (TextUtils.isEmpty(rawEnd)) {
                    return null;
                }
                long suffixLength = Long.parseLong(rawEnd);
                if (suffixLength < 0L) {
                    return null;
                }
                return RangeRequestInfo.forSuffix(suffixLength);
            }
            long start = Long.parseLong(rawStart);
            boolean hasEnd = !TextUtils.isEmpty(parts[1]);
            long end = hasEnd ? Long.parseLong(rawEnd) : -1L;
            if (start < 0L || (hasEnd && end < start)) {
                return null;
            }
            return new RangeRequestInfo(start, end, hasEnd);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object[] buildRangeNotSatisfiable(long totalLength) {
        Object[] result = new Object[4];
        result[0] = 416;
        result[1] = "application/octet-stream";
        result[2] = new ByteArrayInputStream(new byte[0]);
        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Content-Length", "0");
        responseHeaders.put("Accept-Ranges", "bytes");
        responseHeaders.put("Content-Range", "bytes */" + Math.max(totalLength, 0L));
        SpiderDebug.log("passthroughLocalProxyRange 416 total=" + Math.max(totalLength, 0L));
        result[3] = responseHeaders;
        return result;
    }

    private static long resolveTotalLength(Response response, ContentRangeInfo contentRangeInfo) {
        return resolveTotalLength(response, contentRangeInfo, null);
    }

    private static long resolveTotalLength(Response response,
                                           ContentRangeInfo contentRangeInfo,
                                           RangeRequestInfo requestRange) {
        if (contentRangeInfo != null && contentRangeInfo.total > 0L) {
            return contentRangeInfo.total;
        }
        if (response == null || response.body() == null) {
            return -1L;
        }
        long bodyLength = response.body().contentLength();
        if (bodyLength <= 0L) {
            return -1L;
        }
        if (requestRange != null && !requestRange.suffix && requestRange.start >= 0L) {
            // Some upstreams answer seek reopens with 206 + Content-Length but omit Content-Range.
            // In that case bodyLength is only the remaining window, not the file size.
            if (contentRangeInfo == null && (response.code() == 200 || response.code() == 206)) {
                long inferredTotal = requestRange.hasEnd
                        ? Math.max(requestRange.end + 1L, requestRange.start + bodyLength)
                        : requestRange.start + bodyLength;
                SpiderDebug.log("passthroughLocalProxyRange inferredTotal"
                        + " start=" + requestRange.start
                        + " end=" + (requestRange.hasEnd ? requestRange.end : -1L)
                        + " bodyLength=" + bodyLength
                        + " code=" + response.code()
                        + " inferred=" + inferredTotal);
                return inferredTotal;
            }
        }
        return bodyLength;
    }

    private static ContentRangeInfo parseContentRange(String headerValue) {
        if (TextUtils.isEmpty(headerValue)) {
            return null;
        }
        try {
            String value = headerValue.trim();
            if (!value.startsWith("bytes")) {
                return null;
            }
            String[] rangeAndTotal = value.substring("bytes".length()).trim().split("/", 2);
            if (rangeAndTotal.length != 2) {
                return null;
            }
            String[] startEnd = rangeAndTotal[0].trim().split("-", 2);
            if (startEnd.length != 2) {
                return null;
            }
            long start = Long.parseLong(startEnd[0].trim());
            long end = Long.parseLong(startEnd[1].trim());
            long total = "*".equals(rangeAndTotal[1].trim()) ? -1L : Long.parseLong(rangeAndTotal[1].trim());
            if (start < 0 || end < start) {
                return null;
            }
            return new ContentRangeInfo(value, start, end, total);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizePassthroughContentType(String url, String upstreamContentType) {
        if (!TextUtils.isEmpty(upstreamContentType)) {
            String normalized = upstreamContentType.trim().toLowerCase();
            if (!normalized.isEmpty()
                    && !"application/octet-stream".equals(normalized)
                    && !"application/oct-stream".equals(normalized)
                    && !"binary/octet-stream".equals(normalized)
                    && !"video/*".equals(normalized)) {
                return upstreamContentType.trim();
            }
        }
        String lowerUrl = url == null ? "" : url.toLowerCase();
        if (lowerUrl.contains(".mp4")) {
            return "video/mp4";
        }
        if (lowerUrl.contains(".mkv")) {
            return "video/x-matroska";
        }
        if (lowerUrl.contains(".webm")) {
            return "video/webm";
        }
        if (lowerUrl.contains(".ts")) {
            return "video/mp2t";
        }
        if (lowerUrl.contains(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (lowerUrl.contains(".mp3")) {
            return "audio/mpeg";
        }
        if (lowerUrl.contains(".aac")) {
            return "audio/aac";
        }
        return "video/*";
    }

    private static final class ResponseClosingInputStream extends InputStream {
        private final InputStream delegate;
        private final Response response;

        private ResponseClosingInputStream(InputStream delegate, Response response) {
            this.delegate = delegate;
            this.response = response;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return delegate.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                response.close();
            }
        }
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = Math.max(remaining, 0L);
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            } else {
                remaining = 0;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int allowed = (int) Math.min(len, remaining);
            int count = delegate.read(b, off, allowed);
            if (count > 0) {
                remaining -= count;
            } else if (count < 0) {
                remaining = 0;
            }
            return count;
        }
    }

    private static final class ContentRangeInfo {
        private final String rawValue;
        private final long start;
        private final long end;
        private final long total;

        private ContentRangeInfo(String rawValue, long start, long end, long total) {
            this.rawValue = rawValue;
            this.start = start;
            this.end = end;
            this.total = total;
        }

        private long length() {
            return end - start + 1L;
        }

        private boolean isPartial() {
            return total > 0 && length() < total;
        }
    }

    private static final class RangeRequestInfo {
        private final long start;
        private final long end;
        private final boolean hasEnd;
        private final boolean suffix;
        private final long suffixLength;

        private RangeRequestInfo(long start, long end, boolean hasEnd) {
            this(start, end, hasEnd, false, -1L);
        }

        private RangeRequestInfo(long start, long end, boolean hasEnd, boolean suffix, long suffixLength) {
            this.start = start;
            this.end = end;
            this.hasEnd = hasEnd;
            this.suffix = suffix;
            this.suffixLength = suffixLength;
        }

        private static RangeRequestInfo forSuffix(long suffixLength) {
            return new RangeRequestInfo(0L, -1L, false, true, suffixLength);
        }
    }

    private static final class ReconnectingRangeInputStream extends InputStream {
        private final String url;
        private final Map<String, String> requestHeaders;
        private final long targetEnd;
        private final long totalLength;
        private final long chunkSize;
        private Response currentResponse;
        private InputStream currentStream;
        private long absoluteCursor;
        private long currentChunkEnd;
        private int prematureEofCount;
        private long lastOpenStart = -1L;
        private long lastOpenEnd = -1L;
        private int reopenCountForSameRange;

        private ReconnectingRangeInputStream(String url,
                                             Map<String, String> requestHeaders,
                                             Response firstResponse,
                                             long start,
                                             long targetEnd,
                                             long totalLength,
                                             long chunkSize,
                                             long firstPayloadStart,
                                             long firstRequestedEnd) throws IOException {
            this.url = url;
            this.requestHeaders = new HashMap<>(requestHeaders);
            this.currentResponse = firstResponse;
            this.currentStream = firstResponse.body().byteStream();
            this.absoluteCursor = start;
            this.targetEnd = targetEnd;
            this.totalLength = totalLength;
            this.chunkSize = Math.max(1L, chunkSize);
            ContentRangeInfo firstRange = parseContentRange(firstResponse.header("Content-Range"));
            if (firstRange != null) {
                alignRangeStart(firstRange, start, "initial");
                this.currentChunkEnd = Math.min(firstRange.end, targetEnd);
            } else {
                long firstLength = firstResponse.body().contentLength();
                long requestedPayloadEnd = Math.min(targetEnd, firstRequestedEnd);
                long requestedPayloadLength = requestedPayloadEnd >= firstPayloadStart
                        ? requestedPayloadEnd - firstPayloadStart + 1L
                        : targetEnd - firstPayloadStart + 1L;
                long requestedClientLength = requestedPayloadEnd >= start
                        ? requestedPayloadEnd - start + 1L
                        : targetEnd - start + 1L;
                if (start > 0L
                        && firstResponse.code() != 206
                        && (firstLength < 0L || firstLength > requestedClientLength)
                        && start > LOCAL_PROXY_MAX_RANGE_DISCARD) {
                    closeCurrentChunk();
                    SpiderDebug.log("passthroughLocalProxyRange upstreamIgnoredRange start=" + start
                            + " requested=" + requestedClientLength
                            + " bodyLength=" + firstLength
                            + " code=" + firstResponse.code());
                    throw new IOException("Local proxy upstream ignored Range at " + start + " for " + url);
                }
                this.currentChunkEnd = firstLength > 0L
                        ? Math.min(targetEnd, firstPayloadStart + Math.min(firstLength, requestedPayloadLength) - 1L)
                        : Math.min(targetEnd, firstRequestedEnd);
                long skipBytes = Math.max(0L, start - firstPayloadStart);
                if (skipBytes > 0L) {
                    discardInput(this.currentStream, skipBytes);
                }
            }
        }

        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : buffer[0] & 0xff;
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (absoluteCursor > targetEnd) {
                return -1;
            }
            int totalRead = 0;
            while (len > 0 && absoluteCursor <= targetEnd) {
                ensureChunkOpen();
                int allowed = (int) Math.min(len, currentChunkEnd - absoluteCursor + 1L);
                int count;
                try {
                    count = currentStream.read(b, off, allowed);
                } catch (IOException e) {
                    reopenChunk();
                    continue;
                }
                if (count == 0) {
                    reopenChunk();
                    continue;
                }
                if (count < 0) {
                    if (absoluteCursor <= currentChunkEnd) {
                        reopenChunk();
                        continue;
                    }
                    closeCurrentChunk();
                    continue;
                }
                prematureEofCount = 0;
                absoluteCursor += count;
                totalRead += count;
                off += count;
                len -= count;
                if (absoluteCursor > currentChunkEnd) {
                    closeCurrentChunk();
                }
            }
            return totalRead > 0 ? totalRead : -1;
        }

        private void ensureChunkOpen() throws IOException {
            if (currentStream != null) {
                return;
            }
            if (absoluteCursor > targetEnd) {
                return;
            }
            long end = Math.min(targetEnd, absoluteCursor + chunkSize - 1L);
            Response response = openChunkResponse(url, requestHeaders, absoluteCursor, end);
            if (!response.isSuccessful() || response.body() == null) {
                if (response != null) {
                    response.close();
                }
                throw new IOException("Chunk reopen failed for " + absoluteCursor + "-" + end);
            }
            if (lastOpenStart == absoluteCursor && lastOpenEnd == end) {
                reopenCountForSameRange++;
            } else {
                lastOpenStart = absoluteCursor;
                lastOpenEnd = end;
                reopenCountForSameRange = 0;
            }
            if (reopenCountForSameRange > LOCAL_PROXY_MAX_PREMATURE_EOF + 1) {
                response.close();
                throw new IOException("Local proxy chunk reopen loop at " + absoluteCursor + "-" + end + " for " + url);
            }
            currentResponse = response;
            currentStream = response.body().byteStream();
            ContentRangeInfo rangeInfo = parseContentRange(response.header("Content-Range"));
            if (rangeInfo != null) {
                alignRangeStart(rangeInfo, absoluteCursor, "chunk");
                currentChunkEnd = Math.min(rangeInfo.end, targetEnd);
            } else {
                long bodyLength = response.body().contentLength();
                long requestedLength = end - absoluteCursor + 1L;
                if (absoluteCursor > 0L
                        && response.code() != 206
                        && (bodyLength < 0L || bodyLength > requestedLength)) {
                    closeCurrentChunk();
                    SpiderDebug.log("passthroughLocalProxyRange chunkIgnoredRange start=" + absoluteCursor
                            + " requested=" + requestedLength
                            + " bodyLength=" + bodyLength
                            + " code=" + response.code());
                    throw new IOException("Local proxy upstream ignored chunk Range at " + absoluteCursor + " for " + url);
                }
                currentChunkEnd = bodyLength > 0L
                        ? Math.min(targetEnd, absoluteCursor + bodyLength - 1L)
                        : Math.min(targetEnd, end);
            }
            if (currentChunkEnd < absoluteCursor) {
                closeCurrentChunk();
                throw new IOException("Invalid chunk range " + absoluteCursor + "-" + currentChunkEnd + " for " + url);
            }
        }

        private void reopenChunk() throws IOException {
            closeCurrentChunk();
            prematureEofCount++;
            if (prematureEofCount > LOCAL_PROXY_MAX_PREMATURE_EOF) {
                throw new IOException("Too many premature EOFs while reading local proxy stream at " + absoluteCursor
                        + "/" + totalLength);
            }
        }

        private void closeCurrentChunk() throws IOException {
            IOException thrown = null;
            try {
                if (currentStream != null) {
                    currentStream.close();
                }
            } catch (IOException e) {
                thrown = e;
            } finally {
                currentStream = null;
                if (currentResponse != null) {
                    currentResponse.close();
                    currentResponse = null;
                }
            }
            if (thrown != null) {
                throw thrown;
            }
        }

        private void alignRangeStart(ContentRangeInfo rangeInfo, long expectedStart, String phase) throws IOException {
            if (rangeInfo.start > expectedStart || rangeInfo.end < expectedStart) {
                closeCurrentChunk();
                SpiderDebug.log("passthroughLocalProxyRange upstreamWrongRange phase=" + phase
                        + " expected=" + expectedStart
                        + " got=" + rangeInfo.rawValue);
                throw new IOException("Local proxy upstream returned wrong range " + rangeInfo.rawValue
                        + " while expecting " + expectedStart + " for " + url);
            }
            long discard = expectedStart - rangeInfo.start;
            if (discard <= 0L) {
                return;
            }
            if (rangeInfo.start == 0L && expectedStart > 0L && discard <= LOCAL_PROXY_MAX_RANGE_DISCARD) {
                SpiderDebug.log("passthroughLocalProxyRange tolerateRestartFromZero phase=" + phase
                        + " expected=" + expectedStart
                        + " got=" + rangeInfo.rawValue
                        + " discard=" + discard);
            }
            if (discard > LOCAL_PROXY_MAX_RANGE_DISCARD) {
                closeCurrentChunk();
                SpiderDebug.log("passthroughLocalProxyRange upstreamIgnoredRange phase=" + phase
                        + " expected=" + expectedStart
                        + " got=" + rangeInfo.rawValue
                        + " discard=" + discard);
                throw new IOException("Local proxy upstream ignored large Range at " + expectedStart + " for " + url);
            }
            SpiderDebug.log("passthroughLocalProxyRange discardPrefix phase=" + phase
                    + " expected=" + expectedStart
                    + " got=" + rangeInfo.rawValue
                    + " discard=" + discard);
            discardInput(currentStream, discard);
        }

        @Override
        public void close() throws IOException {
            lastOpenStart = -1L;
            lastOpenEnd = -1L;
            reopenCountForSameRange = 0;
            closeCurrentChunk();
        }

        private void discardInput(InputStream stream, long bytesToSkip) throws IOException {
            long remaining = bytesToSkip;
            byte[] scratch = new byte[8192];
            while (remaining > 0L) {
                int read = stream.read(scratch, 0, (int) Math.min(scratch.length, remaining));
                if (read < 0) {
                    throw new IOException("Unexpected EOF while discarding " + bytesToSkip + " bytes at " + absoluteCursor);
                }
                remaining -= read;
            }
        }
    }

}
