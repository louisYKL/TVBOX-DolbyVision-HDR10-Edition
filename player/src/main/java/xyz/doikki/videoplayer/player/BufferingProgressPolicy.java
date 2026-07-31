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
        long percent = safeBuffered >= targetBytes
                ? 99L
                : (safeBuffered * 100L) / targetBytes;
        return Math.min(99, Math.max(clampPercent(previousPercent), (int) percent));
    }

    static boolean isRangePrebufferReady(long bufferedBytes, long targetBytes) {
        return targetBytes > 0L && Math.max(0L, bufferedBytes) >= targetBytes;
    }

    static boolean shouldHoldBufferingEnd(boolean nativeBuffering) {
        return nativeBuffering;
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
