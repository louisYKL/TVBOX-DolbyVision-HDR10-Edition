package com.github.tvbox.osc.player;

import org.junit.Test;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.SeekPoint;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoopbackRangeDataSourceTest {
    @Test
    public void seekMapResolvesInitialResumeAndBothDirectionsEvenWithoutAnotherExtractorRead()
            throws Exception {
        LoopbackRangeDataSource.Factory factory = new LoopbackRangeDataSource.Factory(
                () -> null, "test", 100, 100);
        AtomicLong requestedUs = new AtomicLong(-1L);
        SeekMap seekMap = new SeekMap() {
            public boolean isSeekable() { return true; }
            public long getDurationUs() { return 10_000_000_000L; }
            public SeekPoints getSeekPoints(long timeUs) {
                requestedUs.set(timeUs);
                return new SeekPoints(new SeekPoint(timeUs, timeUs * 500L + 1024L));
            }
        };
        try {
            factory.setInitialResumePosition(100_000L);
            factory.onExtractorSeekStarted(0L, 0L);
            assertEquals(-1L, longField(factory, "resolvedSeekByte"));
            factory.onSeekMapResolved(seekMap);
            assertEquals(100_000_000L, requestedUs.get());
            assertEquals(50_000_001_024L, longField(factory, "resolvedSeekByte"));
            factory.onPlaybackSeekStarted(200_000L);
            assertEquals(100_000_001_024L, longField(factory, "resolvedSeekByte"));
            factory.onPlaybackSeekStarted(50_000L);
            assertEquals(25_000_001_024L, longField(factory, "resolvedSeekByte"));
            factory.onPlaybackSeekStarted(0L);
            assertEquals(1024L, longField(factory, "resolvedSeekByte"));
        } finally {
            factory.release();
        }
    }

    private static long longField(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(object);
    }

    @Test
    public void resolvedAudioOnlyMp3DoesNotWaitForAMissingExtractorSeekCallback() {
        LoopbackRangeDataSource.Factory factory = new LoopbackRangeDataSource.Factory(
                () -> null, "test", 100, 100);
        factory.setInitialResumePosition(5_000L);
        assertTrue(factory.isPlaybackSeekPending());
        factory.onMp3OnlySourceResolved();
        assertFalse(factory.isPlaybackSeekPending());
        factory.release();
    }

    @Test
    public void resumedHeaderProbeDoesNotResolvePlaybackAnchor() {
        assertFalse(LoopbackRangeDataSource.Factory.isPlaybackAnchorResolved(true, 0L, 0L));
        assertTrue(LoopbackRangeDataSource.Factory.isPlaybackAnchorResolved(
                true, 585061073L, 74326000L));
        // Some containers seek back to the first keyframe for an early resume point.
        assertTrue(LoopbackRangeDataSource.Factory.isPlaybackAnchorResolved(true, 0L, 100000L));
        assertTrue(LoopbackRangeDataSource.Factory.isPlaybackAnchorResolved(false, 0L, 0L));
    }

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
    public void remoteHttpVodUsesRangeButLiveAndLocalStreamsKeepTheirOwnPath() {
        assertTrue(LoopbackRangeDataSource.shouldHandleVodUrl(
                "https://media.example.com/movie.mkv?token=abc"));
        assertTrue(LoopbackRangeDataSource.shouldHandleVodUrl(
                "https://dav.example.com/dav/film.mp4"));
        assertTrue(LoopbackRangeDataSource.shouldHandleVodUrl(
                "http://127.0.0.1:6677/proxy/play/disk/movie.mkv"));
        assertFalse(LoopbackRangeDataSource.shouldHandleVodUrl(
                "https://media.example.com/channel/index.m3u8"));
        assertFalse(LoopbackRangeDataSource.shouldHandleVodUrl(
                "http://live.example.com/playlist?id=1&type=m3u8"));
        assertFalse(LoopbackRangeDataSource.shouldHandleVodUrl(
                "http://127.0.0.1:9978/proxy?go=stream&url=https%3A%2F%2Fmedia.example.com%2Fmovie.mkv"));
        assertFalse(LoopbackRangeDataSource.shouldHandleVodUrl(
                "https://media.example.com/live.mpd"));
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

    @Test
    public void eofReadinessUsesRemainingBytesFromPlaybackAnchor() {
        assertTrue(LoopbackRangeDataSource.isWholeFileBuffered(100L, 90L, 10L));
        assertFalse(LoopbackRangeDataSource.isWholeFileBuffered(100L, 90L, 9L));
        assertFalse(LoopbackRangeDataSource.isWholeFileBuffered(100L, -1L, 100L));
        assertFalse(LoopbackRangeDataSource.isWholeFileBuffered(100L, 100L, 0L));
    }

    @Test
    public void retainedSnapshotNeverReleasesGateForReplacementSource() {
        long required = 72L * 1024L * 1024L;
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromRetainedSnapshot(
                false, required, required));
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromRetainedSnapshot(
                false, required + 1L, required));
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromRetainedSnapshot(
                false, required - 1L, required));
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromRetainedSnapshot(
                true, required, required));
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromRetainedSnapshot(
                false, required, 0L));
    }

    @Test
    public void unanchoredStartupLaneCanReleaseOnlyBeforeASeek() {
        long required = 72L * 1024L * 1024L;
        assertTrue(LoopbackRangeDataSource.Factory.shouldReleaseFromUnanchoredStartup(
                false, required, required, required));
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromUnanchoredStartup(
                false, required - 1L, required, required));
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromUnanchoredStartup(
                true, required, required, required));
        assertFalse(LoopbackRangeDataSource.Factory.shouldReleaseFromUnanchoredStartup(
                false, required, 0L, required));
    }

    @Test
    public void anchoredReplacementRemainsAuthoritativeEvenWhenTemporarilyEmpty() {
        assertFalse(LoopbackRangeDataSource.Factory.shouldUseUnanchoredStartup(
                true, 0L, true, 0L));
        assertTrue(LoopbackRangeDataSource.Factory.shouldUseUnanchoredStartup(
                false, 0L, true, 0L));
        assertFalse(LoopbackRangeDataSource.Factory.shouldUseUnanchoredStartup(
                false, 4L * 1024L * 1024L, true, 0L));
        assertTrue(LoopbackRangeDataSource.Factory.shouldUseUnanchoredStartup(
                false, -1L, true, 0L));
    }

}
