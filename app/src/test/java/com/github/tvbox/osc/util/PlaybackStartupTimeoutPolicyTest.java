package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackStartupTimeoutPolicyTest {
    @Test
    public void freshPlaybackKeepsTheExistingStartupDeadline() {
        assertEquals(12_000L,
                PlaybackStartupTimeoutPolicy.resolveInitialTimeout(12_000L, false));
    }

    @Test
    public void resumedPlaybackGetsTimeForTheInitialRangeSeek() {
        assertEquals(90_000L,
                PlaybackStartupTimeoutPolicy.resolveInitialTimeout(12_000L, true));
        assertEquals(90_000L,
                PlaybackStartupTimeoutPolicy.resolveInitialTimeout(30_000L, true));
    }

    @Test
    public void existingSystemProxyAllowanceIsNeverReduced() {
        assertEquals(120_000L,
                PlaybackStartupTimeoutPolicy.resolveInitialTimeout(120_000L, true));
    }
}
