package com.github.tvbox.osc.webdav;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.net.URI;
import java.net.URISyntaxException;

import okhttp3.Credentials;
import okhttp3.HttpUrl;

public final class WebDavConfig {
    public static final int DEFAULT_PORT = 5244;
    /** The standard TLS port is used when an HTTPS WebDAV address omits a port. */
    public static final int DEFAULT_HTTPS_PORT = 443;
    private static final String MIRROR_PREFS = "webdav_config";
    private static final String MIRROR_URL = "url";
    private static final String MIRROR_USERNAME = "username";
    private static final String MIRROR_PASSWORD = "password";

    public final String baseUrl;
    public final String username;
    public final String password;

    private WebDavConfig(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    public static WebDavConfig load() {
        String url = Hawk.get(HawkConfig.WEBDAV_URL, "");
        String username = Hawk.get(HawkConfig.WEBDAV_USERNAME, "");
        String password = Hawk.get(HawkConfig.WEBDAV_PASSWORD, "");

        // Keep an independent copy so a partially unreadable Hawk store does not
        // make the user's WebDAV connection disappear after an update.
        SharedPreferences mirror = mirror();
        if (mirror != null && TextUtils.isEmpty(url)) {
            url = mirror.getString(MIRROR_URL, "");
        }
        if (mirror != null && TextUtils.isEmpty(username)) {
            username = mirror.getString(MIRROR_USERNAME, "");
        }
        if (mirror != null && TextUtils.isEmpty(password)) {
            password = mirror.getString(MIRROR_PASSWORD, "");
        }
        if (!TextUtils.isEmpty(url)) {
            Hawk.put(HawkConfig.WEBDAV_URL, url);
            Hawk.put(HawkConfig.WEBDAV_USERNAME, username == null ? "" : username);
            Hawk.put(HawkConfig.WEBDAV_PASSWORD, password == null ? "" : password);
        }
        return new WebDavConfig(url, username, password);
    }

    public static boolean save(String address, String username, String password) {
        String normalized = normalizeAddress(address);
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        String savedUsername = username == null ? "" : username.trim();
        String savedPassword = password == null ? "" : password;
        Hawk.put(HawkConfig.WEBDAV_URL, normalized);
        Hawk.put(HawkConfig.WEBDAV_USERNAME, savedUsername);
        Hawk.put(HawkConfig.WEBDAV_PASSWORD, savedPassword);
        SharedPreferences mirror = mirror();
        if (mirror != null) {
            mirror.edit()
                    .putString(MIRROR_URL, normalized)
                    .putString(MIRROR_USERNAME, savedUsername)
                    .putString(MIRROR_PASSWORD, savedPassword)
                    .apply();
        }
        return true;
    }

    private static SharedPreferences mirror() {
        App app = App.getInstance();
        return app == null ? null : app.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE);
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
        // Keep address normalization JVM-testable and independent of Android framework
        // stubs; this method is also used before any Activity is available.
        if (input == null || input.trim().isEmpty()) {
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
                // AList commonly exposes plain WebDAV on 5244, while HTTPS endpoints
                // normally use the standard TLS port.  Applying 5244 to HTTPS makes a
                // valid https://host or https://IP address connect to the wrong service.
                builder.port("https".equalsIgnoreCase(parsed.scheme())
                        ? DEFAULT_HTTPS_PORT : DEFAULT_PORT);
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
