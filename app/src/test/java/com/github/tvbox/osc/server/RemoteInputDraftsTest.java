package com.github.tvbox.osc.server;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RemoteInputDraftsTest {
    @Test
    public void validatesExplicitTargetAndValueRatherThanAcceptingUnknownFields() {
        assertNull(RemoteInputRequest.from(null));
        assertNull(request("other", "https://example.com"));
        Map<String, String> params = new HashMap<>();
        params.put("target", RemoteInputRequest.CONFIG);
        assertNull(RemoteInputRequest.from(params));
        params.put("value", null);
        assertNull(RemoteInputRequest.from(params));
    }

    @Test
    public void trimsAddressesButPreservesCredentialWhitespace() {
        assertEquals("https://example.com/config.json",
                request(RemoteInputRequest.CONFIG, " https://example.com/config.json ").value);
        assertEquals("https://example.com/live.m3u",
                request(RemoteInputRequest.LIVE, " https://example.com/live.m3u ").value);
        assertEquals("192.168.0.1", request(RemoteInputRequest.WEBDAV_URL, " 192.168.0.1 ").value);
        assertEquals(" account ", request(RemoteInputRequest.WEBDAV_USERNAME, " account ").value);
        assertEquals(" pass word ", request(RemoteInputRequest.WEBDAV_PASSWORD, " pass word ").value);
    }

    @Test
    public void explicitEmptyValueClearsOnlyTheChosenInputAndAllowsAnonymousCredentials() {
        RemoteInputDrafts drafts = new RemoteInputDrafts();
        RemoteInputDrafts.PendingInput address = drafts.put(request(RemoteInputRequest.WEBDAV_URL, "192.168.0.1"));
        drafts.put(request(RemoteInputRequest.WEBDAV_USERNAME, ""));
        drafts.put(request(RemoteInputRequest.WEBDAV_PASSWORD, ""));
        drafts.put(request(RemoteInputRequest.CONFIG, ""));
        drafts.put(request(RemoteInputRequest.LIVE, ""));
        assertEquals("", drafts.take(RemoteInputRequest.WEBDAV_USERNAME).request.value);
        assertEquals("", drafts.take(RemoteInputRequest.WEBDAV_PASSWORD).request.value);
        assertEquals("", drafts.take(RemoteInputRequest.CONFIG).request.value);
        assertEquals("", drafts.take(RemoteInputRequest.LIVE).request.value);
        assertSame(address, drafts.take(RemoteInputRequest.WEBDAV_URL));
    }

    @Test
    public void openingOneInputConsumesOnlyItsDraftWithoutChangingOtherNativeFields() {
        RemoteInputDrafts drafts = new RemoteInputDrafts();
        RemoteInputDrafts.PendingInput config = drafts.put(request(RemoteInputRequest.CONFIG, "new-config"));
        RemoteInputDrafts.PendingInput live = drafts.put(request(RemoteInputRequest.LIVE, "new-live"));
        RemoteInputDrafts.PendingInput password = drafts.put(request(RemoteInputRequest.WEBDAV_PASSWORD, " secret "));
        Map<String, String> nativeInputs = new HashMap<>();
        nativeInputs.put(RemoteInputRequest.CONFIG, "old-config");
        nativeInputs.put(RemoteInputRequest.LIVE, "old-live");
        RemoteInputDrafts.PendingInput pending = drafts.take(RemoteInputRequest.CONFIG);
        nativeInputs.put(pending.request.target, pending.request.value);
        pending.markFilled();
        assertEquals("new-config", nativeInputs.get(RemoteInputRequest.CONFIG));
        assertEquals("old-live", nativeInputs.get(RemoteInputRequest.LIVE));
        assertTrue(config.awaitFilled(0));
        assertFalse(live.awaitFilled(0));
        assertFalse(password.awaitFilled(0));
        assertNull(drafts.take(RemoteInputRequest.CONFIG));
        assertSame(live, drafts.take(RemoteInputRequest.LIVE));
        assertSame(password, drafts.take(RemoteInputRequest.WEBDAV_PASSWORD));
    }

    @Test
    public void repeatedFieldInputReplacesOnlyThatQueuedDraftAndIsNotReportedFilled() {
        RemoteInputDrafts drafts = new RemoteInputDrafts();
        RemoteInputDrafts.PendingInput old = drafts.put(request(RemoteInputRequest.CONFIG, "old"));
        RemoteInputDrafts.PendingInput live = drafts.put(request(RemoteInputRequest.LIVE, "live"));
        RemoteInputDrafts.PendingInput latest = drafts.put(request(RemoteInputRequest.CONFIG, "new"));
        assertSame(latest, drafts.take(RemoteInputRequest.CONFIG));
        assertFalse(latest.awaitFilled(0));
        latest.markFilled();
        assertTrue(latest.awaitFilled(0));
        assertFalse(old.awaitFilled(0));
        assertSame(live, drafts.take(RemoteInputRequest.LIVE));
        assertNotNull(request(RemoteInputRequest.WEBDAV_PASSWORD, ""));
    }

    private static RemoteInputRequest request(String target, String value) {
        Map<String, String> params = new HashMap<>();
        params.put("target", target);
        params.put("value", value);
        return RemoteInputRequest.from(params);
    }
}
