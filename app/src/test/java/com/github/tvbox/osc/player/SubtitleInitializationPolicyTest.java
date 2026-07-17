package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SubtitleInitializationPolicyTest {
    @Test
    public void nativeTrackInspectionWaitsForRenderedPlayback() {
        assertTrue(SubtitleInitializationPolicy.shouldDeferUntilPlaybackReady(false));
        assertFalse(SubtitleInitializationPolicy.shouldDeferUntilPlaybackReady(true));
    }

    @Test
    public void nativeTrackDiscoveryGetsAtMostOneDelayedRetry() {
        assertTrue(SubtitleInitializationPolicy.shouldRetryNativeTrackDiscovery(0, 0));
        assertFalse(SubtitleInitializationPolicy.shouldRetryNativeTrackDiscovery(0, 1));
        assertFalse(SubtitleInitializationPolicy.shouldRetryNativeTrackDiscovery(2, 0));
    }

    @Test
    public void ordinaryTrackDiscoveryAdvancesItsFiniteAttemptCount() {
        assertEquals(1, SubtitleInitializationPolicy.nextDiscoveryAttempt(0));
        assertEquals(8, SubtitleInitializationPolicy.nextDiscoveryAttempt(7));
        assertEquals(Integer.MAX_VALUE,
                SubtitleInitializationPolicy.nextDiscoveryAttempt(Integer.MAX_VALUE));
    }
}
