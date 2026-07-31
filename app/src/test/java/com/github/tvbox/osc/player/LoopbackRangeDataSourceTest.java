package com.github.tvbox.osc.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoopbackRangeDataSourceTest {
    @Test
    public void onlyDirectSpiderVodEndpointsUseTheRangeReader() {
        assertTrue(LoopbackRangeDataSource.isLoopbackProxyPlayUrl(
                "http://127.0.0.1:6677/proxy/play/disk/movie.mkv"));
        assertTrue(LoopbackRangeDataSource.isLoopbackProxyPlayUrl(
                "http://localhost:6677/proxy/play/disk/movie.mp4?token=x"));
        assertFalse(LoopbackRangeDataSource.isLoopbackProxyPlayUrl(
                "http://127.0.0.1:9978/proxy?go=stream"));
        assertFalse(LoopbackRangeDataSource.isLoopbackProxyPlayUrl(
                "https://media.example.com/proxy/play/movie.mkv"));
    }

    @Test
    public void largeFileRangesRemainLongAndNeverWrapAtTwoGigabytes() {
        long start = 200L * 1024L * 1024L * 1024L;
        long requestSize = 8L * 1024L * 1024L;
        assertEquals(start + requestSize - 1L,
                LoopbackRangeDataSource.safeRangeEnd(start, requestSize));
        assertEquals(Long.MAX_VALUE,
                LoopbackRangeDataSource.safeRangeEnd(Long.MAX_VALUE - 2L, 8L));
    }

    @Test
    public void malformedLocalRangeHeadersAreHandledWithoutUsingTheirFalseLength() {
        assertTrue(LoopbackRangeDataSource.shouldRetryRangeReset(
                200, "bytes 0-1048575/61392789972", 1048576L));
        assertEquals(8L, LoopbackRangeDataSource.malformedRangeShift(
                "bytes 1048584-3145719/1290285458", 1048576L, 3145727L));
        assertTrue(LoopbackRangeDataSource.isExpectedMalformedHeader(
                "bytes 1048584-3145719/1290285458", 1048576L, 3145727L));
        assertFalse(LoopbackRangeDataSource.isExpectedMalformedHeader(
                "bytes 1048585-3145719/1290285458", 1048576L, 3145727L));
        assertTrue(LoopbackRangeDataSource.isAcceptableRangeHeader(
                "bytes 1048584-3145719/1290285458", 1048576L, 3145727L));
        assertTrue(LoopbackRangeDataSource.isAcceptableRangeHeader(
                "bytes 1048576-3145727/1290285458", 1048576L, 3145727L));
        assertFalse(LoopbackRangeDataSource.isAcceptableRangeHeader(
                "bytes 1048585-3145719/1290285458", 1048576L, 3145727L));
        assertEquals(0L, LoopbackRangeDataSource.malformedRangeShift(
                "bytes 1048584-3145720/1290285458", 1048576L, 3145727L));
    }

}
