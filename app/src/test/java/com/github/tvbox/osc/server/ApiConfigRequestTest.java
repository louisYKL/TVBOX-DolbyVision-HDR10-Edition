package com.github.tvbox.osc.server;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiConfigRequestTest {
    @Test
    public void readsSubscriptionAndLiveAddressesTogether() {
        Map<String, String> params = new HashMap<>();
        params.put("subscription_url", " https://example.com/tv.json ");
        params.put("live_url", " https://example.com/live.json ");

        ApiConfigRequest request = ApiConfigRequest.from(params);

        assertEquals("https://example.com/tv.json", request.subscriptionUrl);
        assertEquals("https://example.com/live.json", request.liveUrl);
        assertTrue(request.hasSubscriptionChange);
        assertTrue(request.hasLiveChange);
    }

    @Test
    public void acceptsLegacySubscriptionParameter() {
        Map<String, String> params = new HashMap<>();
        params.put("url", "https://example.com/legacy.json");

        ApiConfigRequest request = ApiConfigRequest.from(params);

        assertEquals("https://example.com/legacy.json", request.subscriptionUrl);
        assertTrue(request.hasSubscriptionChange);
        assertFalse(request.hasLiveChange);
    }

    @Test
    public void blankAddressDoesNotClearOtherConfiguration() {
        Map<String, String> params = new HashMap<>();
        params.put("subscription_url", " ");
        params.put("live_url", "https://example.com/live.json");

        ApiConfigRequest request = ApiConfigRequest.from(params);

        assertEquals("", request.subscriptionUrl);
        assertFalse(request.hasSubscriptionChange);
        assertTrue(request.hasLiveChange);
        assertTrue(request.hasAnyChange());
    }
}
