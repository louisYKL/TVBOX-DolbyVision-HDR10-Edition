package com.github.tvbox.osc.server;

import java.util.Map;

/** An explicit edit of one input field, never a request to save or activate settings. */
public final class RemoteInputRequest {
    public static final String CONFIG = "config";
    public static final String LIVE = "live";
    public static final String WEBDAV_URL = "webdav_url";
    public static final String WEBDAV_USERNAME = "webdav_username";
    public static final String WEBDAV_PASSWORD = "webdav_password";
    private static final int MAX_VALUE_LENGTH = 64 * 1024;

    public final String target;
    public final String value;

    private RemoteInputRequest(String target, String value) {
        this.target = target;
        this.value = value;
    }

    public static RemoteInputRequest from(Map<String, String> params) {
        if (params == null || !params.containsKey("value")) {
            return null;
        }
        String target = params.get("target");
        String value = params.get("value");
        if (!isSupportedTarget(target) || value == null || value.length() > MAX_VALUE_LENGTH) {
            return null;
        }
        boolean address = CONFIG.equals(target) || LIVE.equals(target) || WEBDAV_URL.equals(target);
        return new RemoteInputRequest(target, address ? value.trim() : value);
    }

    private static boolean isSupportedTarget(String target) {
        return CONFIG.equals(target) || LIVE.equals(target) || WEBDAV_URL.equals(target)
                || WEBDAV_USERNAME.equals(target) || WEBDAV_PASSWORD.equals(target);
    }
}
