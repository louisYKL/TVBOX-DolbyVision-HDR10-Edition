package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Tv32LocalProxyRoutePolicyTest {
    @Test
    public void tv32LocalVodKeepsProxyPrebufferingPipeline() {
        assertFalse(Tv32LocalProxyRoutePolicy.shouldUseDirectSystemUri(true, true, false));
    }

    @Test
    public void hlsAndOtherPlatformsKeepTheirExistingSourcePath() {
        assertFalse(Tv32LocalProxyRoutePolicy.shouldUseDirectSystemUri(true, true, true));
        assertFalse(Tv32LocalProxyRoutePolicy.shouldUseDirectSystemUri(false, true, false));
        assertFalse(Tv32LocalProxyRoutePolicy.shouldUseDirectSystemUri(true, false, false));
    }

}
