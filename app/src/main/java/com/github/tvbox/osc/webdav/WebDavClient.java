package com.github.tvbox.osc.webdav;

import android.content.Context;
import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class WebDavClient {
    private static final int MAX_LISTING_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_SUBTITLE_BYTES = 8 * 1024 * 1024;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/>"
            + "<d:resourcetype/><d:getcontentlength/><d:getcontenttype/>"
            + "</d:prop></d:propfind>";

    private final WebDavConfig config;
    private final HttpUrl baseUrl;

    public WebDavClient(WebDavConfig config) {
        if (config == null || !config.isConfigured()) {
            throw new IllegalArgumentException("WebDAV is not configured");
        }
        this.config = config;
        this.baseUrl = HttpUrl.parse(config.baseUrl);
        if (baseUrl == null) {
            throw new IllegalArgumentException("Invalid WebDAV address");
        }
    }

    public String rootUrl() {
        return config.baseUrl;
    }

    public List<WebDavEntry> list(String collectionUrl) throws IOException {
        HttpUrl target = resolveAndValidate(collectionUrl);
        Request.Builder request = new Request.Builder()
                .url(target)
                .header("Depth", "1")
                .header("Accept", "application/xml, text/xml")
                .method("PROPFIND", RequestBody.create(
                        MediaType.parse("application/xml; charset=utf-8"), PROPFIND_BODY));
        addAuthorization(request);
        try (Response response = HTTP.newCall(request.build()).execute()) {
            if (!response.isSuccessful() && response.code() != 207) {
                throw new IOException("WebDAV returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("WebDAV returned an empty directory response");
            }
            byte[] xml = readLimited(body.byteStream(), MAX_LISTING_BYTES);
            return parseEntries(xml, target);
        }
    }

    public File downloadSubtitle(Context context, WebDavEntry entry) throws IOException {
        if (context == null || entry == null || entry.directory) {
            throw new IOException("Invalid subtitle entry");
        }
        HttpUrl target = resolveAndValidate(entry.url);
        Request.Builder request = new Request.Builder().url(target).get();
        addAuthorization(request);
        try (Response response = HTTP.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Subtitle download returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Subtitle response is empty");
            }
            byte[] bytes = readLimited(body.byteStream(), MAX_SUBTITLE_BYTES);
            if (bytes.length == 0) {
                throw new IOException("Subtitle is empty");
            }
            File directory = new File(context.getCacheDir(), "webdav-subtitles");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Cannot create subtitle cache directory");
            }
            File result = new File(directory, sha256(entry.url) + extension(entry.name));
            try (FileOutputStream output = new FileOutputStream(result)) {
                output.write(bytes);
            }
            return result;
        }
    }

    private List<WebDavEntry> parseEntries(byte[] xml, HttpUrl currentUrl) throws IOException {
        ArrayList<WebDavEntry> entries = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
            String href = null;
            String displayName = null;
            String contentLength = null;
            boolean isDirectory = false;
            boolean inResponse = false;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT && entries.size() < MAX_ENTRIES) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("response".equals(tag)) {
                        inResponse = true;
                        href = null;
                        displayName = null;
                        contentLength = null;
                        isDirectory = false;
                    } else if (inResponse && "href".equals(tag)) {
                        href = parser.nextText();
                    } else if (inResponse && "displayname".equals(tag)) {
                        displayName = parser.nextText();
                    } else if (inResponse && "getcontentlength".equals(tag)) {
                        contentLength = parser.nextText();
                    } else if (inResponse && "collection".equals(tag)) {
                        isDirectory = true;
                    }
                } else if (event == XmlPullParser.END_TAG && "response".equals(parser.getName())) {
                    inResponse = false;
                    if (!TextUtils.isEmpty(href)) {
                        HttpUrl entryUrl = resolveAndValidate(href);
                        if (!sameResource(entryUrl, currentUrl)) {
                            if (isDirectory && !entryUrl.encodedPath().endsWith("/")) {
                                entryUrl = entryUrl.newBuilder().addPathSegment("").build();
                            }
                            String name = TextUtils.isEmpty(displayName)
                                    ? lastPathSegment(entryUrl) : displayName.trim();
                            long size = parseLong(contentLength);
                            entries.add(new WebDavEntry(name, entryUrl.toString(), isDirectory, size));
                        }
                    }
                }
                event = parser.next();
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Cannot parse WebDAV directory response", e);
        }
        Collections.sort(entries, new Comparator<WebDavEntry>() {
            @Override
            public int compare(WebDavEntry left, WebDavEntry right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        return entries;
    }

    private HttpUrl resolveAndValidate(String value) throws IOException {
        HttpUrl resolved = baseUrl.resolve(value);
        if (resolved == null
                || !baseUrl.scheme().equals(resolved.scheme())
                || !baseUrl.host().equalsIgnoreCase(resolved.host())
                || baseUrl.port() != resolved.port()) {
            throw new IOException("WebDAV returned a URL outside the configured server");
        }
        String rootPath = baseUrl.encodedPath();
        String resolvedPath = resolved.encodedPath();
        if (!resolvedPath.startsWith(rootPath)) {
            throw new IOException("WebDAV returned a path outside the configured root");
        }
        return resolved;
    }

    private void addAuthorization(Request.Builder request) {
        String authorization = config.authorizationHeader();
        if (!TextUtils.isEmpty(authorization)) {
            request.header("Authorization", authorization);
        }
    }

    private static boolean sameResource(HttpUrl left, HttpUrl right) {
        return left.scheme().equals(right.scheme())
                && left.host().equalsIgnoreCase(right.host())
                && left.port() == right.port()
                && left.encodedPath().equals(right.encodedPath());
    }

    private static String lastPathSegment(HttpUrl url) {
        List<String> segments = url.pathSegments();
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (!TextUtils.isEmpty(segments.get(i))) {
                return segments.get(i);
            }
        }
        return url.host();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() > maxBytes - read) {
                throw new IOException("WebDAV response exceeds the allowed size");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? ".srt" : name.substring(dot).toLowerCase(java.util.Locale.US);
    }

    private static String sha256(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IOException("Cannot name subtitle cache file", e);
        }
    }
}
