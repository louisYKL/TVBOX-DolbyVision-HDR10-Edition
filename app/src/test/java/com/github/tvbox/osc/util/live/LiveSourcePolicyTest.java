package com.github.tvbox.osc.util.live;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LiveSourcePolicyTest {
    @Test public void savedIndependentSourceWinsOverStaleVodAndWorksWithoutVod() {
        assertEquals("https://new.test/live.json", LiveSourcePolicy.resolveUrl(
                "https://old.test/config", " https://new.test/live.json "));
        assertEquals("https://new.test/playlist", LiveSourcePolicy.resolveUrl(
                "", "https://new.test/playlist"));
    }

    @Test public void emptyIndependentSourceUsesSubscriptionWithoutRequiringParsedGroups() {
        assertEquals("https://vod.test/config", LiveSourcePolicy.resolveUrl(
                " https://vod.test/config ", "  "));
        assertEquals("", LiveSourcePolicy.resolveUrl(null, null));
    }
}
