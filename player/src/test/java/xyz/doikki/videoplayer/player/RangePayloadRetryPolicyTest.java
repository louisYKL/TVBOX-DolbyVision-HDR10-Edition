package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RangePayloadRetryPolicyTest {
    @Test
    public void foregroundTransientEmptyPayloadGetsMultipleRecoveryAttempts() {
        assertTrue(RangePayloadRetryPolicy.shouldRetry(1, 100L, false));
        assertTrue(RangePayloadRetryPolicy.shouldRetry(7, 5_000L, false));
        assertFalse(RangePayloadRetryPolicy.shouldRetry(8, 5_000L, false));
        assertFalse(RangePayloadRetryPolicy.shouldRetry(2, 20_000L, false));
    }

    @Test
    public void prefetchRecoveryIsBoundedAndDoesNotOccupyAWorkerForever() {
        assertTrue(RangePayloadRetryPolicy.shouldRetry(5, 2_000L, true));
        assertFalse(RangePayloadRetryPolicy.shouldRetry(6, 2_000L, true));
        assertFalse(RangePayloadRetryPolicy.shouldRetry(2, 8_000L, true));
    }

    @Test
    public void retryDelayBacksOffAndIsCapped() {
        assertEquals(250L, RangePayloadRetryPolicy.retryDelayMs(1));
        assertEquals(750L, RangePayloadRetryPolicy.retryDelayMs(3));
        assertEquals(1_000L, RangePayloadRetryPolicy.retryDelayMs(99));
    }
}
