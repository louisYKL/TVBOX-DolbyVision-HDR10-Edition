package com.github.tvbox.osc.player;

import android.net.Uri;

import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class RangeAwareExtractorsFactoryTest {
    @Test
    public void wrapsBothFactoryOverloadsAndForwardsSeek() {
        AtomicLong position = new AtomicLong(-1L);
        AtomicLong timeUs = new AtomicLong(-1L);
        Extractor delegate = new NoOpExtractor();
        ExtractorsFactory factory = new ExtractorsFactory() {
            @Override
            public Extractor[] createExtractors() {
                return new Extractor[]{delegate};
            }

            @Override
            public Extractor[] createExtractors(Uri uri, Map<String, List<String>> headers) {
                return new Extractor[]{delegate};
            }
        };
        RangeAwareExtractorsFactory wrapped = new RangeAwareExtractorsFactory(
                factory, (seekPosition, seekTimeUs) -> {
                    position.set(seekPosition);
                    timeUs.set(seekTimeUs);
                });

        Extractor first = wrapped.createExtractors()[0];
        first.seek(1234L, 5678L);
        assertEquals(1234L, position.get());
        assertEquals(5678L, timeUs.get());
        assertNotNull(wrapped.createExtractors(null, Collections.emptyMap())[0]);
    }

    @Test
    public void preservesMp3ExtractorIdentityForExoSpecialHandling() {
        Mp3Extractor mp3 = new Mp3Extractor();
        RangeAwareExtractorsFactory wrapped = new RangeAwareExtractorsFactory(
                () -> new Extractor[]{mp3}, (position, timeUs) -> {
                });
        assertEquals(mp3, wrapped.createExtractors()[0]);
    }

    @Test
    public void forwardsUnseekableMapWithoutInventingAByteSeek() {
        AtomicReference<ExtractorOutput> delegateOutput = new AtomicReference<>();
        AtomicReference<SeekMap> receivedMap = new AtomicReference<>();
        AtomicReference<Boolean> seekable = new AtomicReference<>();
        AtomicLong seeks = new AtomicLong();
        Extractor delegate = new NoOpExtractor() {
            @Override
            public void init(ExtractorOutput output) {
                delegateOutput.set(output);
            }
        };
        RangeAwareExtractorsFactory factory = new RangeAwareExtractorsFactory(
                () -> new Extractor[]{delegate}, (position, timeUs) -> seeks.incrementAndGet(),
                map -> seekable.set(map.isSeekable()));
        factory.createExtractors()[0].init(new ExtractorOutput() {
            @Override
            public TrackOutput track(int id, int type) {
                return null;
            }

            @Override
            public void endTracks() {
            }

            @Override
            public void seekMap(SeekMap map) {
                receivedMap.set(map);
            }
        });
        SeekMap map = new SeekMap.Unseekable(10_000_000L);
        delegateOutput.get().seekMap(map);
        assertEquals(map, receivedMap.get());
        assertEquals(Boolean.FALSE, seekable.get());
        assertEquals(0L, seeks.get());
    }

    private static class NoOpExtractor implements Extractor {
        @Override
        public boolean sniff(ExtractorInput input) {
            return false;
        }

        @Override
        public void init(ExtractorOutput output) {
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) {
            return RESULT_END_OF_INPUT;
        }

        @Override
        public void seek(long position, long timeUs) {
        }

        @Override
        public void release() {
        }
    }
}
