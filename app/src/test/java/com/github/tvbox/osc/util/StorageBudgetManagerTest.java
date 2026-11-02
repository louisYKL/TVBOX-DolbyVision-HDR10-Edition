package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StorageBudgetManagerTest {
    @Test
    public void cacheBudgetIsExactly64MiB() {
        assertEquals(64L * 1024L * 1024L, StorageBudgetManager.MAX_CACHE_BYTES);
        assertTrue(StorageBudgetManager.MAX_CACHE_BYTES <= 64L * 1024L * 1024L);
    }
}
