package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RealtimeNetworkSpeedMeterTest {
    @Test
    public void reportsCurrentNetworkThroughputAndRefreshesEveryWindow() {
        FakeClock clock = new FakeClock();
        RealtimeNetworkSpeedMeter meter = new RealtimeNetworkSpeedMeter(null, clock);

        meter.onTransferStart(null, null, true);
        meter.onBytesTransferred(null, null, true, 512 * 1024);
        clock.advance(500L);
        meter.onBytesTransferred(null, null, true, 512 * 1024);

        assertEquals(2L * 1024L * 1024L, meter.getBytesPerSecond());

        clock.advance(250L);
        meter.onBytesTransferred(null, null, true, 256 * 1024);

        assertEquals(1024L * 1024L, meter.getBytesPerSecond());
    }

    @Test
    public void clearsAStaleReadingWhenTransferStops() {
        FakeClock clock = new FakeClock();
        RealtimeNetworkSpeedMeter meter = new RealtimeNetworkSpeedMeter(null, clock);

        meter.onTransferStart(null, null, true);
        meter.onBytesTransferred(null, null, true, 256 * 1024);
        clock.advance(250L);
        meter.onBytesTransferred(null, null, true, 256 * 1024);
        assertEquals(2L * 1024L * 1024L, meter.getBytesPerSecond());

        clock.advance(5_001L);

        assertEquals(0L, meter.getBytesPerSecond());
    }

    @Test
    public void ignoresNonNetworkTransfers() {
        FakeClock clock = new FakeClock();
        RealtimeNetworkSpeedMeter meter = new RealtimeNetworkSpeedMeter(null, clock);

        meter.onTransferStart(null, null, false);
        meter.onBytesTransferred(null, null, false, 1024 * 1024);
        clock.advance(1_000L);

        assertEquals(0L, meter.getBytesPerSecond());
    }

    private static final class FakeClock implements RealtimeNetworkSpeedMeter.Clock {
        private long nowMs;

        @Override
        public long elapsedRealtime() {
            return nowMs;
        }

        void advance(long elapsedMs) {
            nowMs += elapsedMs;
        }
    }
}
