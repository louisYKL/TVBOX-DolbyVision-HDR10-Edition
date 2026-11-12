package com.github.tvbox.osc.webdav;

import android.text.TextUtils;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.net.URI;
import java.net.URISyntaxException;

import okhttp3.Credentials;
import okhttp3.HttpUrl;

public final class WebDavConfig {
    public static final int DEFAULT_PORT = 5244;

    public final String baseUrl;
    public final String username;
    public final String password;

    private WebDavConfig(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    public static WebDavConfig load() {
        return new WebDavConfig(
                Hawk.get(HawkConfig.WEBDAV_URL, ""),
                Hawk.get(HawkConfig.WEBDAV_USERNAME, ""),
                Hawk.get(HawkConfig.WEBDAV_PASSWORD, ""));
    }

    public static boolean save(String address, String username, String password) {
        String normalized = normalizeAddress(address);
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        Hawk.put(HawkConfig.WEBDAV_URL, normalized);
        Hawk.put(HawkConfig.WEBDAV_USERNAME, username == null ? "" : username.trim());
        Hawk.put(HawkConfig.WEBDAV_PASSWORD, password == null ? "" : password);
        return true;
    }

    public boolean isConfigured() {
        return !TextUtils.isEmpty(baseUrl);
    }

    public String authorizationHeader() {
        if (TextUtils.isEmpty(username) && TextUtils.isEmpty(password)) {
            return null;
        }
        return Credentials.basic(username == null ? "" : username,
                password == null ? "" : password);
    }

    public static String normalizeAddress(String input) {
        if (TextUtils.isEmpty(input)) {
            return null;
        }
        String address = input.trim();
        if (address.indexOf("://") < 0) {
            address = "http://" + address;
        }
        HttpUrl parsed = HttpUrl.parse(address);
        if (parsed == null || (!"http".equals(parsed.scheme()) && !"https".equals(parsed.scheme()))) {
            return null;
        }
        try {
            URI uri = new URI(address);
            HttpUrl.Builder builder = parsed.newBuilder();
            if (uri.getPort() < 0) {
                builder.port(DEFAULT_PORT);
            }
            String path = parsed.encodedPath();
            if ("/".equals(path)) {
                builder.addPathSegment("dav");
            } else if (path.endsWith("/dav/")) {
                return builder.build().toString();
            } else if (path.endsWith("/dav")) {
                builder.addPathSegment("");
                return builder.build().toString();
            } else {
                builder.addPathSegment("dav");
            }
            builder.addPathSegment("");
            return builder.build().toString();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
