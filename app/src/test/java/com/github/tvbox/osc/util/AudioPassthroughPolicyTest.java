package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioPassthroughPolicyTest {
    @Test
    public void eac3RequiresEac3Capability() {
        assertTrue(AudioPassthroughPolicy.supports(
                AudioPassthroughPolicy.E_AC3,
                AudioPassthroughPolicy.E_AC3));
        assertFalse(AudioPassthroughPolicy.supports(
                AudioPassthroughPolicy.E_AC3,
                AudioPassthroughPolicy.AC3));
    }

    @Test
    public void trueHdRequiresTrueHdCapability() {
        assertFalse(AudioPassthroughPolicy.supports(
                AudioPassthroughPolicy.TRUE_HD,
                AudioPassthroughPolicy.AC3 | AudioPassthroughPolicy.E_AC3));
        assertTrue(AudioPassthroughPolicy.supports(
                AudioPassthroughPolicy.TRUE_HD,
                AudioPassthroughPolicy.TRUE_HD));
    }

    @Test
    public void eac3AtmosRequiresJocCapability() {
        assertFalse(AudioPassthroughPolicy.supports(
                AudioPassthroughPolicy.E_AC3 | AudioPassthroughPolicy.E_AC3_JOC,
                AudioPassthroughPolicy.E_AC3));
        assertTrue(AudioPassthroughPolicy.supports(
                AudioPassthroughPolicy.E_AC3 | AudioPassthroughPolicy.E_AC3_JOC,
                AudioPassthroughPolicy.E_AC3 | AudioPassthroughPolicy.E_AC3_JOC));
    }

    @Test
    public void everyRequiredCodecMustBeSupported() {
        int required = AudioPassthroughPolicy.AC3 | AudioPassthroughPolicy.DTS;
        assertFalse(AudioPassthroughPolicy.supports(required, AudioPassthroughPolicy.AC3));
        assertTrue(AudioPassthroughPolicy.supports(required, required));
        assertFalse(AudioPassthroughPolicy.supports(0, required));
    }
}
