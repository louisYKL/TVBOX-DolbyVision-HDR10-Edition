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
        if (!active
                || receivedAtStart < 0L
                || receivedNow < receivedAtStart
                || targetBytes <= 0L) {
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
