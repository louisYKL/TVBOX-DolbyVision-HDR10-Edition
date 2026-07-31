package com.github.tvbox.osc.player;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.util.concurrent.atomic.AtomicLong;

/** Tracks current playback throughput without allocating on the loader thread. */
final class RealtimeNetworkSpeedMeter implements TransferListener {
    private static final long SAMPLE_INTERVAL_MS = 250L;
    // A range boundary can take a few seconds to reopen even while the source is healthy. Keep
    // the last measured rate through that short gap so the UI does not falsely show 0 B/s.
    private static final long STALE_AFTER_MS = 5_000L;
    private static final long TIME_UNSET = Long.MIN_VALUE;

    interface Clock {
        long elapsedRealtime();
    }

    @Nullable
    private final TransferListener delegate;
    private final Clock clock;
    private final AtomicLong totalNetworkBytes = new AtomicLong();
    private long sampleStartMs = TIME_UNSET;
    private long sampleStartBytes;
    private long latestBytesPerSecond;
    private long lastDataSampleAtMs = TIME_UNSET;
    private int activeNetworkTransfers;

    RealtimeNetworkSpeedMeter(@Nullable TransferListener delegate) {
        this(delegate, SystemClock::elapsedRealtime);
    }

    RealtimeNetworkSpeedMeter(@Nullable TransferListener delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public void onTransferInitializing(DataSource source,
                                       DataSpec dataSpec,
                                       boolean isNetwork) {
        if (delegate != null) {
            delegate.onTransferInitializing(source, dataSpec, isNetwork);
        }
    }

    @Override
    public void onTransferStart(DataSource source,
                                DataSpec dataSpec,
                                boolean isNetwork) {
        if (delegate != null) {
            delegate.onTransferStart(source, dataSpec, isNetwork);
        }
        if (!isNetwork) {
            return;
        }
        synchronized (this) {
            if (activeNetworkTransfers++ == 0) {
                sampleStartMs = clock.elapsedRealtime();
                sampleStartBytes = totalNetworkBytes.get();
                latestBytesPerSecond = 0L;
                lastDataSampleAtMs = TIME_UNSET;
            }
        }
    }

    @Override
    public void onBytesTransferred(DataSource source,
                                   DataSpec dataSpec,
                                   boolean isNetwork,
                                   int bytesTransferred) {
        if (delegate != null) {
            delegate.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred);
        }
        if (!isNetwork || bytesTransferred <= 0) {
            return;
        }
        totalNetworkBytes.addAndGet(bytesTransferred);
    }

    @Override
    public void onTransferEnd(DataSource source,
                              DataSpec dataSpec,
                              boolean isNetwork) {
        if (delegate != null) {
            delegate.onTransferEnd(source, dataSpec, isNetwork);
        }
        if (!isNetwork) {
            return;
        }
        synchronized (this) {
            activeNetworkTransfers = Math.max(0, activeNetworkTransfers - 1);
        }
    }

    synchronized long getBytesPerSecond() {
        long now = clock.elapsedRealtime();
        if (sampleStartMs == TIME_UNSET || now < sampleStartMs) {
            sampleStartMs = now;
            sampleStartBytes = totalNetworkBytes.get();
            latestBytesPerSecond = 0L;
            lastDataSampleAtMs = TIME_UNSET;
            return 0L;
        }
        long elapsedMs = now - sampleStartMs;
        if (elapsedMs < SAMPLE_INTERVAL_MS) {
            return latestBytesPerSecond;
        }
        long currentBytes = totalNetworkBytes.get();
        long transferredBytes = Math.max(0L, currentBytes - sampleStartBytes);
        if (transferredBytes > 0L) {
            latestBytesPerSecond = bytesPerSecond(transferredBytes, elapsedMs);
            lastDataSampleAtMs = now;
        } else if (lastDataSampleAtMs == TIME_UNSET
                || now - lastDataSampleAtMs > STALE_AFTER_MS) {
            latestBytesPerSecond = 0L;
        }
        sampleStartMs = now;
        sampleStartBytes = currentBytes;
        return latestBytesPerSecond;
    }

    private static long bytesPerSecond(long bytes, long elapsedMs) {
        if (bytes <= 0L || elapsedMs <= 0L) {
            return 0L;
        }
        if (bytes > Long.MAX_VALUE / 1_000L) {
            return Long.MAX_VALUE / elapsedMs;
        }
        return (bytes * 1_000L) / elapsedMs;
    }
}
