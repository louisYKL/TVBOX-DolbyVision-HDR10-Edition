package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Tv32MatroskaRoutePolicyTest {
    @Test
    public void hdrMetadataUsesReleasedDirectSystemUri() {
        assertTrue(Tv32MatroskaRoutePolicy.shouldUseDirectUri(true, false));
    }

    @Test
    public void startupPreflightUsesDirectSystemUriWhenHdrMarkerIsLate() {
        assertTrue(Tv32MatroskaRoutePolicy.shouldUseDirectUri(false, true));
    }

    @Test
    public void unclassifiedMatroskaKeepsRangeBackedProxyFd() {
        assertFalse(Tv32MatroskaRoutePolicy.shouldUseDirectUri(false, false));
    }

    @Test
    public void hdrStartupPreflightUsesDirectSystemUri() {
        assertTrue(Tv32MatroskaRoutePolicy.shouldUseDirectUri(true, true));
    }

    @Test
    public void rangeBackedResumeSeekRunsBeforeFirstStart() {
        assertFalse(Tv32MatroskaRoutePolicy.shouldDelayResumeSeekUntilRenderingStart(true, false));
    }

    @Test
    public void directUriResumeKeepsSeekBeforeRendering() {
        assertFalse(Tv32MatroskaRoutePolicy.shouldDelayResumeSeekUntilRenderingStart(false, true));
    }

    @Test
    public void legacyRouteAlsoAvoidsDoubleStartLifecycle() {
        assertFalse(Tv32MatroskaRoutePolicy.shouldDelayResumeSeekUntilRenderingStart(false, false));
    }

}
