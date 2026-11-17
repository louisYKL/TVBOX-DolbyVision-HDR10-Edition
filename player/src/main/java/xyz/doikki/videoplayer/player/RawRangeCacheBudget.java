package xyz.doikki.videoplayer.player;

import java.util.ArrayList;
import java.util.List;

/** Process-wide ownership of raw HTTP payloads, including responses still being downloaded. */
final class RawRangeCacheBudget {
    private static final long MIB = 1024L * 1024L;
    private static final long PROCESS_MAX_HEAP = Runtime.getRuntime().maxMemory();
    private static final boolean PROCESS_IS_32_BIT = detect32BitProcess();
    static final RawRangeCacheBudget PROCESS = new RawRangeCacheBudget(
            PlaybackPrebufferPolicy.resolveCacheLimitBytes(PROCESS_MAX_HEAP, PROCESS_IS_32_BIT),
            PlaybackPrebufferPolicy.resolveHeapHeadroomBytes(PROCESS_MAX_HEAP, PROCESS_IS_32_BIT),
            new HeapState() {
                @Override public long maxBytes() {
                    return Runtime.getRuntime().maxMemory();
                }
                @Override public long usedBytes() {
                    Runtime runtime = Runtime.getRuntime();
                    return runtime.totalMemory() - runtime.freeMemory();
                }
            });

    private static boolean detect32BitProcess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                return !android.os.Process.is64Bit();
            }
            String abi = android.os.Build.CPU_ABI == null ? "" : android.os.Build.CPU_ABI;
            return !abi.contains("64");
        } catch (Throwable ignored) {
            return true;
        }
    }

    interface HeapState {
        long maxBytes();
        long usedBytes();
    }

    private final long limitBytes;
    private final long heapHeadroomBytes;
    private final HeapState heap;
    private final List<Account> accounts = new ArrayList<>();
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

    synchronized Account openAccount() {
        Account account = new Account();
        accounts.add(account);
        return account;
    }

    synchronized long chargedBytes() {
        return committedBytes + reservedBytes;
    }

    synchronized long reservedBytes() {
        return reservedBytes;
    }

    private long activeCommittedBytesExcluding(Account excluded) {
        long active = 0L;
        for (Account account : accounts) {
            if (account != excluded && account.active) {
                active += account.committed;
            }
        }
        return active;
    }

    final class Account {
        private long committed;
        private long reserved;
        private boolean active = true;

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
                // A closed source may still be returning an in-flight response. Allow its
                // worker to shrink its reservation, but never let it acquire new ownership.
                if (!active && newCommitted + newReserved > committed + reserved) {
                    return false;
                }
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

        /** Drop payload ownership immediately while keeping live response reservations charged. */
        void releaseCommitted() {
            tryResize(0L, reserved);
        }

        /** Mark this source closed while retaining physical reservations until workers unwind. */
        void deactivate() {
            synchronized (RawRangeCacheBudget.this) {
                active = false;
                accounts.remove(this);
                RawRangeCacheBudget.this.notifyAll();
            }
        }

        /** Null means that active response owners may still return memory; retry admission. */
        String minimumRunwayFailure(long minimumOwnedBytes) {
            synchronized (RawRangeCacheBudget.this) {
                if (reservedBytes > 0L) {
                    return null;
                }
                // A source that has already closed is not a competing playback owner. Its
                // arrays may still be physically resident, so normal admission still waits for
                // them to be released; it must not be converted into a permanent cache error.
                long activeOtherCommitted = activeCommittedBytesExcluding(this);
                if (minimumOwnedBytes > limitBytes - Math.min(limitBytes, activeOtherCommitted)) {
                    return "Range process cache is occupied by another playback source";
                }
                long maxHeap = heap.maxBytes();
                long nonRawHeap = Math.max(0L, heap.usedBytes() - committedBytes);
                long headroom = Math.min(heapHeadroomBytes / 2L, 48L * MIB);
                long available = maxHeap - Math.min(maxHeap, nonRawHeap) - headroom;
                long physicalOtherCommitted = committedBytes - committed;
                if (maxHeap > 0L && (available < physicalOtherCommitted
                        || minimumOwnedBytes > available - physicalOtherCommitted)) {
                    return "Insufficient Java heap for the playback runway and decoder headroom";
                }
                return null;
            }
        }
    }
}
