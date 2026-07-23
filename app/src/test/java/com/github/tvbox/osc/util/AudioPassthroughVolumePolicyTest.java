package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioPassthroughVolumePolicyTest {
    @Test
    public void onlyThePassthroughSettingLocksVolumeAtMaximum() {
        assertTrue(AudioPassthroughVolumePolicy.shouldForceMaximum(true));
        assertFalse(AudioPassthroughVolumePolicy.shouldForceMaximum(false));
    }
}
