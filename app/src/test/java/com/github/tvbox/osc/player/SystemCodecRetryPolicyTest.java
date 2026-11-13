package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemCodecRetryPolicyTest {
    @Test
    public void transientSourceErrorsHaveAThreeAttemptBudget() {
        int count = 0;
        count = SystemCodecRetryPolicy.nextRetryAttempt(count, 2004);
        assertEquals(1, count);
        count = SystemCodecRetryPolicy.nextRetryAttempt(count, 2004);
        assertEquals(2, count);
        count = SystemCodecRetryPolicy.nextRetryAttempt(count, 2004);
        assertEquals(3, count);
        assertEquals(-1, SystemCodecRetryPolicy.nextRetryAttempt(count, 2004));
    }

    @Test
    public void firstFrameDoesNotChangeTheRetryBudget() {
        int count = 2;

        // The policy is state-only; rendering a frame has no reset operation. The next source
        // error therefore consumes the final attempt instead of starting an infinite 1/3 loop.
        int next = SystemCodecRetryPolicy.nextRetryAttempt(count, 2004);

        assertEquals(3, next);
        assertEquals(-1, SystemCodecRetryPolicy.nextRetryAttempt(next, 2004));
    }

    @Test
    public void nonTransientAndInvalidCountsDoNotRetry() {
        assertFalse(SystemCodecRetryPolicy.isTransientSourceError(1001));
        assertFalse(SystemCodecRetryPolicy.isTransientSourceError(3004));
        assertEquals(-1, SystemCodecRetryPolicy.nextRetryAttempt(0, 1001));
        assertEquals(-1, SystemCodecRetryPolicy.nextRetryAttempt(-1, 2004));
        assertTrue(SystemCodecRetryPolicy.isTransientSourceError(2004));
    }

    @Test
    public void retryKeepsSavedResumeWhenCurrentPositionIsNotAvailableYet() {
        assertEquals(18_000L, SystemCodecRetryPolicy.resolveRetryResumePosition(
                0L, false, -1L, 18_000L));
        assertEquals(24_000L, SystemCodecRetryPolicy.resolveRetryResumePosition(
                0L, true, 24_000L, 18_000L));
        assertEquals(0L, SystemCodecRetryPolicy.resolveRetryResumePosition(
                0L, true, 0L, 18_000L));
        assertEquals(31_000L, SystemCodecRetryPolicy.resolveRetryResumePosition(
                31_000L, false, 24_000L, 18_000L));
        assertEquals(24_000L, SystemCodecRetryPolicy.resolveRetryResumePosition(
                31_000L, true, 24_000L, 18_000L));
    }

    @Test
    public void retryDoesNotInventAResumePositionForFreshPlayback() {
        assertEquals(0L, SystemCodecRetryPolicy.resolveRetryResumePosition(
                0L, false, -1L, 0L));
    }
}
