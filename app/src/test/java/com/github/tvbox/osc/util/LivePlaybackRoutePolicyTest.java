package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LivePlaybackRoutePolicyTest {
    @Test
    public void systemLivePlayerKeepsSystemUrlRouteOnJava64() {
        assertFalse(LivePlaybackRoutePolicy.shouldUseCompatUrl(PlayerHelper.PLAYER_TYPE_SYSTEM));
    }

    @Test
    public void compatLivePlayerUsesCompatUrlRoute() {
        assertTrue(LivePlaybackRoutePolicy.shouldUseCompatUrl(
                PlayerHelper.PLAYER_TYPE_DOLBY_VISION_COMPAT));
    }
}
