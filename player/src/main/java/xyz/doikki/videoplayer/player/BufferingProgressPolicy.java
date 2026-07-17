package xyz.doikki.videoplayer.player;

final class BufferingProgressPolicy {
    private BufferingProgressPolicy() {
    }

    static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    static int resolveDisplayedPercent(boolean prebufferActive,
                                       long bufferedBytes,
                                       long targetBytes,
                                       int nativePercent) {
        if (!prebufferActive || targetBytes <= 0L) {
            return clampPercent(nativePercent);
        }
        if (bufferedBytes <= 0L) {
            return 0;
        }
        long percent = bufferedBytes >= targetBytes
                ? 100L
                : (bufferedBytes * 100L) / targetBytes;
        return clampPercent((int) percent);
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

    static boolean shouldHoldBufferingEnd(boolean nativeBuffering,
                                          boolean prebufferActive,
                                          boolean preparedResumeActive) {
        return nativeBuffering || prebufferActive || preparedResumeActive;
    }

    static boolean shouldDeferFirstFrameFailure(boolean nativeBuffering,
                                                int armedPercent,
                                                int currentPercent) {
        return nativeBuffering || clampPercent(currentPercent) > clampPercent(armedPercent);
    }
}
