package com.github.tvbox.osc.player;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Decorates ExoPlayer extractors so a real extractor seek can update the byte-range episode.
 *
 * <p>Progressive extractors may inspect byte zero while opening a resumed item and then issue a
 * second seek to the actual sample position. The seek callback is the only point where Exo has
 * selected that byte position; guessing it from the first DataSource read can pin the gate to
 * the file header. All other extractor operations are delegated unchanged.</p>
 */
final class RangeAwareExtractorsFactory implements ExtractorsFactory {
    interface SeekListener {
        void onExtractorSeek(long position, long timeUs);
    }

    interface SeekMapListener {
        void onSeekMap(SeekMap seekMap);
    }

    private final ExtractorsFactory delegate;
    private final SeekListener listener;
    private final SeekMapListener seekMapListener;

    RangeAwareExtractorsFactory(ExtractorsFactory delegate, SeekListener listener) {
        this(delegate, listener, null);
    }

    RangeAwareExtractorsFactory(ExtractorsFactory delegate, SeekListener listener,
                                SeekMapListener seekMapListener) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate == null");
        }
        this.delegate = delegate;
        this.listener = listener;
        this.seekMapListener = seekMapListener;
    }

    @Override
    public Extractor[] createExtractors() {
        return wrap(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri,
                                        Map<String, List<String>> responseHeaders) {
        Map<String, List<String>> safeHeaders = responseHeaders == null
                ? Collections.emptyMap() : responseHeaders;
        return wrap(delegate.createExtractors(uri, safeHeaders));
    }

    private Extractor[] wrap(@Nullable Extractor[] extractors) {
        if (extractors == null || extractors.length == 0) {
            return new Extractor[0];
        }
        Extractor[] wrapped = new Extractor[extractors.length];
        for (int i = 0; i < extractors.length; i++) {
            Extractor extractor = extractors[i];
            // BundledExtractorsAdapter uses instanceof Mp3Extractor to disable seeking for
            // streams that cannot safely seek. Keep that concrete type visible to ExoPlayer;
            // the video extractors that need the byte callback are still decorated.
            wrapped[i] = extractor == null || extractor instanceof Mp3Extractor
                    ? extractor : new SeekAwareExtractor(extractor, listener, seekMapListener);
        }
        return wrapped;
    }

    private static final class SeekAwareExtractor implements Extractor {
        private final Extractor delegate;
        private final SeekListener listener;
        private final SeekMapListener seekMapListener;

        private SeekAwareExtractor(Extractor delegate, SeekListener listener,
                                    SeekMapListener seekMapListener) {
            this.delegate = delegate;
            this.listener = listener;
            this.seekMapListener = seekMapListener;
        }

        @Override
        public boolean sniff(com.google.android.exoplayer2.extractor.ExtractorInput input)
                throws java.io.IOException {
            return delegate.sniff(input);
        }

        @Override
        public void init(com.google.android.exoplayer2.extractor.ExtractorOutput output) {
            if (seekMapListener == null) {
                delegate.init(output);
                return;
            }
            delegate.init(new ExtractorOutput() {
                @Override
                public TrackOutput track(int id, int type) {
                    return output.track(id, type);
                }

                @Override
                public void endTracks() {
                    output.endTracks();
                }

                @Override
                public void seekMap(SeekMap seekMap) {
                    output.seekMap(seekMap);
                    seekMapListener.onSeekMap(seekMap);
                }
            });
        }

        @Override
        public int read(com.google.android.exoplayer2.extractor.ExtractorInput input,
                        com.google.android.exoplayer2.extractor.PositionHolder seekPosition)
                throws java.io.IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            delegate.seek(position, timeUs);
            if (listener != null) {
                listener.onExtractorSeek(position, timeUs);
            }
        }

        @Override
        public void release() {
            delegate.release();
        }
    }
}
