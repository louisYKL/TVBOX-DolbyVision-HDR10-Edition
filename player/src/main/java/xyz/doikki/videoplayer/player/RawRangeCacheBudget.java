package xyz.doikki.videoplayer.player;

/** Process-wide ownership of raw HTTP payloads, including responses still being downloaded. */
final class RawRangeCacheBudget {
    private static final long MIB = 1024L * 1024L;
    static final RawRangeCacheBudget PROCESS = new RawRangeCacheBudget(
            192L * MIB, 96L * MIB, new HeapState() {
                @Override public long maxBytes() {
                    return Runtime.getRuntime().maxMemory();
                }
                @Override public long usedBytes() {
                    Runtime runtime = Runtime.getRuntime();
                    return runtime.totalMemory() - runtime.freeMemory();
                }
            });

    interface HeapState {
        long maxBytes();
        long usedBytes();
    }

    private final long limitBytes;
    private final long heapHeadroomBytes;
    private final HeapState heap;
    private long committedBytes;
    private long reservedBytes;

    RawRangeCacheBudget(long limitBytes, long heapHeadroomBytes, HeapState heap) {
        if (limitBytes <= 0L || heapHeadroomBytes < 0L || heap == null) {
            throw new IllegalArgumentException("Invalid Range process budget");
        }
        this.limitBytes = limitBytes;
        this.heapHeadroomBytes = heapHeadroomBytes;
        this.heap = heap;
    }

    Account openAccount() {
        return new Account();
    }

    synchronized long chargedBytes() {
        return committedBytes + reservedBytes;
    }

    synchronized long reservedBytes() {
        return reservedBytes;
    }

    final class Account {
        private long committed;
        private long reserved;

        /** A reservation-to-payload transfer is atomic and never needs another allocation. */
        boolean tryResize(long newCommitted, long newReserved) {
            return tryResize(newCommitted, newReserved, false);
        }

        boolean tryResize(long newCommitted, long newReserved, boolean minimumRunway) {
            if (newCommitted < 0L || newReserved < 0L
                    || newCommitted > Long.MAX_VALUE - newReserved) {
                throw new IllegalArgumentException("Invalid Range ownership");
            }
            synchronized (RawRangeCacheBudget.this) {
                long oldOwned = committed + reserved;
                long newOwned = newCommitted + newReserved;
                long otherOwned = committedBytes + reservedBytes - oldOwned;
                if (newOwned > oldOwned) {
                    if (newOwned > limitBytes - Math.min(limitBytes, otherOwned)) {
                        return false;
                    }
                    // Existing reservations may not have allocated their arrays yet. Charging
                    // them against currently free heap is conservative even during a partial
                    // response, and prevents two sources both spending the same free space.
                    long futureReservations = reservedBytes - reserved + newReserved;
                    long maxHeap = heap.maxBytes();
                    long usedHeap = Math.max(0L, heap.usedBytes());
                    long freeHeap = maxHeap > usedHeap ? maxHeap - usedHeap : 0L;
                    long headroom = minimumRunway
                            ? Math.min(heapHeadroomBytes / 2L, 48L * MIB) : heapHeadroomBytes;
                    if (maxHeap > 0L && (freeHeap < headroom
                            || futureReservations > freeHeap - headroom)) {
                        return false;
                    }
                }
                committedBytes += newCommitted - committed;
                reservedBytes += newReserved - reserved;
                committed = newCommitted;
                reserved = newReserved;
                RawRangeCacheBudget.this.notifyAll();
                return true;
            }
        }

        long chargedBytes() {
            synchronized (RawRangeCacheBudget.this) {
                return committed + reserved;
            }
        }

        /** Null means that active response owners may still return memory; retry admission. */
        String minimumRunwayFailure(long minimumOwnedBytes) {
            synchronized (RawRangeCacheBudget.this) {
                if (reservedBytes > 0L) {
                    return null;
                }
                long otherCommitted = committedBytes - committed;
                if (minimumOwnedBytes > limitBytes - Math.min(limitBytes, otherCommitted)) {
                    return "Range process cache is occupied by another playback source";
                }
                long maxHeap = heap.maxBytes();
                long nonRawHeap = Math.max(0L, heap.usedBytes() - committedBytes);
                long headroom = Math.min(heapHeadroomBytes / 2L, 48L * MIB);
                long available = maxHeap - Math.min(maxHeap, nonRawHeap) - headroom;
                if (maxHeap > 0L && (available < otherCommitted
                        || minimumOwnedBytes > available - otherCommitted)) {
                    return "Insufficient Java heap for the playback runway and decoder headroom";
                }
                return null;
            }
        }
    }
}
