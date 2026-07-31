package xyz.doikki.videoplayer.player;

final class RangeWindowPolicy {
    private RangeWindowPolicy() {
    }

    static Window resolveForegroundWindow(long position,
                                          int requestedSize,
                                          long blockSize,
                                          long maxWindowSize) {
        long safePosition = Math.max(0L, position);
        long safeBlockSize = Math.max(1L, blockSize);
        long start = safePosition - (safePosition % safeBlockSize);
        long requiredSpan = safePosition - start + Math.max(1, requestedSize);
        long size = Math.min(Math.max(safeBlockSize, maxWindowSize),
                Math.max(safeBlockSize, requiredSpan));
        return new Window(start, size);
    }

    static boolean shouldYieldPrefetchToForeground(boolean prefetchActive) {
        // A cache miss is decoder-critical. Yield even when it falls inside the background
        // range so the source never opens two overlapping HTTP transfers at once.
        return prefetchActive;
    }

    static final class Window {
        final long start;
        final long size;

        Window(long start, long size) {
            this.start = start;
            this.size = size;
        }
    }
}
