package xyz.doikki.videoplayer.player;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class PlaybackWarmupCacheTest {
    @After
    public void clearCache() {
        PlaybackWarmupCache.clearForTests();
    }

    @Test
    public void warmupIsOneShotAndMatchesNormalizedHeaders() {
        Map<String, String> offeredHeaders = new HashMap<>();
        offeredHeaders.put("User-Agent", "TVBox");
        offeredHeaders.put("Range", "bytes=0-99");
        offeredHeaders.put("X-TVBox-Probe-Hdr10", "1");
        PlaybackWarmupCache.offer("http://127.0.0.1/video", offeredHeaders,
                new byte[]{1, 2, 3}, 1000L);

        Map<String, String> consumedHeaders = new HashMap<>();
        consumedHeaders.put("user-agent", "TVBox");
        PlaybackWarmupCache.Entry entry = PlaybackWarmupCache.take(
                "http://127.0.0.1/video", consumedHeaders);

        assertNotNull(entry);
        assertArrayEquals(new byte[]{1, 2, 3}, entry.data);
        assertEquals(1000L, entry.totalSize);
        assertNull(PlaybackWarmupCache.take("http://127.0.0.1/video", consumedHeaders));
    }

    @Test
    public void warmupMemoryIsCapped() {
        byte[] large = new byte[700 * 1024];
        Arrays.fill(large, (byte) 7);
        PlaybackWarmupCache.offer("http://127.0.0.1/large", null, large, large.length);

        PlaybackWarmupCache.Entry entry = PlaybackWarmupCache.take(
                "http://127.0.0.1/large", null);

        assertNotNull(entry);
        assertEquals(512 * 1024, entry.data.length);
    }
}
