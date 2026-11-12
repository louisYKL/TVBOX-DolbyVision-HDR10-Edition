package com.github.tvbox.osc.server;

import java.util.Map;

final class ApiConfigRequest {
    final String subscriptionUrl;
    final String liveUrl;
    final boolean hasSubscriptionChange;
    final boolean hasLiveChange;

    private ApiConfigRequest(String subscriptionUrl, String liveUrl) {
        this.subscriptionUrl = subscriptionUrl;
        this.liveUrl = liveUrl;
        this.hasSubscriptionChange = !subscriptionUrl.isEmpty();
        this.hasLiveChange = !liveUrl.isEmpty();
    }

    static ApiConfigRequest from(Map<String, String> params) {
        String subscriptionUrl = firstNonEmpty(params, "subscription_url", "url");
        String liveUrl = value(params, "live_url");
        return new ApiConfigRequest(subscriptionUrl, liveUrl);
    }

    boolean hasAnyChange() {
        return hasSubscriptionChange || hasLiveChange;
    }

    private static String firstNonEmpty(Map<String, String> params, String firstKey, String fallbackKey) {
        String value = value(params, firstKey);
        return value.isEmpty() ? value(params, fallbackKey) : value;
    }

    private static String value(Map<String, String> params, String key) {
        String value = params == null ? null : params.get(key);
        return value == null ? "" : value.trim();
    }
}
