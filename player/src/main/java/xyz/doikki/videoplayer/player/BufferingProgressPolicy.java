package xyz.doikki.videoplayer.player;

final class BufferingProgressPolicy {
    private BufferingProgressPolicy() {
    }

    static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    static int resolveDirectUriDisplayedPercent(boolean active,
                                                long receivedAtStart,
                                                long receivedNow,
                                                long targetBytes,
                                                int nativePercent,
                                                int previousPercent) {
        int nativeValue = clampPercent(nativePercent);
        if (!active || targetBytes <= 0L) {
            return nativeValue;
        }
        // TrafficStats is not available on a few vendor TV firmwares. A zero native
        // percentage is not evidence that the source is idle in that case; preserve a
        // known high-water mark and let the UI render an explicit unknown-progress state.
        if (receivedAtStart < 0L) {
            int knownPrevious = clampPercent(previousPercent);
            return knownPrevious > 0 || nativeValue > 0
                    ? Math.max(knownPrevious, nativeValue)
                    : -1;
        }
        if (receivedNow < receivedAtStart) {
            return nativeValue;
        }
        long received = receivedNow - receivedAtStart;
        long networkPercent = received >= targetBytes
                ? 99L
                : (received * 100L) / targetBytes;
        // Buffer completion is signalled separately. Keep an active operation below 100 so
        // the UI never claims completion while the vendor player is still seeking/preparing.
        return Math.min(99, Math.max(Math.max(0, previousPercent),
                Math.max(nativeValue, (int) networkPercent)));
    }

    static int resolveRangePrebufferDisplayedPercent(boolean active,
                                                      long bufferedBytes,
                                                      long targetBytes,
                                                      int previousPercent) {
        if (!active || targetBytes <= 0L) {
            return 0;
        }
        long safeBuffered = Math.max(0L, bufferedBytes);
        if (safeBuffered >= targetBytes) {
            return 100;
        }
        long percent = (safeBuffered * 100L) / targetBytes;
        // The byte snapshot is the source of truth. Retaining the historical maximum makes a
        // stale 99% survive a source handoff or a seek even when the new Range has received no
        // bytes, which is exactly the endless-99% state seen on the TV.
        return Math.min(99, (int) percent);
    }

    /**
     * A short file may be allowed to start after its complete remaining tail is buffered.
     * Use that tail as the progress denominator so the loading surface reaches 100% instead
     * of stopping at (tail / 96 MiB) while playback has already correctly started.
     */
    static long resolveRangeProgressTargetBytes(long configuredTargetBytes,
                                                long knownTotalBytes,
                                                long anchorBytes) {
        if (configuredTargetBytes <= 0L || knownTotalBytes <= 0L || anchorBytes < 0L) {
            return configuredTargetBytes;
        }
        long safeAnchor = Math.min(anchorBytes, knownTotalBytes);
        long remaining = knownTotalBytes - safeAnchor;
        return remaining > 0L
                ? Math.min(configuredTargetBytes, remaining)
                : configuredTargetBytes;
    }

    /**
     * The progress overlay may include the received prefix of an in-flight response, but that
     * prefix is not usable by the decoder until the response is committed to the rolling cache.
     * Keep the overlay at 99% until the committed startup gate is satisfied; otherwise the UI
     * claims completion while the player is still waiting for the same bytes to be committed.
     */
    static long capUncommittedRangeProgress(long progressBytes,
                                            long committedBytes,
                                            long targetBytes) {
        long safeProgress = Math.max(0L, progressBytes);
        long safeCommitted = Math.max(0L, committedBytes);
        if (targetBytes > 0L && safeProgress >= targetBytes
                && safeCommitted < targetBytes) {
            return Math.max(0L, targetBytes - 1L);
        }
        return safeProgress;
    }

    static boolean isRangePrebufferReady(long bufferedBytes, long targetBytes) {
        return targetBytes > 0L && Math.max(0L, bufferedBytes) >= targetBytes;
    }

    static boolean shouldHoldBufferingEnd(boolean nativeBuffering) {
        return nativeBuffering;
    }

    static long resolveFirstFrameDeadlineMs(boolean playbackStarted,
                                            long existingDeadlineMs,
                                            long nowMs,
                                            long timeoutMs) {
        if (!playbackStarted) {
            return 0L;
        }
        // Repeated callbacks must not extend a genuine decoder failure indefinitely.
        return existingDeadlineMs > 0L ? existingDeadlineMs : nowMs + timeoutMs;
    }

    static boolean shouldDeferFirstFrameFailure(boolean nativeBuffering,
                                                int armedPercent,
                                                int currentPercent,
                                                long nowMs,
                                                long absoluteDeadlineMs) {
        if (absoluteDeadlineMs <= 0L || nowMs >= absoluteDeadlineMs) {
            return false;
        }
        return nativeBuffering || clampPercent(currentPercent) > clampPercent(armedPercent);
    }
}
