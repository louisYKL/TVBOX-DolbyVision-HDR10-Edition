package xyz.doikki.videoplayer.player;

final class InitialResumeWarmupPolicy {
    private InitialResumeWarmupPolicy() {
    }

    static boolean isPrimeStart(boolean active, boolean targetDispatched) {
        return active && !targetDispatched;
    }

    static boolean canStartAfterOpeningPrebuffer(boolean openingPrebufferReady) {
        return openingPrebufferReady;
    }

    static float nativeTrackVolume(boolean active, float requestedVolume) {
        // Huawei's vendor AudioTrack can latch silence if it is created at zero volume.
        // The black video cover and immediate pause/seek hide the prime instead.
        return requestedVolume;
    }

    static boolean isTargetBufferComplete(boolean active,
                                          boolean resumeSeekApplied,
                                          boolean explicitPrebufferEnd) {
        return active && resumeSeekApplied && explicitPrebufferEnd;
    }
}
