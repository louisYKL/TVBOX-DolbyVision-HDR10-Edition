package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InitialResumeWarmupPolicyTest {
    @Test
    public void primeStartRemainsActiveUntilTargetSeekIsDispatched() {
        assertTrue(InitialResumeWarmupPolicy.isPrimeStart(true, false));
        assertFalse(InitialResumeWarmupPolicy.isPrimeStart(true, true));
        assertFalse(InitialResumeWarmupPolicy.isPrimeStart(false, false));
    }

    @Test
    public void primeCannotBypassTheOpeningPrebuffer() {
        assertFalse(InitialResumeWarmupPolicy.canStartAfterOpeningPrebuffer(false));
        assertTrue(InitialResumeWarmupPolicy.canStartAfterOpeningPrebuffer(true));
    }

    @Test
    public void primeKeepsNativeAudioTrackAtRequestedVolume() {
        assertEquals(1f, InitialResumeWarmupPolicy.nativeTrackVolume(true, 1f), 0f);
        assertEquals(0.35f, InitialResumeWarmupPolicy.nativeTrackVolume(true, 0.35f), 0f);
        assertEquals(1f, InitialResumeWarmupPolicy.nativeTrackVolume(false, 1f), 0f);
    }

    @Test
    public void nativeBufferingEndCannotExposeTheOpeningFrame() {
        assertFalse(InitialResumeWarmupPolicy.isTargetBufferComplete(true, true, false));
    }

    @Test
    public void prebufferEndBeforeResumeSeekCannotExposeTheOpeningFrame() {
        assertFalse(InitialResumeWarmupPolicy.isTargetBufferComplete(true, false, true));
    }

    @Test
    public void explicitTargetPrebufferEndCompletesWarmup() {
        assertTrue(InitialResumeWarmupPolicy.isTargetBufferComplete(true, true, true));
    }

    @Test
    public void resumePlaybackCannotSeekOrExposeVideoBeforeBothLargeBuffersAreReady() {
        boolean warmupActive = true;
        boolean resumeSeekDispatched = false;

        assertTrue(InitialResumeWarmupPolicy.isPrimeStart(
                warmupActive, resumeSeekDispatched));
        assertFalse(InitialResumeWarmupPolicy.canStartAfterOpeningPrebuffer(false));
        assertTrue(InitialResumeWarmupPolicy.canStartAfterOpeningPrebuffer(true));

        resumeSeekDispatched = true;
        assertFalse(InitialResumeWarmupPolicy.isPrimeStart(
                warmupActive, resumeSeekDispatched));
        assertFalse(InitialResumeWarmupPolicy.isTargetBufferComplete(
                warmupActive, resumeSeekDispatched, false));
        assertTrue(InitialResumeWarmupPolicy.isTargetBufferComplete(
                warmupActive, resumeSeekDispatched, true));
    }
}
