package xyz.doikki.videoplayer.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RawRangeCacheBudgetTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    public void sourcesShareOneCeilingInsteadOfEachSpendingTheWholeHeap() {
        MutableHeap heap = new MutableHeap(512L * MIB, 32L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account first = budget.openAccount();
        RawRangeCacheBudget.Account second = budget.openAccount();
        assertTrue(first.tryResize(120L * MIB, 0L));
        assertTrue(second.tryResize(48L * MIB, 24L * MIB));
        assertEquals(192L * MIB, budget.chargedBytes());
        assertFalse(second.tryResize(72L * MIB, 24L * MIB));
        assertEquals(192L * MIB, budget.chargedBytes());
        assertTrue(first.tryResize(96L * MIB, 0L));
        assertTrue(second.tryResize(72L * MIB, 24L * MIB));
    }

    @Test
    public void reservationTransferAndWarmupUseOneCharge() {
        MutableHeap heap = new MutableHeap(512L * MIB, 32L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account account = budget.openAccount();
        // A small warmup payload changes ownership without allocating a duplicate body.
        assertTrue(account.tryResize(2L * MIB, 0L));
        assertTrue(account.tryResize(2L * MIB, 24L * MIB));
        assertEquals(26L * MIB, budget.chargedBytes());
        assertEquals(24L * MIB, budget.reservedBytes());
        // The downloaded range replaces the overlapping warmup and commits atomically.
        assertTrue(account.tryResize(24L * MIB, 0L));
        assertEquals(24L * MIB, budget.chargedBytes());
        assertEquals(0L, budget.reservedBytes());
    }

    @Test
    public void closedSourceKeepsDelayedWorkerChargedUntilTheWorkerReturns() {
        MutableHeap heap = new MutableHeap(512L * MIB, 32L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account retired = budget.openAccount();
        RawRangeCacheBudget.Account replacement = budget.openAccount();
        assertTrue(retired.tryResize(96L * MIB, 24L * MIB));
        assertTrue(retired.tryResize(0L, 24L * MIB));
        assertEquals(24L * MIB, retired.chargedBytes());
        assertFalse(replacement.tryResize(192L * MIB, 0L));
        assertTrue(replacement.tryResize(96L * MIB, 0L));
        assertTrue(retired.tryResize(0L, 0L));
        assertTrue(retired.tryResize(0L, 0L));
        assertEquals("double close must not refund someone else's memory",
                96L * MIB, budget.chargedBytes());
        assertTrue(replacement.tryResize(192L * MIB, 0L));
    }

    @Test
    public void reservationsLeaveHeapSpaceForDecoderAndUiEvenBeforeTheyAllocate() {
        MutableHeap heap = new MutableHeap(512L * MIB, 384L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account first = budget.openAccount();
        RawRangeCacheBudget.Account second = budget.openAccount();
        assertTrue(first.tryResize(0L, 24L * MIB));
        assertFalse(second.tryResize(0L, 24L * MIB));
        assertEquals(24L * MIB, budget.chargedBytes());
        // A transfer is legal even if unrelated allocations increased in the meantime.
        heap.used = 480L * MIB;
        assertTrue(first.tryResize(24L * MIB, 0L));
        assertFalse(first.tryResize(24L * MIB, 24L * MIB));
        assertTrue(first.tryResize(0L, 0L));
        assertEquals(0L, budget.chargedBytes());
    }

    @Test
    public void normalStartupCanCommitFourWindowsWithoutLosingIts96MiBRunway() {
        MutableHeap heap = new MutableHeap(512L * MIB, 64L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account account = budget.openAccount();
        long committed = 0L;
        for (int window = 0; window < 4; window++) {
            assertTrue(account.tryResize(committed, 24L * MIB));
            committed += 24L * MIB;
            heap.used += 24L * MIB;
            assertTrue(account.tryResize(committed, 0L));
        }
        assertEquals(96L * MIB, account.chargedBytes());
        assertTrue(account.tryResize(96L * MIB, 24L * MIB));
    }

    @Test
    public void minimumRunwayCanFinishUnderPressureWithoutAnImpossible96MiBHeadroomGate() {
        MutableHeap heap = new MutableHeap(512L * MIB, 400L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account account = budget.openAccount();
        assertTrue(account.tryResize(72L * MIB, 0L));
        assertFalse(account.tryResize(72L * MIB, 24L * MIB));
        assertTrue("the final startup window may use the protected 48 MiB headroom",
                account.tryResize(72L * MIB, 24L * MIB, true));
        heap.used += 24L * MIB;
        assertTrue(account.tryResize(96L * MIB, 0L));
        assertEquals(96L * MIB, budget.chargedBytes());
        assertFalse("extra fill-ahead still keeps the larger normal headroom",
                account.tryResize(96L * MIB, 24L * MIB));
    }

    @Test
    public void impossibleMinimumIsReportedAfterResponseOwnersHaveReturned() {
        MutableHeap heap = new MutableHeap(512L * MIB, 400L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account account = budget.openAccount();
        assertTrue(account.tryResize(72L * MIB, 0L));
        assertTrue(account.tryResize(72L * MIB, 24L * MIB, true));
        heap.used = 480L * MIB;
        assertEquals("an in-flight owner may still return memory and is allowed to unwind",
                null, account.minimumRunwayFailure(96L * MIB));
        assertTrue(account.tryResize(72L * MIB, 0L));
        assertFalse(account.tryResize(72L * MIB, 24L * MIB, true));
        assertTrue("stale released heap or genuine retained heap cannot create an infinite gate",
                account.minimumRunwayFailure(96L * MIB) != null);
    }

    @Test
    public void anotherLiveSourceCannotLeaveTheMinimumRunwayWaitingForever() {
        MutableHeap heap = new MutableHeap(1024L * MIB, 64L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        RawRangeCacheBudget.Account paused = budget.openAccount();
        RawRangeCacheBudget.Account playing = budget.openAccount();
        assertTrue(paused.tryResize(120L * MIB, 0L));
        assertTrue(playing.tryResize(72L * MIB, 0L));
        assertFalse(playing.tryResize(72L * MIB, 24L * MIB, true));
        assertTrue(playing.minimumRunwayFailure(96L * MIB) != null);
        assertTrue(paused.tryResize(0L, 0L));
        assertTrue(playing.tryResize(72L * MIB, 24L * MIB, true));
        assertEquals(null, playing.minimumRunwayFailure(96L * MIB));
    }

    @Test
    public void parallelSourcesCannotRacePastProcessCeiling() throws Exception {
        MutableHeap heap = new MutableHeap(1024L * MIB, 32L * MIB);
        RawRangeCacheBudget budget = new RawRangeCacheBudget(192L * MIB, 96L * MIB, heap);
        List<RawRangeCacheBudget.Account> accounts = new ArrayList<>();
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(12);
        AtomicInteger admitted = new AtomicInteger();
        try {
            for (int i = 0; i < 12; i++) {
                RawRangeCacheBudget.Account account = budget.openAccount();
                accounts.add(account);
                workers.submit(() -> {
                    try {
                        start.await();
                        if (account.tryResize(0L, 24L * MIB)) {
                            admitted.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5L, TimeUnit.SECONDS));
            assertEquals(8, admitted.get());
            assertEquals(192L * MIB, budget.chargedBytes());
            for (RawRangeCacheBudget.Account account : accounts) {
                assertTrue(account.tryResize(0L, 0L));
            }
            assertEquals(0L, budget.chargedBytes());
        } finally {
            workers.shutdownNow();
        }
    }

    private static final class MutableHeap implements RawRangeCacheBudget.HeapState {
        private final long max;
        private volatile long used;

        MutableHeap(long max, long used) {
            this.max = max;
            this.used = used;
        }

        @Override public long maxBytes() { return max; }
        @Override public long usedBytes() { return used; }
    }
}
