package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InternalLogPolicyTest {
    @Test
    public void totalDiagnosticStorageNeverExceeds512Kilobytes() {
        assertEquals(2, InternalLogPolicy.POLICY_VERSION);
        assertEquals(512L * 1024L, InternalLogPolicy.MAX_TOTAL_BYTES);
        assertEquals(256L * 1024L, InternalLogPolicy.MAX_FILE_BYTES);
    }

    @Test
    public void rotatesBeforeActiveGenerationExceedsItsBudget() {
        assertFalse(InternalLogPolicy.shouldRotate(0L, 1L));
        assertFalse(InternalLogPolicy.shouldRotate(InternalLogPolicy.MAX_FILE_BYTES - 1L, 1L));
        assertTrue(InternalLogPolicy.shouldRotate(InternalLogPolicy.MAX_FILE_BYTES, 1L));
    }

    @Test
    public void neverAppendsPastTheActiveGenerationBudget() {
        assertTrue(InternalLogPolicy.fitsInGeneration(0L, InternalLogPolicy.MAX_FILE_BYTES));
        assertTrue(InternalLogPolicy.fitsInGeneration(InternalLogPolicy.MAX_FILE_BYTES - 1L, 1L));
        assertFalse(InternalLogPolicy.fitsInGeneration(InternalLogPolicy.MAX_FILE_BYTES, 1L));
        assertFalse(InternalLogPolicy.fitsInGeneration(InternalLogPolicy.MAX_FILE_BYTES + 1L, 0L));
    }
}
