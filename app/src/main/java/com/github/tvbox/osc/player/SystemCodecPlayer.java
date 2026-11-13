package com.github.tvbox.osc.player;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlaybackUrlNormalizer;
import com.github.tvbox.osc.util.SystemDecoderRoutePolicy;
import com.github.tvbox.osc.util.VideoColorMetadata;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary;
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.ColorInfo;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * Kodi-style Android pipeline: one ExoPlayer clock/demuxer, device MediaCodec video and audio,
 * FFmpeg audio fallback, and stereo PCM AudioTrack output.
 */
public final class SystemCodecPlayer extends AbstractPlayer implements CompatTrackSelectorPlayer {
    private static final String TAG = "SystemCodecPlayer";
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Subtitle lookup runs on its own HandlerThread. ExoPlayer getters are confined to
    // the application looper, so expose a small clock snapshot instead of querying Exo
    // from that worker (which throws and used to stop external subtitle refresh).
    private volatile long subtitleClockPositionMs;
    private volatile long subtitleClockDurationMs;
    private final Runnable subtitleClockPoll = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) {
                return;
            }
            refreshSubtitleClock();
            mainHandler.postDelayed(this, 100L);
        }
    };
    private final LinkedHashMap<Integer, TrackSelectionTarget> audioTargets = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, TrackSelectionTarget> subtitleTargets = new LinkedHashMap<>();

    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private DefaultHttpDataSource.Factory httpFactory;
    private LoopbackRangeDataSource.Factory loopbackRangeFactory;
    private DefaultBandwidthMeter bandwidthMeter;
    private RealtimeNetworkSpeedMeter networkSpeedMeter;
    private DefaultAllocator allocator;
    private SystemCodecLoadControl loadControl;
    private volatile boolean currentSourceRangeBacked;
    private Surface surface;
    private SurfaceHolder surfaceHolder;
    private Surface boundSurface;
    private String dataSource;
    private Map<String, String> requestHeaders = Collections.emptyMap();
    // Probe headers are internal (removed before network requests) but carry reliable dynamic
    // range evidence into vendor MediaCodec implementations that drop ExoPlayer ColorInfo.
    private boolean sourceHdr10Hint;
    private boolean sourceHdr10PlusHint;
    private boolean sourceDolbyVisionHint;
    private volatile VideoColorMetadata sourceColorMetadata = VideoColorMetadata.EMPTY;
    private boolean nativeDolbyVisionCodecActive;
    private boolean videoCodecInitialized;
    private boolean prepared;
    // Release can be requested from a Surface callback while ExoPlayer is dispatching a
    // playback callback. Volatile visibility prevents a stale callback from touching a torn
    // down player and turning a normal Activity transition into a crash.
    private volatile boolean released;
    private boolean firstFrameRendered;
    private boolean renderingStartNotified;
    private boolean selectedVideoTrackPresent;
    private boolean selectedAudioTrackPresent;
    private boolean bufferingNotified;
    private boolean startRequested;
    private long lastPerformanceErrorAtMs;
    private long droppedVideoFrames;
    private long audioUnderruns;
    private long lastBufferStallAhead = -1L;
    private long lastBufferStallProgressAt;
    private long lastBufferStallStallErrorAt;
    private final ArrayDeque<BufferEventSnapshot> recentBufferEvents = new ArrayDeque<>();
    private long lastRebufferErrorAt;
    private int rebufferEpisodes;
    private static final long BUFFER_STALL_POLL_MS = 250L;
    private static final long BUFFER_STALL_ERROR_MS = 8_000L;
    private final Runnable bufferStallPoll = new Runnable() {
        @Override
        public void run() {
            checkBufferStall();
        }
    };
    private long firstFramePositionMs = C.TIME_UNSET;
    private long lastObservedPositionMs = C.TIME_UNSET;
    private int renderingStartPollCount;
    private static final long RENDERING_START_POLL_MS = 250L;
    private static final int MAX_RENDERING_START_POLLS = 240;
    private boolean passthroughVolumeLocked;
    private long pendingInitialSeekMs = C.TIME_UNSET;
    private long pendingSeekCompleteMs = C.TIME_UNSET;
    // ExoPlayer can report position 0 until the first frame even when the source was opened at
    // a saved position. Keep that target for a transient source retry so the replacement range
    // starts at the same point instead of silently restarting from byte/time zero.
    private long initialResumePositionMs = C.TIME_UNSET;
    private int seekGeneration;
    private int bufferProgressTargetBytes;
    private volatile int bufferingStartAllocatedBytes;
    private volatile long bufferingStartTransferredBytes;
    // LoadControl runs on ExoPlayer's playback thread, while Player getters are main-thread
    // confined. Keep the end-of-media decision as a main-thread snapshot.
    private final AtomicBoolean bufferedToEndSnapshot = new AtomicBoolean(false);
    // Transient source errors (HttpDataSourceException etc.) from the slow local network-disk
    // proxy (6677 quark/baidu spider) are retried in-place a few times before giving up, so a
    // momentary proxy stall/reconnect does not turn into a permanent black screen. Retry resumes
    // from the last known position.
    private int sourceRetryCount;
    private static final long SOURCE_RETRY_BACKOFF_MS = 1_000L;
    // This bounds inactivity without limiting the total download time. The app-level buffering
    // watchdog remains the terminal guard, while a continuously slow/large file is allowed to
    // stream for as long as bytes keep arriving.
    private static final int HTTP_CONNECT_TIMEOUT_MS = 20_000;
    // This is an inactivity timeout for one HTTP body read, not a total playback timeout. A
    // stalled WebDAV/proxy connection is retried, while a slow but continuously transferring
    // large file may keep playing for as long as it needs.
    private static final int HTTP_READ_TIMEOUT_MS = 30_000;
    private static final String HTTP_USER_AGENT = "TVBox-SystemCodec/0.2.7";
    private TrackInfo lastTrackInfo = new TrackInfo();
    private SubtitleTextListener subtitleTextListener;
    private BitmapSubtitleCueListener bitmapSubtitleCueListener;
    private RuntimeVideoModeListener runtimeVideoModeListener;
    private boolean lastRuntimeHdr;
    private boolean lastRuntimeDolbyVision;
    private boolean runtimeVideoFormatSeen;
    private boolean runtimeVideoModeDispatched;
    private boolean lastDispatchedRuntimeHdr;
    private boolean lastDispatchedRuntimeDolbyVision;
    private String lastRuntimeOutputMode = "sdr";
    private String lastRuntimeReason = "init";
    private String playbackErrorMessage;

    public SystemCodecPlayer(Context context) {
        appContext = context.getApplicationContext();
    }

    @Override
    public void initPlayer() {
        released = false;
        resetBoundSurfaceState();
        resetPlaybackFlags();

        boolean ffmpegAvailable = FfmpegLibrary.isAvailable();
        log("echo-system-codec ffmpeg available=" + ffmpegAvailable
                + " version=" + (ffmpegAvailable ? FfmpegLibrary.getVersion() : "unavailable"));

        trackSelector = new DefaultTrackSelector(appContext);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setTunnelingEnabled(false)
                .build());

        bandwidthMeter = new DefaultBandwidthMeter.Builder(appContext)
                .setResetOnNetworkTypeChange(true)
                .build();
        networkSpeedMeter = new RealtimeNetworkSpeedMeter(bandwidthMeter);
        httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(HTTP_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
                .setDefaultRequestProperties(requestHeaders);
        loopbackRangeFactory = new LoopbackRangeDataSource.Factory(
                httpFactory,
                HTTP_USER_AGENT,
                HTTP_CONNECT_TIMEOUT_MS,
                HTTP_READ_TIMEOUT_MS);
        loopbackRangeFactory.setDefaultRequestHeaders(requestHeaders);
        // In this ExoPlayer version the two-argument constructor uses the supplied factory for
        // HTTP/HTTPS as well as the other supported schemes. Keep the Range adapter as the base
        // factory so VOD/WebDAV requests use the contiguous cache; the one-argument builder API
        // is not available in the bundled 2.19.1 classes.
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(appContext,
                loopbackRangeFactory).setTransferListener(networkSpeedMeter);
        final LoopbackRangeDataSource.Factory rangeFactory = loopbackRangeFactory;
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(
                dataSourceFactory,
                new RangeAwareExtractorsFactory(new DefaultExtractorsFactory(), (position, timeUs) -> {
                    bufferedToEndSnapshot.set(false);
                    rangeFactory.onExtractorSeekStarted(position, timeUs);
                    mainHandler.post(() -> {
                        if (loopbackRangeFactory == rangeFactory) {
                            recordBufferEvent("extractor-seek byte=" + position + " timeUs=" + timeUs);
                            checkBufferStall();
                        }
                    });
                }, rangeFactory::onSeekMapResolved));

        ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClassMb = activityManager == null ? 256 : activityManager.getMemoryClass();
        int targetBufferBytes = SystemCodecBufferPolicy.targetBufferBytes(memoryClassMb);
        allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        bufferProgressTargetBytes = SystemCodecBufferPolicy.bufferProgressTargetBytes(memoryClassMb);
        loadControl = new SystemCodecLoadControl(
                allocator,
                SystemCodecBufferPolicy.MIN_BUFFER_MS,
                SystemCodecBufferPolicy.MAX_BUFFER_MS,
                SystemCodecBufferPolicy.MIN_BUFFER_BYTES,
                targetBufferBytes,
                true,
                requiredBytes -> {
                    // Allocator bytes are segment capacity, not verified media bytes. The
                    // session's contiguous Range runway is the startup/seek byte evidence.
                    return currentSourceRangeBacked
                            && loopbackRangeFactory != null
                            && (loopbackRangeFactory.hasRequiredBuffer(requiredBytes)
                            || loopbackRangeFactory.hasWholeFileBuffered());
                },
                () -> bufferedToEndSnapshot.get());

        SystemCodecRenderersFactory renderersFactory = new SystemCodecRenderersFactory(
                appContext, this);
        player = new SimpleExoPlayer.Builder(appContext, renderersFactory)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .setLooper(Looper.getMainLooper())
                // PREVIOUS_SYNC (not CLOSEST_SYNC): resuming to a saved position seeks to the
                // keyframe at/before the target instead of the nearest one. On a network-drive
                // proxy serving a huge REMUX, this needs less forward reading from a random byte
                // offset, so the resume seek recovers faster instead of stalling at low buffer.
                .setSeekParameters(SeekParameters.PREVIOUS_SYNC)
                .setReleaseTimeoutMs(750L)
                .setDetachSurfaceTimeoutMs(750L)
                .build();
        mainHandler.removeCallbacks(subtitleClockPoll);
        mainHandler.post(subtitleClockPoll);
        player.setAudioAttributes(new com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), false);
        player.setHandleAudioBecomingNoisy(false);
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        player.setVolume(1.0f);
        attachPlayerListeners();
        bindSurface();
        log("echo-system-codec init memoryClassMb=" + memoryClassMb
                + " targetBuffer=" + (targetBufferBytes == C.LENGTH_UNSET
                ? "track-default" : (targetBufferBytes / (1024 * 1024)) + "MB")
                + " progressTargetMb=" + (bufferProgressTargetBytes / (1024 * 1024)));
    }

    private void attachPlayerListeners() {
        if (player == null) {
            return;
        }
        player.addListener(new Player.Listener() {
            @Override
            public void onTimelineChanged(Timeline timeline, int reason) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post(() -> onTimelineChanged(timeline, reason));
                    return;
                }
                if (loadControl != null) {
                    // Progressive HTTP/WebDAV VOD can expose a temporary dynamic timeline
                    // while its length/index is still being resolved. The URL policy is the
                    // authoritative live-vs-VOD decision for our byte-gated Range source;
                    // otherwise the initial playWhenReady=false gate can be left paused
                    // forever after this callback.
                    boolean timelineDynamic = isLiveOrDynamicTimeline(timeline);
                    boolean liveOrDynamic = isLiveOrDynamicSourceUrl(dataSource)
                            || (timelineDynamic && !currentSourceRangeBacked);
                    loadControl.setLiveOrDynamicSource(liveOrDynamic);
                    if (startRequested) {
                        if (liveOrDynamic) {
                            mainHandler.removeCallbacks(bufferStallPoll);
                        } else {
                            scheduleBufferStallPoll();
                        }
                    }
                }
                updateBufferedToEndSnapshot();
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post(() -> onIsLoadingChanged(isLoading));
                    return;
                }
                updateBufferedToEndSnapshot();
                recordBufferEvent("loading=" + isLoading);
                if (startRequested) {
                    checkBufferStall();
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                // Output intent is applied by applyPlayIntent using the same byte evidence as
                // LoadControl. Never derive readiness from this asynchronous notification.
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post(() -> onPlaybackStateChanged(state));
                    return;
                }
                logPlaybackState("state", state);
                updateBufferedToEndSnapshot();
                handlePlaybackStateChanged(state);
                // Vendor Exo builds can enter READY/BUFFERING while the byte-gated source is
                // being handed off. Re-evaluate on the callback itself so a delayed or removed
                // poll cannot leave a validly buffered source paused at 100%.
                if (startRequested) {
                    checkBufferStall();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post(() -> onIsPlayingChanged(isPlaying));
                    return;
                }
                updateBufferedToEndSnapshot();
                logPlaybackState("is-playing=" + isPlaying,
                        player == null ? Player.STATE_IDLE : player.getPlaybackState());
                if (!released && isPlaying
                        && (!isByteGatedVod() || loadControl.hasRequiredBufferNow())) {
                    if (isByteGatedVod() && loopbackRangeFactory != null) {
                        loopbackRangeFactory.onPlaybackOutputResumed();
                    }
                    maybeNotifyRenderingStart("is-playing");
                    if (renderingStartNotified) {
                        notifyBufferingEnd();
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    // Some vendor ExoPlayer builds dispatch this callback from the playback
                    // thread despite the main Looper supplied to the builder. Error recovery
                    // reads position/state and must therefore never run on that thread.
                    mainHandler.post(() -> onPlayerError(error));
                    return;
                }
                if (released || playbackErrorMessage != null) {
                    return;
                }
                if (handlePlaybackCacheError()) {
                    return;
                }
                log("echo-system-codec error code=" + error.errorCode
                        + " message=" + error.getMessage()
                        + " cause=" + describeThrowable(error.getCause()));
                // Keep the full cause chain in the app log. Vendor ExoPlayer wraps renderer and
                // thread-affinity failures in PlaybackException; the short cause summary alone
                // is not enough to identify which callback accessed the Player.
                log("echo-system-codec error-stack=" + Log.getStackTraceString(error));
                if (tryRetryTransientSourceError(error)) {
                    return;
                }
                notifyBufferingEnd();
                notifyError();
            }

            @Override
            public void onVideoSizeChanged(com.google.android.exoplayer2.video.VideoSize videoSize) {
                notifyVideoSizeChanged(videoSize.width, videoSize.height);
            }

            @Override
            public void onRenderedFirstFrame() {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post(this::onRenderedFirstFrame);
                    return;
                }
                firstFrameRendered = true;
                firstFramePositionMs = player == null ? C.TIME_UNSET : player.getCurrentPosition();
                lastObservedPositionMs = firstFramePositionMs;
                // Do not reset sourceRetryCount here: a first frame does not prove that later
                // range requests will succeed, and resetting it would allow an endless retry loop.
                log("echo-system-codec first-frame surface=" + describeBoundSurface());
                dispatchRuntimeVideoModeIfNeeded("first-frame");
                maybeNotifyRenderingStart("first-frame");
                scheduleRenderingStartPoll();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    mainHandler.post(() -> onTracksChanged(tracks));
                    return;
                }
                rebuildTrackInfo(tracks);
                maybeNotifyRenderingStart("tracks-changed");
            }

            @Override
            public void onCues(CueGroup cueGroup) {
                dispatchSubtitleText(cueGroup == null ? null : cueGroup.cues);
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition,
                                                int reason) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK || pendingSeekCompleteMs == C.TIME_UNSET) {
                    return;
                }
                final int generation = seekGeneration;
                mainHandler.postDelayed(() -> completePendingSeekIfReady(generation), 50L);
            }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames,
                                              long elapsedMs) {
                runOnMain(() -> {
                    droppedVideoFrames += Math.max(0, droppedFrames);
                    reportPlaybackPerformanceError("dropped-video-frames",
                            " dropped=" + droppedFrames + " elapsedMs=" + elapsedMs);
                });
            }

            @Override
            public void onAudioUnderrun(EventTime eventTime, int bufferSize,
                                         long bufferSizeMs, long elapsedSinceLastFeedMs) {
                runOnMain(() -> {
                    audioUnderruns++;
                    reportPlaybackPerformanceError("audio-underrun",
                            " audioBufferBytes=" + bufferSize + " audioBufferMs=" + bufferSizeMs
                                    + " sinceLastFeedMs=" + elapsedSinceLastFeedMs);
                });
            }

            @Override
            public void onVideoDecoderInitialized(EventTime eventTime,
                                                  String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                log("echo-system-codec video-decoder=" + decoderName
                        + " initMs=" + initializationDurationMs);
            }

            @Override
            public void onAudioDecoderInitialized(EventTime eventTime,
                                                  String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                log("echo-system-codec audio-decoder=" + decoderName
                        + " initMs=" + initializationDurationMs);
            }
        });
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        PlaybackUrlNormalizer.UrlWithHeaders parsed = PlaybackUrlNormalizer.splitUrlAndHeaders(path, headers);
        dataSource = PlaybackUrlNormalizer.normalizeHttpUrl(parsed.url);
        currentSourceRangeBacked = LoopbackRangeDataSource.shouldHandleVodUrl(dataSource);
        if (loopbackRangeFactory != null) {
            loopbackRangeFactory.resetPlaybackBufferSnapshot();
        }
        passthroughVolumeLocked = hasHeaderValue(parsed.headers, "X-TVBox-Probe-AudioPassthrough", "1");
        sourceHdr10Hint = hasHeaderValue(parsed.headers, "X-TVBox-Probe-Hdr10", "1");
        sourceHdr10PlusHint = hasHeaderValue(parsed.headers, "X-TVBox-Probe-Hdr10Plus", "1");
        sourceDolbyVisionHint = hasHeaderValue(parsed.headers, "X-TVBox-Probe-DolbyVision", "1");
        sourceColorMetadata = VideoColorMetadata.fromHeaders(parsed.headers);
        requestHeaders = sanitizeRequestHeaders(parsed.headers);
        if (networkSpeedMeter != null) {
            networkSpeedMeter.resetRate();
        }
        // A new source gets a fresh transient-error budget. Retry prepares do not call
        // setDataSource, so they keep the same bounded budget for this source session.
        sourceRetryCount = 0;
        initialResumePositionMs = C.TIME_UNSET;
        resetPlaybackFlags();
        enforceExternalPcmVolume();
        log("echo-system-codec source network=" + isNetworkSource(dataSource)
                + " headers=" + requestHeaders.size()
                + " passthroughVolume=" + passthroughVolumeLocked
                + " hdrHint=" + sourceHdr10Hint
                + " hdr10PlusHint=" + sourceHdr10PlusHint
                + " dvHint=" + sourceDolbyVisionHint);
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        throw new UnsupportedOperationException("SystemCodecPlayer does not support AssetFileDescriptor");
    }

    @Override
    public void start() {
        if (player == null || released) {
            return;
        }
        startRequested = true;
        applyPlayIntent();
        scheduleBufferStallPoll();
    }

    @Override
    public void pause() {
        startRequested = false;
        mainHandler.removeCallbacks(bufferStallPoll);
        if (player != null && !released) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    public void stop() {
        if (player != null && !released) {
            player.stop();
        }
        resetPlaybackFlags();
    }

    @Override
    public void prepareAsync() {
        if (player == null || released || TextUtils.isEmpty(dataSource)) {
            notifyError();
            return;
        }
        // FFmpeg is an audio extension only. A device with no bundled extension must still be
        // allowed to start its native MediaCodec video/audio route; rejecting preparation here
        // incorrectly turned otherwise hardware-playable streams into an immediate black screen.
        if (!FfmpegLibrary.isAvailable()) {
            log("echo-system-codec ffmpeg-unavailable platform-codec-only");
        }
        try {
            long initialPositionMs = pendingInitialSeekMs;
            resetPlaybackFlags();
            pendingInitialSeekMs = initialPositionMs;
            if (initialPositionMs > 0L) {
                initialResumePositionMs = initialPositionMs;
            }
            startRequested = true;
            configureSourceBufferPolicy();
            httpFactory.setDefaultRequestProperties(requestHeaders);
            loopbackRangeFactory.setDefaultRequestHeaders(requestHeaders);
            applyPlayIntent();
            player.clearMediaItems();
            MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(Uri.parse(dataSource));
            String mimeType = resolveMediaItemMimeType(dataSource);
            if (!TextUtils.isEmpty(mimeType)) {
                mediaItemBuilder.setMimeType(mimeType);
            }
            pendingInitialSeekMs = C.TIME_UNSET;
            loopbackRangeFactory.setInitialResumePosition(Math.max(0L, initialPositionMs));
            if (initialPositionMs > 0L) {
                // ExoPlayer creates the Range DataSource after setMediaItem(), and may replace
                // it while probing a resumed stream. Mark the factory first so every replacement
                // treats its first decoder read as the new playback anchor.
                player.setMediaItem(mediaItemBuilder.build(), initialPositionMs);
            } else {
                player.setMediaItem(mediaItemBuilder.build());
            }
            player.prepare();
            if (isByteGatedVod()) {
                scheduleBufferStallPoll();
            }
            notifyBufferingStart();
            log("echo-system-codec prepare mime=" + (TextUtils.isEmpty(mimeType) ? "auto" : mimeType)
                    + " initialPositionMs=" + Math.max(0L, initialPositionMs));
        } catch (Throwable throwable) {
            log("echo-system-codec prepare-failed " + describeThrowable(throwable));
            notifyError();
        }
    }

    @Override
    public void reset() {
        if (player != null && !released) {
            player.setPlayWhenReady(false);
            player.stop();
            player.clearMediaItems();
        }
        resetPlaybackFlags();
    }

    @Override
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public boolean isStartPending() {
        SimpleExoPlayer current = player;
        return current != null && !released && startRequested
                && (current.getPlaybackState() == Player.STATE_BUFFERING
                || (isByteGatedVod() && !loadControl.hasRequiredBufferNow()));
    }

    @Override
    public void seekTo(long time) {
        if (player == null || released) {
            return;
        }
        long safeTime = Math.max(0L, time);
        if (!prepared) {
            pendingInitialSeekMs = safeTime;
            return;
        }
        if (safeTime == player.getCurrentPosition()) {
            // Exo can acknowledge an unchanged position without touching the source. There
            // is no new byte episode to wait for in that case.
            notifySeekComplete(safeTime);
            return;
        }
        pendingSeekCompleteMs = safeTime;
        int generation = ++seekGeneration;
        if (loopbackRangeFactory != null) {
            bufferedToEndSnapshot.set(false);
            loopbackRangeFactory.onPlaybackSeekStarted(safeTime);
        }
        // A seek starts a new HTTP Range episode. Reset the meter so the overlay reports the
        // current seek transfer instead of a stale pre-seek sample (or a zero left by a quiet
        // interval between two Range requests).
        if (networkSpeedMeter != null) {
            networkSpeedMeter.resetRate();
        }
        if (isByteGatedVod()) {
            applyPlayIntent();
            scheduleBufferStallPoll();
        }
        resetBufferingReadinessBaseline();
        notifyBufferingStart();
        player.seekTo(safeTime);
        refreshSubtitleClock();
        mainHandler.postDelayed(() -> completePendingSeekIfReady(generation), 75L);
    }

    @Override
    public boolean setInitialPosition(long time) {
        if (player == null || released || prepared || time <= 0L) {
            return false;
        }
        pendingInitialSeekMs = Math.max(0L, time);
        return true;
    }

    @Override
    public synchronized void release() {
        // Surface callbacks and error recovery can race with Activity teardown on vendor
        // firmware. Make release idempotent and never let a codec teardown exception escape
        // into the UI thread (which otherwise appears as a random return to the home screen).
        if (released && player == null) {
            return;
        }
        released = true;
        mainHandler.removeCallbacks(bufferStallPoll);
        mainHandler.removeCallbacks(subtitleClockPoll);
        if (loopbackRangeFactory != null) {
            try {
                // Wake a loader waiting on HTTP before waiting for Exo's release message.
                // The reverse order needlessly hits Exo's release timeout on source switches
                // and lets the old download/cache overlap the replacement player.
                loopbackRangeFactory.release();
            } catch (Throwable throwable) {
                log("echo-system-codec range-release-failed " + describeThrowable(throwable));
            }
        }
        SimpleExoPlayer playerToRelease = player;
        if (playerToRelease != null) {
            try {
                clearBoundVideoOutput();
            } catch (Throwable ignored) {
            }
            try {
                playerToRelease.release();
            } catch (Throwable throwable) {
                log("echo-system-codec release-failed " + describeThrowable(throwable));
            }
        }
        player = null;
        trackSelector = null;
        httpFactory = null;
        loopbackRangeFactory = null;
        bandwidthMeter = null;
        networkSpeedMeter = null;
        allocator = null;
        surface = null;
        surfaceHolder = null;
        resetBoundSurfaceState();
        audioTargets.clear();
        subtitleTargets.clear();
    }

    @Override
    public long getCurrentPosition() {
        if (released) {
            return 0L;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshSubtitleClock();
        }
        return subtitleClockPositionMs;
    }

    @Override
    public long getDuration() {
        if (released) {
            return 0L;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshSubtitleClock();
        }
        return subtitleClockDurationMs;
    }

    private void refreshSubtitleClock() {
        SimpleExoPlayer current = player;
        if (current == null || released) {
            subtitleClockPositionMs = 0L;
            subtitleClockDurationMs = 0L;
            return;
        }
        subtitleClockPositionMs = Math.max(0L, current.getCurrentPosition());
        long duration = current.getDuration();
        subtitleClockDurationMs = duration == C.TIME_UNSET ? 0L : Math.max(0L, duration);
    }

    @Override
    public int getBufferedPercentage() {
        SimpleExoPlayer current = player;
        if (current == null) {
            return 0;
        }
        if (isByteGatedVod()) {
            // Display arriving contiguous bytes; only committed bytes can release LoadControl.
            // The media session owns this cache across header/index/sample DataSource reopens.
            long rangeCacheAheadBytes = loopbackRangeFactory.getBufferedAheadBytesForProgress();
            // Verified bytes may already have moved into the sample queues. Keep that episode's
            // byte proof when the extractor advances, rather than displaying a second 0-100 fill.
            boolean bufferReady = loadControl != null && loadControl.hasRequiredBufferNow();
            if (bufferReady) {
                rangeCacheAheadBytes = Math.max(rangeCacheAheadBytes, bufferProgressTargetBytes);
            }
            boolean ready = bufferReady && (current.getPlaybackState() == Player.STATE_READY
                    || current.getPlaybackState() == Player.STATE_ENDED);
            long sourceTarget = loopbackRangeFactory.getPlaybackStartBufferTargetBytes();
            long progressTarget = sourceTarget > 0L
                    ? Math.min(bufferProgressTargetBytes, sourceTarget) : bufferProgressTargetBytes;
            int rangePercent = SystemCodecBufferPolicy.bufferingProgressPercent(
                    0L, 0L, rangeCacheAheadBytes, progressTarget, ready);
            if (current.getPlaybackState() == Player.STATE_BUFFERING
                    || rangePercent > 0
                    || current.getPlaybackState() == Player.STATE_READY) {
                return rangePercent;
            }
            return 0;
        }
        return Math.max(0, Math.min(100, current.getBufferedPercentage()));
    }

    @Override
    public void setSurface(Surface surface) {
        this.surface = surface;
        surfaceHolder = null;
        bindSurface();
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        surfaceHolder = holder;
        surface = holder == null ? null : holder.getSurface();
        bindSurface();
    }

    /** Detaches only the holder that actually owns the current codec output. */
    public void detachDisplay(SurfaceHolder destroyedHolder) {
        if (destroyedHolder == null) {
            setDisplay(null);
            return;
        }
        if (!SystemCodecSurfacePolicy.isSameHolder(surfaceHolder, destroyedHolder)) {
            log("echo-system-codec surface-ignore-destroy reason=stale-holder current="
                    + describeBoundSurface());
            return;
        }
        surfaceHolder = null;
        surface = null;
        clearBoundVideoOutput();
    }

    @Override
    public void setVolume(float v1, float v2) {
        if (player != null) {
            player.setVolume(1.0f);
        }
        enforceExternalPcmVolume();
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (player != null) {
            player.setRepeatMode(isLooping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
    }

    @Override
    public void setOptions() {
    }

    @Override
    public void setSpeed(float speed) {
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    @Override
    public float getSpeed() {
        return player == null ? 1f : player.getPlaybackParameters().speed;
    }

    @Override
    public long getTcpSpeed() {
        RealtimeNetworkSpeedMeter meter = networkSpeedMeter;
        return meter == null ? 0L : meter.getBytesPerSecond();
    }

    @Override
    public boolean supportsTrackSelection() {
        return true;
    }

    @Override
    public boolean hasValidDataSource() {
        return !TextUtils.isEmpty(dataSource);
    }

    @Override
    public TrackInfo getTrackInfo() {
        return lastTrackInfo;
    }

    @Override
    public void selectAudioTrack(@Nullable TrackInfoBean track) {
        applyTrackSelection(track, C.TRACK_TYPE_AUDIO, audioTargets, false);
    }

    @Override
    public void selectSubtitleTrack(@Nullable TrackInfoBean track) {
        applyTrackSelection(track, C.TRACK_TYPE_TEXT, subtitleTargets, true);
    }

    @Override
    public void clearSubtitleTrackSelection() {
        if (trackSelector == null) {
            return;
        }
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT);
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        trackSelector.setParameters(builder.build());
        dispatchSubtitleText(Collections.emptyList());
        rebuildTrackInfo(player == null ? null : player.getCurrentTracks());
    }

    @Override
    public boolean shouldDelaySubtitleSelection(int attempt) {
        return shouldDelaySubtitleSelection(0, attempt);
    }

    @Override
    public boolean shouldDelaySubtitleSelection(int currentSubtitleCount, int attempt) {
        return (!prepared || currentSubtitleCount <= 0) && attempt < 8;
    }

    @Override
    public void setOnSubtitleTextListener(@Nullable SubtitleTextListener listener) {
        subtitleTextListener = listener;
    }

    @Override
    public void setOnBitmapSubtitleCueListener(@Nullable BitmapSubtitleCueListener listener) {
        bitmapSubtitleCueListener = listener;
    }

    @Override
    public void setOnRuntimeVideoModeListener(@Nullable RuntimeVideoModeListener listener) {
        runtimeVideoModeListener = listener;
        dispatchRuntimeVideoModeIfNeeded("listener-bind");
    }

    private void handlePlaybackStateChanged(int state) {
        if (released) {
            return;
        }
        if (state == Player.STATE_BUFFERING) {
            recordBufferEvent("state-buffering");
            if (renderingStartNotified && pendingSeekCompleteMs == C.TIME_UNSET) {
                reportRebufferError();
                if (isByteGatedVod()) {
                    bufferedToEndSnapshot.set(false);
                    loopbackRangeFactory.onPlaybackRebufferStarted();
                }
            }
            notifyBufferingStart();
            return;
        }
        if (state == Player.STATE_READY) {
            recordBufferEvent("state-ready");
            if (!prepared) {
                prepared = true;
                notifyPrepared();
            } else {
                completePendingSeekIfReady(seekGeneration);
            }
            maybeNotifyRenderingStart("state-ready");
            if (renderingStartNotified && player != null && player.getPlayWhenReady()
                    && (player.isPlaying() || hasObservedPositionAdvance(player))) {
                notifyBufferingEnd();
            }
            dispatchRuntimeVideoModeIfNeeded("state-ready");
            return;
        }
        if (state == Player.STATE_ENDED) {
            notifyBufferingEnd();
            notifyCompletion();
        }
    }

    private void completePendingSeekIfReady(int generation) {
        if (released || generation != seekGeneration || pendingSeekCompleteMs == C.TIME_UNSET
                || player == null || player.getPlaybackState() != Player.STATE_READY
                || (isByteGatedVod() && !loadControl.hasRequiredBufferNow())) {
            return;
        }
        long completedPosition = Math.max(0L, player.getCurrentPosition());
        pendingSeekCompleteMs = C.TIME_UNSET;
        // A completed user seek supersedes the original saved resume point, including a seek
        // back to exactly 0 ms. Keep a positive target for a possible retry before the native
        // clock has advanced enough to expose it through getCurrentPosition().
        initialResumePositionMs = completedPosition > 0L ? completedPosition : C.TIME_UNSET;
        notifySeekComplete(completedPosition);
        if (player.getPlayWhenReady()
                && (player.isPlaying() || hasObservedPositionAdvance(player))) {
            notifyBufferingEnd();
        }
    }

    private void applyTrackSelection(@Nullable TrackInfoBean track,
                                     int trackType,
                                     LinkedHashMap<Integer, TrackSelectionTarget> targets,
                                     boolean disableWhenNull) {
        if (trackSelector == null) {
            return;
        }
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        builder.clearOverridesOfType(trackType);
        builder.setTrackTypeDisabled(trackType, false);
        if (track != null) {
            TrackSelectionTarget target = targets.get(track.trackId);
            if (target != null) {
                builder.addOverride(new TrackSelectionOverride(target.group, target.trackIndex));
            }
        } else if (disableWhenNull) {
            builder.setTrackTypeDisabled(trackType, true);
        }
        trackSelector.setParameters(builder.build());
        rebuildTrackInfo(player == null ? null : player.getCurrentTracks());
    }

    private void bindSurface() {
        if (player == null) {
            return;
        }
        if (surfaceHolder != null) {
            Surface holderSurface = surfaceHolder.getSurface();
            if (holderSurface != null && holderSurface.isValid()) {
                if (SystemCodecSurfacePolicy.isSameDirectSurface(boundSurface, holderSurface)) {
                    return;
                }
                // SurfaceRenderView is the sole SurfaceHolder.Callback owner. Passing the holder
                // to ExoPlayer registers a second callback chain that can clear or replace the
                // codec output after our render view has already rebound it on affected TVs.
                player.setVideoSurface(holderSurface);
                rememberBoundSurface(holderSurface);
                log("echo-system-codec surface-bind source=holder " + describeBoundSurface());
                return;
            }
        }
        if (surface != null && surface.isValid()) {
            if (SystemCodecSurfacePolicy.isSameDirectSurface(boundSurface, surface)) {
                return;
            }
            player.setVideoSurface(surface);
            rememberBoundSurface(surface);
            log("echo-system-codec surface-bind source=direct " + describeBoundSurface());
            return;
        }
        clearBoundVideoOutput();
    }

    private void rememberBoundSurface(Surface nextSurface) {
        boundSurface = nextSurface;
    }

    private void clearBoundVideoOutput() {
        if (player == null) {
            resetBoundSurfaceState();
            return;
        }
        try {
            if (boundSurface != null) {
                Surface oldSurface = boundSurface;
                try {
                    player.clearVideoSurface(oldSurface);
                    log("echo-system-codec surface-clear id="
                            + System.identityHashCode(oldSurface) + ",valid=" + oldSurface.isValid());
                } catch (Throwable throwable) {
                    // A vendor Surface may become invalid between the callback and this call.
                    // The Java reference is still cleared below; do not crash the playback loop.
                    log("echo-system-codec surface-clear-failed id="
                            + System.identityHashCode(oldSurface) + " error="
                            + describeThrowable(throwable));
                }
            }
        } finally {
            resetBoundSurfaceState();
        }
    }

    private void resetBoundSurfaceState() {
        boundSurface = null;
    }

    private String describeBoundSurface() {
        return boundSurface == null
                ? "none"
                : "id=" + System.identityHashCode(boundSurface) + ",valid=" + boundSurface.isValid();
    }

    private void logPlaybackState(String reason, int state) {
        SimpleExoPlayer current = player;
        if (current == null) {
            log("echo-system-codec playback reason=" + reason + " state=" + stateName(state)
                    + " player=null");
            return;
        }
        log("echo-system-codec playback reason=" + reason
                + " state=" + stateName(state)
                + " playWhenReady=" + current.getPlayWhenReady()
                + " loading=" + current.isLoading()
                + " positionMs=" + Math.max(0L, current.getCurrentPosition())
                + " bufferedMs=" + Math.max(0L, current.getBufferedPosition())
                + " totalBufferedMs=" + Math.max(0L, current.getTotalBufferedDuration())
                + " surface=" + describeBoundSurface());
    }

    private static String stateName(int state) {
        if (state == Player.STATE_IDLE) {
            return "idle";
        }
        if (state == Player.STATE_BUFFERING) {
            return "buffering";
        }
        if (state == Player.STATE_READY) {
            return "ready";
        }
        if (state == Player.STATE_ENDED) {
            return "ended";
        }
        return String.valueOf(state);
    }

    private void rebuildTrackInfo(@Nullable Tracks tracks) {
        TrackInfo info = new TrackInfo();
        audioTargets.clear();
        subtitleTargets.clear();
        selectedVideoTrackPresent = false;
        selectedAudioTrackPresent = false;
        Format selectedVideoFormat = null;
        boolean hasVideoTrack = false;
        boolean hasMp3Track = false;
        if (tracks != null) {
            int audioId = 0;
            int subtitleId = 1000;
            for (Tracks.Group group : tracks.getGroups()) {
                TrackGroup mediaGroup = group.getMediaTrackGroup();
                int trackType = group.getType();
                hasVideoTrack |= trackType == C.TRACK_TYPE_VIDEO;
                for (int i = 0; i < mediaGroup.length; i++) {
                    Format format = mediaGroup.getFormat(i);
                    boolean selected = group.isTrackSelected(i);
                    if (trackType == C.TRACK_TYPE_AUDIO) {
                        hasMp3Track |= MimeTypes.AUDIO_MPEG.equals(format.sampleMimeType);
                        TrackInfoBean bean = buildTrackBean("音轨", audioId++, format, selected, i, 0, 2);
                        info.addAudio(bean);
                        audioTargets.put(bean.trackId, new TrackSelectionTarget(mediaGroup, i));
                        selectedAudioTrackPresent |= selected;
                    } else if (trackType == C.TRACK_TYPE_TEXT) {
                        TrackInfoBean bean = buildTrackBean("字幕", subtitleId++, format, selected, i, 1, 3);
                        info.addSubtitle(bean);
                        subtitleTargets.put(bean.trackId, new TrackSelectionTarget(mediaGroup, i));
                    } else if (trackType == C.TRACK_TYPE_VIDEO && selected && selectedVideoFormat == null) {
                        selectedVideoFormat = format;
                        selectedVideoTrackPresent = true;
                    }
                }
            }
        }
        if (!hasVideoTrack && hasMp3Track && loopbackRangeFactory != null && loadControl != null) {
            loopbackRangeFactory.onMp3OnlySourceResolved();
            loadControl.setSourcePolicy(false, false);
            applyPlayIntent();
        }
        lastTrackInfo = info;
        updateRuntimeVideoMode(selectedVideoFormat, "tracks-changed");
    }

    private TrackInfoBean buildTrackBean(String prefix,
                                         int trackId,
                                         @Nullable Format format,
                                         boolean selected,
                                         int index,
                                         int groupId,
                                         int rendererId) {
        TrackInfoBean bean = new TrackInfoBean();
        bean.trackId = trackId;
        bean.index = index;
        bean.groupIndex = index;
        bean.trackGroupId = groupId;
        bean.renderId = rendererId;
        bean.selected = selected;
        bean.rawLanguage = format == null ? "" : nullToEmpty(format.language);
        bean.rawTitle = format == null ? "" : nullToEmpty(format.label);
        bean.rawCodec = format == null ? "" : nullToEmpty(format.codecs);
        bean.rawMimeType = format == null ? "" : nullToEmpty(format.sampleMimeType);
        bean.language = SystemPlayerTrackManager.getFriendlyLanguage(bean.rawLanguage, bean.rawTitle);
        int displayNumber = groupId == 0 ? trackId + 1 : Math.max(1, trackId - 999);
        bean.name = buildTrackName(prefix, displayNumber, bean.language, bean.rawTitle,
                TextUtils.isEmpty(bean.rawCodec) ? bean.rawMimeType : bean.rawCodec);
        return bean;
    }

    private String buildTrackName(String prefix, int number, String language, String title, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(' ').append(number);
        if (!TextUtils.isEmpty(language)) {
            builder.append(" - ").append(language);
        }
        if (!TextUtils.isEmpty(title)) {
            builder.append(' ').append(title);
        } else if (!TextUtils.isEmpty(detail)) {
            builder.append(' ').append(detail);
        }
        return builder.toString().trim();
    }

    private void dispatchSubtitleText(@Nullable List<Cue> cues) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        List<Cue> bitmapCues = new ArrayList<>();
        if (cues != null) {
            for (Cue cue : cues) {
                if (cue != null && cue.text != null && cue.text.length() > 0) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    int start = text.length();
                    text.append(cue.text);
                    int end = text.length();
                    if (cue.text instanceof Spanned) {
                        Spanned styledText = (Spanned) cue.text;
                        Object[] spans = styledText.getSpans(0, styledText.length(), Object.class);
                        for (Object span : spans) {
                            int spanStart = styledText.getSpanStart(span);
                            int spanEnd = styledText.getSpanEnd(span);
                            int flags = styledText.getSpanFlags(span);
                            if (spanStart >= 0 && spanEnd > spanStart) {
                                text.setSpan(span, start + spanStart, start + spanEnd, flags);
                            }
                        }
                    }
                }
                if (cue != null && cue.bitmap != null) {
                    bitmapCues.add(cue);
                }
            }
        }
        final CharSequence subtitleText = text;
        final List<Cue> immutableBitmapCues = bitmapCues.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(bitmapCues));
        runOnMain(() -> {
            if (!released && subtitleTextListener != null) {
                subtitleTextListener.onSubtitleText(subtitleText);
            }
            if (!released && bitmapSubtitleCueListener != null) {
                bitmapSubtitleCueListener.onBitmapSubtitleCues(immutableBitmapCues);
            }
        });
    }

    private void updateRuntimeVideoMode(@Nullable Format format, String reason) {
        if (format == null) {
            return;
        }
        format = SystemCodecHdrPolicy.withSourceDolbyVision(
                SystemCodecHdrPolicy.withSourceColorMetadata(format, sourceColorMetadata),
                sourceColorMetadata);
        String sampleMimeType = nullToEmpty(format.sampleMimeType).toLowerCase(Locale.US);
        String codecs = nullToEmpty(format.codecs).toLowerCase(Locale.US);
        ColorInfo colorInfo = format.colorInfo;
        // Read individual container metadata entries. HEVC Main10 and BT.2020
        // alone are not HDR evidence because both are also valid for SDR video.
        String metadataText = SystemCodecHdrPolicy.metadataText(format.metadata);
        String formatDump;
        try {
            formatDump = format.toString();
        } catch (Throwable ignored) {
            formatDump = "";
        }
        if (!TextUtils.isEmpty(formatDump)) {
            metadataText = TextUtils.isEmpty(metadataText)
                    ? formatDump : metadataText + " " + formatDump;
        }
        boolean dolbyVision = SystemCodecHdrPolicy.isDolbyVision(
                sampleMimeType, codecs, metadataText);
        int baseTransfer = videoCodecInitialized && !nativeDolbyVisionCodecActive && dolbyVision
                ? SystemCodecHdrPolicy.dolbyVisionBaseTransfer(format, sourceColorMetadata) : -1;
        if (baseTransfer == C.COLOR_TRANSFER_SDR) {
            lastRuntimeHdr = false;
            lastRuntimeDolbyVision = false;
            lastRuntimeOutputMode = "sdr";
            runtimeVideoFormatSeen = true;
            dispatchRuntimeVideoModeIfNeeded(reason);
            return;
        }
        boolean hdrCodecMarker = SystemCodecHdrPolicy.hasExplicitHdrMarker(codecs)
                || SystemCodecHdrPolicy.hasExplicitHdrMarker(metadataText);
        // Use the complete ExoPlayer Format as well as the explicit fields. On
        // several phone extractors PQ/HLG is present only in Format.toString(),
        // so relying on ColorInfo alone leaves HDR10/HDR10+ on the SDR path.
        boolean detectedHdr = SystemCodecHdrPolicy.isHdr(
                sampleMimeType, codecs, colorInfo, metadataText)
                || SystemCodecHdrPolicy.isHdr(format);
        boolean explicitSdr = SystemCodecHdrPolicy.isExplicitSdr(format);
        if (!detectedHdr && !dolbyVision) {
            if (!explicitSdr) {
                // Vendor extractors often expose HEVC/Main10 without ColorInfo even while the
                // decoder and display have already entered HDR. Unknown must not erase the
                // preflight/runtime HDR state or turn the corner badge back to SDR.
                log("echo-system-codec video-format mime=" + sampleMimeType
                        + " codecs=" + codecs
                        + " colorTransfer=" + (colorInfo == null ? "none" : colorInfo.colorTransfer)
                        + " colorSpace=" + (colorInfo == null ? "none" : colorInfo.colorSpace)
                        + " hdrStatic=" + (colorInfo != null && colorInfo.hdrStaticInfo != null)
                        + " hdrCodecMarker=" + hdrCodecMarker
                        + " metadata=" + (TextUtils.isEmpty(metadataText) ? "none" : "present")
                        + " -> dynamicRange=unknown (preserving output mode)");
                return;
            }
            runtimeVideoFormatSeen = true;
            lastRuntimeDolbyVision = false;
            lastRuntimeHdr = false;
            lastRuntimeOutputMode = "sdr";
            lastRuntimeReason = reason;
            log("echo-system-codec video-format mime=" + sampleMimeType
                    + " codecs=" + codecs
                    + " colorTransfer=" + (colorInfo == null ? "none" : colorInfo.colorTransfer)
                    + " colorSpace=" + (colorInfo == null ? "none" : colorInfo.colorSpace)
                    + " hdrStatic=" + (colorInfo != null && colorInfo.hdrStaticInfo != null)
                    + " hdrCodecMarker=" + hdrCodecMarker
                    + " metadata=" + (TextUtils.isEmpty(metadataText) ? "none" : "present")
                    + " -> dynamicRange=sdr (explicit output metadata)");
            dispatchRuntimeVideoModeIfNeeded(reason);
            return;
        }
        runtimeVideoFormatSeen = true;
        lastRuntimeDolbyVision = dolbyVision;
        lastRuntimeHdr = detectedHdr || dolbyVision;
        lastRuntimeOutputMode = dolbyVision
                ? (nativeDolbyVisionCodecActive ? "native-dv"
                : videoCodecInitialized ? "dv-base-hdr" : "dv-pending")
                : (lastRuntimeHdr ? "base-hdr" : "sdr");
        lastRuntimeReason = reason;
        // Diagnostic: what ExoPlayer actually decoded this video track as. For a Dolby Vision /
        // HDR file this reveals whether the container exposes it as video/dolby-vision (dv*) or a
        // plain HEVC track with HDR colorInfo, or neither (which would explain a black picture when
        // the DV signal is emitted into an SDR output).
        log("echo-system-codec video-format mime=" + sampleMimeType
                + " codecs=" + codecs
                + " colorTransfer=" + (colorInfo == null ? "none" : colorInfo.colorTransfer)
                + " colorSpace=" + (colorInfo == null ? "none" : colorInfo.colorSpace)
                + " hdrStatic=" + (colorInfo != null && colorInfo.hdrStaticInfo != null)
                + " hdrCodecMarker=" + hdrCodecMarker
                + " metadata=" + (TextUtils.isEmpty(metadataText) ? "none" : "present")
                + " -> dv=" + dolbyVision + " hdr=" + lastRuntimeHdr + " mode=" + lastRuntimeOutputMode);
        dispatchRuntimeVideoModeIfNeeded(reason);
    }

    private void onCodecOutputFormatChanged(@Nullable Format inputFormat,
                                            @Nullable MediaFormat outputFormat) {
        // MediaCodec renderer callbacks are dispatched on ExoPlayer's playback thread, while
        // the player-facing runtime-mode state is consumed by the main-looper UI. Keep all
        // state mutation and listener dispatch on the same looper as the Player instance.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Format callbackInputFormat = inputFormat;
            final MediaFormat callbackOutputFormat = outputFormat;
            mainHandler.post(() -> onCodecOutputFormatChanged(callbackInputFormat,
                    callbackOutputFormat));
            return;
        }
        if (SystemCodecHdrPolicy.isHdr(outputFormat)) {
            String mime = inputFormat == null ? "" : nullToEmpty(inputFormat.sampleMimeType);
            String codecs = inputFormat == null ? "" : nullToEmpty(inputFormat.codecs);
            boolean dolbyVision = SystemCodecHdrPolicy.isDolbyVision(mime, codecs,
                    SystemCodecHdrPolicy.metadataText(inputFormat == null ? null : inputFormat.metadata));
            lastRuntimeDolbyVision = dolbyVision;
            lastRuntimeHdr = true;
            lastRuntimeOutputMode = dolbyVision
                    ? (nativeDolbyVisionCodecActive ? "native-dv" : "dv-base-hdr")
                    : "base-hdr";
            runtimeVideoFormatSeen = true;
            lastRuntimeReason = "codec-output-format";
            log("echo-system-codec codec-output-hdr mime=" + mime
                    + " transfer=" + safeMediaFormatInteger(outputFormat, MediaFormat.KEY_COLOR_TRANSFER)
                    + " standard=" + safeMediaFormatInteger(outputFormat, MediaFormat.KEY_COLOR_STANDARD)
                    + " hdrStatic=" + hasMediaFormatValue(outputFormat, MediaFormat.KEY_HDR_STATIC_INFO)
                    + " hdr10Plus=" + hasMediaFormatValue(outputFormat, "hdr10-plus-info"));
            dispatchRuntimeVideoModeIfNeeded("codec-output-format");
        } else if (SystemCodecHdrPolicy.isExplicitSdr(inputFormat, outputFormat)) {
            lastRuntimeDolbyVision = false;
            lastRuntimeHdr = false;
            lastRuntimeOutputMode = "sdr";
            runtimeVideoFormatSeen = true;
            lastRuntimeReason = "codec-output-format";
            log("echo-system-codec codec-output-sdr transfer="
                    + safeMediaFormatInteger(outputFormat, MediaFormat.KEY_COLOR_TRANSFER)
                    + " standard=" + safeMediaFormatInteger(outputFormat, MediaFormat.KEY_COLOR_STANDARD));
            dispatchRuntimeVideoModeIfNeeded("codec-output-format");
        }
    }

    private static int safeMediaFormatInteger(MediaFormat format, String key) {
        try {
            return format != null && format.containsKey(key) ? format.getInteger(key) : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean hasMediaFormatValue(MediaFormat format, String key) {
        if (format == null) {
            return false;
        }
        try {
            java.nio.ByteBuffer value = format.getByteBuffer(key);
            return value != null && value.remaining() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void dispatchRuntimeVideoModeIfNeeded(String reason) {
        if (runtimeVideoModeListener == null || !runtimeVideoFormatSeen) {
            return;
        }
        if (runtimeVideoModeDispatched
                && lastDispatchedRuntimeHdr == lastRuntimeHdr
                && lastDispatchedRuntimeDolbyVision == lastRuntimeDolbyVision) {
            return;
        }
        runtimeVideoModeDispatched = true;
        lastDispatchedRuntimeHdr = lastRuntimeHdr;
        lastDispatchedRuntimeDolbyVision = lastRuntimeDolbyVision;
        final String callbackReason = TextUtils.isEmpty(reason) ? lastRuntimeReason : reason;
        final boolean callbackHdr = lastRuntimeHdr;
        final boolean callbackDolbyVision = lastRuntimeDolbyVision;
        final String callbackOutputMode = lastRuntimeOutputMode;
        runOnMain(() -> {
            if (!released && runtimeVideoModeListener != null) {
                runtimeVideoModeListener.onRuntimeVideoMode(callbackHdr, callbackDolbyVision,
                        callbackOutputMode, callbackReason);
            }
        });
    }

    private void resetPlaybackFlags() {
        playbackErrorMessage = null;
        nativeDolbyVisionCodecActive = false;
        videoCodecInitialized = false;
        subtitleClockPositionMs = 0L;
        subtitleClockDurationMs = 0L;
        prepared = false;
        firstFrameRendered = false;
        renderingStartNotified = false;
        selectedVideoTrackPresent = false;
        selectedAudioTrackPresent = false;
        bufferingNotified = false;
        startRequested = false;
        mainHandler.removeCallbacks(bufferStallPoll);
        lastBufferStallAhead = -1L;
        lastBufferStallProgressAt = 0L;
        lastBufferStallStallErrorAt = 0L;
        recentBufferEvents.clear();
        lastRebufferErrorAt = 0L;
        rebufferEpisodes = 0;
        firstFramePositionMs = C.TIME_UNSET;
        lastObservedPositionMs = C.TIME_UNSET;
        renderingStartPollCount = 0;
        pendingInitialSeekMs = C.TIME_UNSET;
        pendingSeekCompleteMs = C.TIME_UNSET;
        seekGeneration++;
        bufferingStartAllocatedBytes = allocator == null ? 0 : allocator.getTotalBytesAllocated();
        bufferingStartTransferredBytes = networkSpeedMeter == null
                ? 0L : networkSpeedMeter.getTotalNetworkBytes();
        bufferedToEndSnapshot.set(false);
        audioTargets.clear();
        subtitleTargets.clear();
        lastTrackInfo = new TrackInfo();
        lastRuntimeHdr = false;
        lastRuntimeDolbyVision = false;
        runtimeVideoFormatSeen = false;
        runtimeVideoModeDispatched = false;
        lastDispatchedRuntimeHdr = false;
        lastDispatchedRuntimeDolbyVision = false;
        lastRuntimeOutputMode = "sdr";
        lastRuntimeReason = "init";
    }

    private boolean isByteGatedVod() {
        return currentSourceRangeBacked
                && loadControl != null
                && loadControl.shouldWaitForByteBuffer()
                && loopbackRangeFactory != null;
    }

    private void scheduleBufferStallPoll() {
        if (released || !startRequested || !isByteGatedVod()) {
            return;
        }
        mainHandler.removeCallbacks(bufferStallPoll);
        mainHandler.postDelayed(bufferStallPoll, BUFFER_STALL_POLL_MS);
    }

    private void applyPlayIntent() {
        SimpleExoPlayer current = player;
        if (current == null || released) {
            return;
        }
        // Exo can enter READY without calling LoadControl when its loader temporarily stops.
        // Enforce the same readiness predicate at output as well; there is no second latch.
        boolean waitingForBytes = isByteGatedVod() && !loadControl.hasRequiredBufferNow();
        boolean play = startRequested && !waitingForBytes;
        if (startRequested && waitingForBytes) {
            // READY describes the sample queue, not our Range runway. Pausing output for
            // this gate must keep the same visible buffering episode as native BUFFERING.
            notifyBufferingStart();
        }
        if (current.getPlayWhenReady() != play) {
            current.setPlayWhenReady(play);
            recordBufferEvent("output-intent=" + play);
        }
    }

    private void recordBufferEvent(String event) {
        SimpleExoPlayer current = player;
        if (released || current == null || !currentSourceRangeBacked) {
            return;
        }
        // Loading can toggle several times per second on a high-bitrate remux. Retain only
        // scalars here: formatting the full Range interval map on every toggle allocated
        // diagnostic strings and acquired the cache monitor during healthy playback.
        BufferEventSnapshot snapshot = recentBufferEvents.size() >= 8
                ? recentBufferEvents.removeFirst() : new BufferEventSnapshot();
        snapshot.timeMs = android.os.SystemClock.uptimeMillis();
        snapshot.event = event;
        snapshot.state = current.getPlaybackState();
        snapshot.positionMs = current.getCurrentPosition();
        snapshot.bufferedPositionMs = current.getBufferedPosition();
        snapshot.sampleBufferMs = current.getTotalBufferedDuration();
        snapshot.loading = current.isLoading();
        snapshot.playWhenReady = current.getPlayWhenReady();
        snapshot.allocatorBytes = allocator == null ? -1 : allocator.getTotalBytesAllocated();
        snapshot.heapUsedBytes = Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory();
        snapshot.speedBytesPerSecond = getTcpSpeed();
        snapshot.rangeAheadBytes = loopbackRangeFactory == null
                ? -1L : loopbackRangeFactory.getBufferedAheadBytes();
        snapshot.rangeTargetBytes = loopbackRangeFactory == null
                ? -1L : loopbackRangeFactory.getPlaybackStartBufferTargetBytes();
        snapshot.rangeReady = loadControl != null && loadControl.hasRequiredBufferNow();
        recentBufferEvents.addLast(snapshot);
    }

    /** Eight reusable event slots; expensive diagnostics are formatted only on an error. */
    private static final class BufferEventSnapshot {
        long timeMs;
        String event;
        int state;
        long positionMs;
        long bufferedPositionMs;
        long sampleBufferMs;
        boolean loading;
        boolean playWhenReady;
        int allocatorBytes;
        long heapUsedBytes;
        long speedBytesPerSecond;
        long rangeAheadBytes;
        long rangeTargetBytes;
        boolean rangeReady;

        String format() {
            return timeMs + ":" + event
                    + " state=" + stateName(state)
                    + " posMs=" + positionMs
                    + " bufferedMs=" + bufferedPositionMs
                    + " sampleBufferMs=" + sampleBufferMs
                    + " loading=" + loading
                    + " playWhenReady=" + playWhenReady
                    + " allocator=" + allocatorBytes
                    + " heapUsedBytes=" + heapUsedBytes
                    + " speed=" + speedBytesPerSecond
                    + " rangeAheadBytes=" + rangeAheadBytes
                    + " rangeTargetBytes=" + rangeTargetBytes
                    + " rangeReady=" + rangeReady;
        }
    }

    private String getPlaybackBufferSnapshot() {
        SimpleExoPlayer current = player;
        if (current == null) {
            return "player=none";
        }
        Format video = current.getVideoFormat();
        return "state=" + stateName(current.getPlaybackState())
                + " posMs=" + current.getCurrentPosition()
                + " durationMs=" + current.getDuration()
                + " bufferedMs=" + current.getBufferedPosition()
                + " sampleBufferMs=" + current.getTotalBufferedDuration()
                + " loading=" + current.isLoading()
                + " playWhenReady=" + current.getPlayWhenReady()
                + " allocator=" + (allocator == null ? -1 : allocator.getTotalBytesAllocated())
                + " heapMaxBytes=" + Runtime.getRuntime().maxMemory()
                + " heapUsedBytes=" + (Runtime.getRuntime().totalMemory()
                    - Runtime.getRuntime().freeMemory())
                + " speed=" + getTcpSpeed()
                + " video=" + (video == null ? "none" : video.width + "x" + video.height
                    + "/" + video.frameRate + "/avg=" + video.averageBitrate)
                + " " + (loadControl == null ? "loadControl=none" : loadControl.getBufferDiagnostics())
                + " " + (loopbackRangeFactory == null ? "range=none"
                    : loopbackRangeFactory.getBufferDiagnostics());
    }

    private void reportRebufferError() {
        rebufferEpisodes++;
        long now = android.os.SystemClock.uptimeMillis();
        if (lastRebufferErrorAt > 0L && now - lastRebufferErrorAt < 3_000L) {
            return;
        }
        lastRebufferErrorAt = now;
        log("echo-system-codec buffer-error-rebuffer count=" + rebufferEpisodes
                + " current={" + getPlaybackBufferSnapshot() + "}");
        // Android truncates a single log record around 4 KiB. Emit the small bounded
        // history only on this error, so the state leading to starvation stays readable.
        int index = 0;
        for (BufferEventSnapshot event : recentBufferEvents) {
            log("echo-system-codec buffer-error-history count=" + rebufferEpisodes
                    + " event=" + index++ + " {" + event.format() + "}");
        }
    }

    private void reportPlaybackPerformanceError(String event, String detail) {
        if (released || player == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        // These are renderer errors, independent of a network BUFFERING transition. Keep
        // normal frames silent and bound detailed snapshots even on a struggling decoder.
        if (lastPerformanceErrorAtMs > 0L && now - lastPerformanceErrorAtMs < 10_000L) {
            return;
        }
        lastPerformanceErrorAtMs = now;
        log("echo-system-codec performance-error event=" + event + detail
                + " totalDroppedFrames=" + droppedVideoFrames + " audioUnderruns=" + audioUnderruns
                + " current={" + getPlaybackBufferSnapshot() + "}");
    }

    /** Uses LoadControl's byte evidence at the output boundary and records stalled episodes. */
    private void checkBufferStall() {
        if (released || !startRequested || player == null) {
            return;
        }
        if (handlePlaybackCacheError()) {
            return;
        }
        if (!isByteGatedVod()) {
            applyPlayIntent();
            return;
        }
        applyPlayIntent();
        boolean ready = loadControl.hasRequiredBufferNow();
        if (player.getPlaybackState() != Player.STATE_BUFFERING && ready) {
            lastBufferStallAhead = -1L;
            lastBufferStallProgressAt = 0L;
            return;
        }
        long ahead = loopbackRangeFactory.getBufferedAheadBytes();
        long arrivingAhead = loopbackRangeFactory.getBufferedAheadBytesForProgress();
        boolean wholeFile = loopbackRangeFactory.hasWholeFileBuffered();
        long now = android.os.SystemClock.uptimeMillis();
        if (lastBufferStallAhead < 0L || arrivingAhead != lastBufferStallAhead) {
            lastBufferStallAhead = arrivingAhead;
            lastBufferStallProgressAt = now;
        } else if (lastBufferStallProgressAt > 0L
                && now - lastBufferStallProgressAt >= BUFFER_STALL_ERROR_MS
                && now - lastBufferStallStallErrorAt >= BUFFER_STALL_ERROR_MS) {
            lastBufferStallStallErrorAt = now;
            log("echo-system-codec buffer-error-stalled aheadBytes=" + ahead
                    + " requiredBytes=" + SystemCodecBufferPolicy.MIN_BUFFER_BYTES
                    + " progressPercent=" + getBufferedPercentage()
                    + " speedBytesPerSecond=" + getTcpSpeed()
                    + " wholeFile=" + wholeFile
                    + " state=" + (player == null ? "null" : stateName(player.getPlaybackState()))
                    + " loading=" + (player != null && player.isLoading())
                    + " playWhenReady=" + (player != null && player.getPlayWhenReady())
                    + " startRequested=" + startRequested
                    + " rangeReady=" + ready
                    + " rangeBacked=" + currentSourceRangeBacked
                    + " gateEnabled=" + (loadControl != null
                            && loadControl.shouldWaitForByteBuffer())
                    + " sampleBufferMs=" + Math.max(0L,
                            player.getBufferedPosition() - player.getCurrentPosition())
                    + " allocatorBytes=" + (allocator == null
                            ? -1 : allocator.getTotalBytesAllocated())
                    + " heapMaxBytes=" + Runtime.getRuntime().maxMemory()
                    + " heapUsedBytes=" + (Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory())
                    + " " + loopbackRangeFactory.getBufferDiagnostics());
        }
        scheduleBufferStallPoll();
    }

    @Override
    public String getPlaybackErrorMessage() {
        return playbackErrorMessage;
    }

    private boolean handlePlaybackCacheError() {
        if (playbackErrorMessage != null) {
            return true;
        }
        java.io.IOException error = loopbackRangeFactory == null
                ? null : loopbackRangeFactory.getPlaybackCacheError();
        if (error == null) {
            return false;
        }
        playbackErrorMessage = "播放可用内存不足，请关闭其他应用后重试";
        startRequested = false;
        mainHandler.removeCallbacks(bufferStallPoll);
        log("echo-system-codec cache-memory-error " + error.getMessage());
        // Cancel readers before stopping Exo, so its loader can return all raw/sample storage.
        loopbackRangeFactory.release();
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (RuntimeException stopError) {
                log("echo-system-codec memory-error-stop-failed " + describeThrowable(stopError));
            }
        }
        notifyBufferingEnd();
        notifyError();
        return true;
    }

    private void notifyBufferingStart() {
        if (bufferingNotified || released) {
            return;
        }
        bufferingNotified = true;
        resetBufferingReadinessBaseline();
        notifyInfo(MEDIA_INFO_BUFFERING_START, 0);
    }

    private void resetBufferingReadinessBaseline() {
        if (allocator != null) {
            allocator.trim();
        }
        bufferingStartAllocatedBytes = allocator == null ? 0 : allocator.getTotalBytesAllocated();
        bufferingStartTransferredBytes = networkSpeedMeter == null
                ? 0L : networkSpeedMeter.getTotalNetworkBytes();
    }

    /** Refreshes the main-thread-only Player getters used by the playback-thread LoadControl. */
    private void updateBufferedToEndSnapshot() {
        SimpleExoPlayer current = player;
        if (current == null) {
            bufferedToEndSnapshot.set(false);
            return;
        }
        try {
            long duration = current.getDuration();
            long bufferedPosition = current.getBufferedPosition();
            bufferedToEndSnapshot.set(duration > 0L
                    && duration != C.TIME_UNSET
                    && bufferedPosition != C.TIME_UNSET
                    && bufferedPosition >= duration);
        } catch (Throwable ignored) {
            bufferedToEndSnapshot.set(false);
        }
    }

    private void notifyBufferingEnd() {
        if (!bufferingNotified || released) {
            return;
        }
        bufferingNotified = false;
        notifyInfo(MEDIA_INFO_BUFFERING_END, 0);
    }

    private void maybeNotifyRenderingStart(String reason) {
        SimpleExoPlayer current = player;
        if (released || renderingStartNotified || current == null
                || !current.getPlayWhenReady()) {
            return;
        }
        // Some TV firmware reports STATE_BUFFERING while its MediaCodec has already rendered
        // the first frame. Waiting for a later STATE_READY leaves VideoView in BUFFERING forever,
        // so its timeout watchdog tears down a healthy player. A rendered frame is the stronger
        // readiness signal; ExoPlayer will still emit BUFFERING again if the runway is exhausted.
        boolean nativeIsPlaying = current.isPlaying();
        boolean positionAdvanced = hasObservedPositionAdvance(current);
        if (!SystemCodecRenderingPolicy.shouldNotify(
                released,
                renderingStartNotified,
                current.getPlayWhenReady(),
                selectedVideoTrackPresent,
                selectedAudioTrackPresent,
                firstFrameRendered,
                nativeIsPlaying,
                positionAdvanced)) {
            return;
        }
        renderingStartNotified = true;
        log("echo-system-codec rendering-start reason=" + reason
                + " video=" + selectedVideoTrackPresent
                + " firstFrame=" + firstFrameRendered);
        notifyBufferingEnd();
        notifyInfo(MEDIA_INFO_RENDERING_START, 0);
    }

    private void scheduleRenderingStartPoll() {
        if (released || renderingStartNotified || player == null
                || !player.getPlayWhenReady() || renderingStartPollCount >= MAX_RENDERING_START_POLLS) {
            return;
        }
        renderingStartPollCount++;
        final int generation = seekGeneration;
        mainHandler.postDelayed(() -> {
            if (released || generation != seekGeneration || renderingStartNotified || player == null) {
                return;
            }
            maybeNotifyRenderingStart("position-poll");
            if (!renderingStartNotified) {
                scheduleRenderingStartPoll();
            }
        }, RENDERING_START_POLL_MS);
    }

    /** A first frame can be rendered before the vendor clock starts. */
    private boolean hasObservedPositionAdvance(SimpleExoPlayer current) {
        if (current == null) {
            return false;
        }
        long position = current.getCurrentPosition();
        if (position == C.TIME_UNSET || position < 0L) {
            return false;
        }
        if (lastObservedPositionMs == C.TIME_UNSET) {
            lastObservedPositionMs = position;
            return false;
        }
        boolean advanced = position > lastObservedPositionMs
                && (firstFramePositionMs == C.TIME_UNSET || position > firstFramePositionMs);
        if (position > lastObservedPositionMs) {
            lastObservedPositionMs = position;
        }
        return advanced;
    }

    private void notifyPrepared() {
        runOnMain(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onPrepared();
            }
        });
    }

    private void notifyInfo(int what, int extra) {
        runOnMain(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onInfo(what, extra);
            }
        });
    }

    private void notifySeekComplete(long position) {
        runOnMain(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onSeekComplete(position);
            }
        });
    }

    private void notifyCompletion() {
        runOnMain(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onCompletion();
            }
        });
    }

    /**
     * A network-drive proxy (6677 quark/baidu spider) frequently stalls or drops the connection
     * mid-stream, surfacing as an IO PlaybackException (error codes 2000-2999). That is a
     * momentary source failure, not a decode failure: re-preparing the same media item usually
     * lets the proxy reopen the connection and continue. Retry a bounded number of times with a
     * short backoff, resuming from the last played position, before giving up to onError().
     */
    private boolean tryRetryTransientSourceError(PlaybackException error) {
        if (released || player == null) {
            return false;
        }
        // The controlled HLS proxy retries a failed CDN segment across independent connections
        // and DNS routes before ExoPlayer sees it. Re-preparing that same playlist after a final
        // HTTP response failure only asks for the same missing segment again, which produces the
        // visible black-screen/reload loop. Keep the established retry path for direct range VOD
        // sources, including their 416 recovery behavior.
        if (isControlledHlsProxySource() && containsHttpResponseFailure(error)) {
            log("echo-system-codec source-retry-suppressed http-response "
                    + describeThrowable(error));
            return false;
        }
        int code = error.errorCode;
        int attempt = SystemCodecRetryPolicy.nextRetryAttempt(sourceRetryCount, code);
        if (attempt < 0) {
            return false;
        }
        sourceRetryCount = attempt;
        final long resumeMs = SystemCodecRetryPolicy.resolveRetryResumePosition(
                player.getCurrentPosition(), pendingSeekCompleteMs != C.TIME_UNSET,
                pendingSeekCompleteMs, initialResumePositionMs);
        log("echo-system-codec source-retry attempt=" + attempt + "/"
                + SystemCodecRetryPolicy.MAX_SOURCE_RETRIES
                + " resumeMs=" + resumeMs + " code=" + code);
        notifyBufferingStart();
        mainHandler.postDelayed(() -> {
            if (released || player == null) {
                return;
            }
            try {
                // A retry creates a fresh DataSource. Clear the retained byte snapshot before
                // the replacement starts so the gate cannot mistake the previous source's
                // runway for bytes at the new retry anchor (especially when resuming at 0 ms).
                if (loopbackRangeFactory != null) {
                    loopbackRangeFactory.resetPlaybackBufferSnapshot();
                }
                resetPlaybackFlagsForRetry();
                configureSourceBufferPolicy();
                applyPlayIntent();
                player.clearMediaItems();
                MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(Uri.parse(dataSource));
                String mimeType = resolveMediaItemMimeType(dataSource);
                if (!TextUtils.isEmpty(mimeType)) {
                    mediaItemBuilder.setMimeType(mimeType);
                }
                if (loopbackRangeFactory != null) {
                    loopbackRangeFactory.setInitialResumePosition(resumeMs);
                }
                player.setMediaItem(mediaItemBuilder.build(), resumeMs);
                player.prepare();
                if (isByteGatedVod()) {
                    scheduleBufferStallPoll();
                }
                log("echo-system-codec source-retry re-prepared attempt=" + attempt
                        + " resumeMs=" + resumeMs);
            } catch (Throwable throwable) {
                log("echo-system-codec source-retry-failed " + describeThrowable(throwable));
                notifyBufferingEnd();
                notifyError();
            }
        }, SOURCE_RETRY_BACKOFF_MS * attempt);
        return true;
    }

    private boolean isControlledHlsProxySource() {
        if (TextUtils.isEmpty(dataSource)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(dataSource);
            String host = uri == null ? null : uri.getHost();
            String path = uri == null ? null : uri.getPath();
            return ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && "/proxy".equals(path)
                    && "live".equalsIgnoreCase(uri.getQueryParameter("go"))
                    && "m3u8".equalsIgnoreCase(uri.getQueryParameter("type"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean containsHttpResponseFailure(@Nullable Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    /** Like resetPlaybackFlags but preserves the source-retry counter across a retry prepare. */
    private void resetPlaybackFlagsForRetry() {
        prepared = false;
        firstFrameRendered = false;
        renderingStartNotified = false;
        selectedVideoTrackPresent = false;
        selectedAudioTrackPresent = false;
        pendingInitialSeekMs = C.TIME_UNSET;
        pendingSeekCompleteMs = C.TIME_UNSET;
        bufferedToEndSnapshot.set(false);
        seekGeneration++;
    }

    private void notifyError() {
        runOnMain(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        });
    }

    private void notifyVideoSizeChanged(int width, int height) {
        runOnMain(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onVideoSizeChanged(width, height);
            }
        });
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    private void enforceExternalPcmVolume() {
        if (!passthroughVolumeLocked) {
            return;
        }
        try {
            AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (max > 0 && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != max) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
                }
            }
        } catch (Throwable throwable) {
            log("echo-system-codec volume-lock-failed " + throwable.getClass().getSimpleName());
        }
    }

    private Map<String, String> sanitizeRequestHeaders(@Nullable Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> clean = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            if (key.toLowerCase(Locale.US).startsWith("x-tvbox-probe-")) {
                continue;
            }
            clean.put(key, entry.getValue());
        }
        return clean.isEmpty() ? Collections.emptyMap() : clean;
    }

    private boolean hasHeaderValue(@Nullable Map<String, String> headers,
                                   String expectedName,
                                   String expectedValue) {
        if (headers == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && expectedName.equalsIgnoreCase(entry.getKey().trim())
                    && expectedValue.equalsIgnoreCase(entry.getValue().trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNetworkSource(@Nullable String source) {
        if (TextUtils.isEmpty(source)) {
            return false;
        }
        try {
            String scheme = Uri.parse(source).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Live/HLS uses its streaming policy; HTTP VOD waits for its contiguous Range cache. */
    private void configureSourceBufferPolicy() {
        if (loadControl == null) {
            return;
        }
        boolean liveOrDynamic = isLiveOrDynamicSourceUrl(dataSource);
        // Live/HLS stays stream-driven. Remote HTTP/WebDAV VOD is read through the bounded
        // Range source so startup readiness is based on contiguous media bytes, not time.
        boolean byteGated = currentSourceRangeBacked && !liveOrDynamic;
        loadControl.setSourcePolicy(byteGated, liveOrDynamic);
        log("echo-system-codec source-buffer-policy byteGate=" + byteGated
                + " rangeBacked=" + currentSourceRangeBacked
                + " liveOrDynamic=" + liveOrDynamic);
    }

    private boolean isLiveOrDynamicSourceUrl(@Nullable String url) {
        return isControlledHlsProxySource()
                || PlaybackUrlNormalizer.isHlsLike(url)
                || !TextUtils.isEmpty(resolveMediaItemMimeType(url));
    }

    private boolean isLiveOrDynamicTimeline(@Nullable Timeline timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return false;
        }
        Timeline.Window window = timeline.getWindow(0, new Timeline.Window());
        return window.isLive() || window.isDynamic;
    }

    @Nullable
    private String resolveMediaItemMimeType(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (PlaybackUrlNormalizer.isHlsLike(url)) {
            return MimeTypes.APPLICATION_M3U8;
        }
        try {
            Uri uri = Uri.parse(url);
            String go = uri.getQueryParameter("go");
            String type = uri.getQueryParameter("type");
            String format = uri.getQueryParameter("format");
            if ("live".equalsIgnoreCase(go)
                    || "m3u8".equalsIgnoreCase(type)
                    || "m3u8".equalsIgnoreCase(format)) {
                return MimeTypes.APPLICATION_M3U8;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private String describeThrowable(@Nullable Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(throwable.getClass().getSimpleName());
        if (!TextUtils.isEmpty(throwable.getMessage())) {
            builder.append(':').append(throwable.getMessage());
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            builder.append(" <- ").append(cause.getClass().getSimpleName());
            if (!TextUtils.isEmpty(cause.getMessage())) {
                builder.append(':').append(cause.getMessage());
            }
        }
        return builder.toString();
    }

    private void log(String message) {
        if (!isErrorLog(message)) {
            return;
        }
        Log.e(TAG, message);
        LOG.e(message);
    }

    private static boolean isErrorLog(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("exception")
                || lower.contains("stalled")
                || lower.contains("timeout")
                || lower.contains("retry");
    }

    private static final class TrackSelectionTarget {
        final TrackGroup group;
        final int trackIndex;

        TrackSelectionTarget(TrackGroup group, int trackIndex) {
            this.group = group;
            this.trackIndex = trackIndex;
        }
    }

    private static final class SystemCodecRenderersFactory extends DefaultRenderersFactory {
        private final Context context;
        private final SystemCodecPlayer owner;
        SystemCodecRenderersFactory(Context context, SystemCodecPlayer owner) {
            super(context);
            this.context = context.getApplicationContext();
            this.owner = owner;
            // Let the TV's MediaCodec audio decoder run first. The affected NVT hardware can
            // decode this route without starving the video clock; FFmpeg remains the fallback
            // for formats the platform does not expose. Both paths feed the stereo-PCM sink
            // below, so no encoded bitstream passthrough is enabled.
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);
            setMediaCodecSelector(DeviceCodecSelector.INSTANCE);
            setEnableDecoderFallback(true);
            setEnableAudioFloatOutput(false);
            setEnableAudioOffload(false);
            setEnableAudioTrackPlaybackParams(false);
            setAllowedVideoJoiningTimeMs(5_000L);
        }

        @Override
        protected AudioSink buildAudioSink(Context ignored,
                                           boolean enableFloatOutput,
                                           boolean enableAudioTrackPlaybackParams,
                                           boolean enableOffload) {
            // Root cause of "audio clock advances but no sound": TrueHD/DTS decode to 8-channel
            // PCM, the device HAL falsely reports deviceMaxChannels=10, so DefaultAudioSink sends
            // 7.1 PCM straight through with no downmix — but the real output (TV speakers / stereo)
            // cannot render 7.1 PCM, so it is silent while the clock keeps advancing.
            //
            // Fix (per requirement): force EVERYTHING to stereo PCM. Never output more than 2
            // channels. Two parts are BOTH required:
            //   1) StereoPcmAudioProcessor actually downmixes multichannel PCM -> stereo.
            //   2) Advertise a stereo-only AudioCapabilities so the sink never decides it can pass
            //      multichannel through. (Encoded passthrough is intentionally disabled here; every
            //      compressed track is decoded to PCM and downmixed to stereo.)
            AudioCapabilities deviceCapabilities = AudioCapabilities.getCapabilities(context);
            // Keep the device's PCM input channel limit so MediaCodec/FFmpeg multichannel tracks
            // remain supported, but advertise no encoded formats. The processor below converts
            // every decoded PCM frame to stereo before AudioTrack, so optical/ARC receives only
            // 16-bit two-channel PCM. Limiting the capability object itself to two channels makes
            // ExoPlayer reject an 8-channel decoder output before the processor can downmix it.
            int pcmInputChannelCount = Math.max(2, deviceCapabilities.getMaxChannelCount());
            AudioCapabilities pcmOnlyCaps = new AudioCapabilities(
                    new int[]{C.ENCODING_PCM_16BIT}, pcmInputChannelCount);
            String capsLog = "echo-system-codec audio-caps forced=stereo-pcm"
                    + " inputMaxChannels=" + pcmInputChannelCount
                    + " deviceMaxChannels=" + deviceCapabilities.getMaxChannelCount();

            return new DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(pcmOnlyCaps)
                    .setAudioProcessors(new AudioProcessor[]{new StereoPcmAudioProcessor()})
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .build();
        }

        @Override
        protected void buildVideoRenderers(Context context,
                                           int extensionRendererMode,
                                           MediaCodecSelector mediaCodecSelector,
                                           boolean enableDecoderFallback,
                                           Handler eventHandler,
                                           VideoRendererEventListener eventListener,
                                           long allowedVideoJoiningTimeMs,
                                           ArrayList<Renderer> out) {
            int firstVideoRenderer = out.size();
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
                    enableDecoderFallback, eventHandler, eventListener,
                    allowedVideoJoiningTimeMs, out);
            for (int i = firstVideoRenderer; i < out.size(); i++) {
                if (out.get(i) instanceof MediaCodecVideoRenderer) {
                    out.set(i, new SafeSurfaceMediaCodecVideoRenderer(context,
                            getCodecAdapterFactory(), mediaCodecSelector,
                            allowedVideoJoiningTimeMs, enableDecoderFallback,
                            eventHandler, eventListener, owner));
                    break;
                }
            }
        }

        @Override
        protected void buildAudioRenderers(Context context,
                                           int extensionRendererMode,
                                           MediaCodecSelector mediaCodecSelector,
                                           boolean enableDecoderFallback,
                                           AudioSink audioSink,
                                           Handler eventHandler,
                                           AudioRendererEventListener eventListener,
                                           ArrayList<Renderer> out) {
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
                    enableDecoderFallback, audioSink, eventHandler,
                    new LoggingAudioRendererEventListener(eventListener), out);
        }

        private static final class LoggingAudioRendererEventListener
                implements AudioRendererEventListener {
            private final AudioRendererEventListener delegate;

            LoggingAudioRendererEventListener(AudioRendererEventListener delegate) {
                this.delegate = delegate;
            }

            @Override
            public void onAudioEnabled(DecoderCounters counters) {
                log("echo-system-codec audio-enabled");
                delegate.onAudioEnabled(counters);
            }

            @Override
            public void onAudioDecoderInitialized(String decoderName,
                                                   long initializedTimestampMs,
                                                   long initializationDurationMs) {
                log("echo-system-codec audio-decoder-event=" + decoderName);
                delegate.onAudioDecoderInitialized(decoderName, initializedTimestampMs,
                        initializationDurationMs);
            }

            @Override
            public void onAudioInputFormatChanged(Format format,
                                                  DecoderReuseEvaluation evaluation) {
                log("echo-system-codec audio-format codec=" + safe(format.codecs)
                        + " mime=" + safe(format.sampleMimeType)
                        + " channels=" + format.channelCount
                        + " rate=" + format.sampleRate);
                delegate.onAudioInputFormatChanged(format, evaluation);
            }

            @Override
            public void onAudioPositionAdvancing(long playoutStartSystemTimeMs) {
                log("echo-system-codec audio-position-advancing");
                delegate.onAudioPositionAdvancing(playoutStartSystemTimeMs);
            }

            @Override
            public void onAudioUnderrun(int bufferSize,
                                        long bufferSizeMs,
                                        long elapsedSinceLastFeedMs) {
                log("echo-system-codec audio-underrun bytes=" + bufferSize
                        + " bufferMs=" + bufferSizeMs
                        + " elapsedMs=" + elapsedSinceLastFeedMs);
                delegate.onAudioUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
            }

            @Override
            public void onAudioDecoderReleased(String decoderName) {
                log("echo-system-codec audio-decoder-released=" + decoderName);
                delegate.onAudioDecoderReleased(decoderName);
            }

            @Override
            public void onAudioDisabled(DecoderCounters counters) {
                log("echo-system-codec audio-disabled");
                delegate.onAudioDisabled(counters);
            }

            @Override
            public void onAudioCodecError(Exception audioCodecError) {
                log("echo-system-codec audio-codec-error="
                        + describe(audioCodecError));
                delegate.onAudioCodecError(audioCodecError);
            }

            @Override
            public void onAudioSinkError(Exception audioSinkError) {
                log("echo-system-codec audio-sink-error="
                        + describe(audioSinkError));
                delegate.onAudioSinkError(audioSinkError);
            }

            private static void log(String message) {
                if (message == null) {
                    return;
                }
                String lower = message.toLowerCase(Locale.ROOT);
                if (!(lower.contains("error") || lower.contains("failed")
                        || lower.contains("exception") || lower.contains("timeout")
                        || lower.contains("retry"))) {
                    return;
                }
                Log.e(TAG, message);
                LOG.e(message);
            }

            private static String safe(String value) {
                return value == null ? "" : value;
            }

            private static String describe(Exception error) {
                if (error == null) {
                    return "null";
                }
                return error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
            }
        }
    }

    private static final class SafeSurfaceMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
        private final SystemCodecPlayer owner;
        private boolean configuredNativeDolbyVision;

        SafeSurfaceMediaCodecVideoRenderer(Context context,
                                           MediaCodecAdapter.Factory codecAdapterFactory,
                                           MediaCodecSelector mediaCodecSelector,
                                           long allowedVideoJoiningTimeMs,
                                           boolean enableDecoderFallback,
                                           Handler eventHandler,
                                           VideoRendererEventListener eventListener,
                                           SystemCodecPlayer owner) {
            super(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                    enableDecoderFallback, eventHandler, eventListener, 50);
            this.owner = owner;
        }

        private VideoColorMetadata sourceMetadata() {
            return owner == null ? VideoColorMetadata.EMPTY : owner.sourceColorMetadata;
        }

        private Format sourceFormat(Format format) {
            return SystemCodecHdrPolicy.withSourceDolbyVision(
                    SystemCodecHdrPolicy.withSourceColorMetadata(format, sourceMetadata()), sourceMetadata());
        }

        private boolean isDolbyVision(Format format) {
            return MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                    || SystemCodecHdrPolicy.isDolbyVision(format.sampleMimeType, format.codecs, null);
        }

        @Override
        protected int supportsFormat(MediaCodecSelector selector, Format format)
                throws MediaCodecUtil.DecoderQueryException {
            Format restored = sourceFormat(format);
            if (!isDolbyVision(restored)) return super.supportsFormat(selector, restored);
            List<MediaCodecInfo> nativeDecoders = supportedNativeDecoders(selector, restored, false);
            if (!nativeDecoders.isEmpty()) return super.supportsFormat(selector, restored);
            Format base = SystemCodecHdrPolicy.dolbyVisionBaseLayerFormat(restored, sourceMetadata());
            return base == null ? RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                    : super.supportsFormat(selector, base);
        }

        @Override
        protected List<MediaCodecInfo> getDecoderInfos(MediaCodecSelector selector, Format format,
                                                       boolean requiresSecureDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            Format restored = sourceFormat(format);
            if (!isDolbyVision(restored)) {
                return super.getDecoderInfos(selector, restored, requiresSecureDecoder);
            }
            // Prefer decoders which support this stream's actual DV profile/level and size.
            // A cached display capability or the existence of an unrelated DV decoder is not
            // evidence that every DV profile should use the same route.
            ArrayList<MediaCodecInfo> ordered = new ArrayList<>(
                    supportedNativeDecoders(selector, restored, requiresSecureDecoder));
            Format base = SystemCodecHdrPolicy.dolbyVisionBaseLayerFormat(restored, sourceMetadata());
            if (base != null) {
                for (MediaCodecInfo decoder : selector.getDecoderInfos(base.sampleMimeType,
                        requiresSecureDecoder, false)) {
                    if (decoder.isFormatSupported(base)) ordered.add(decoder);
                }
            }
            return ordered;
        }

        private List<MediaCodecInfo> supportedNativeDecoders(MediaCodecSelector selector,
                Format format, boolean requiresSecureDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            ArrayList<MediaCodecInfo> supported = new ArrayList<>();
            for (MediaCodecInfo decoder : selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION,
                    requiresSecureDecoder, false)) {
                if (decoder.isFormatSupported(format)) supported.add(decoder);
            }
            return supported;
        }

        @Override
        protected MediaCodecAdapter.Configuration getMediaCodecConfiguration(MediaCodecInfo codecInfo,
                Format format, android.media.MediaCrypto crypto, float codecOperatingRate) {
            Format restored = sourceFormat(format);
            boolean dolbyVision = isDolbyVision(restored);
            configuredNativeDolbyVision = dolbyVision
                    && (MimeTypes.VIDEO_DOLBY_VISION.equals(codecInfo.mimeType)
                    || MimeTypes.VIDEO_DOLBY_VISION.equals(codecInfo.codecMimeType));
            Format base = dolbyVision && !configuredNativeDolbyVision
                    ? SystemCodecHdrPolicy.dolbyVisionBaseLayerFormat(restored, sourceMetadata()) : null;
            stripDolbyVisionRpu = !configuredNativeDolbyVision
                    && SystemCodecHdrPolicy.shouldStripDolbyVisionRpu(restored,
                    codecInfo.mimeType, sourceMetadata());
            // Only the selected ordinary HEVC codec receives the compatible base format.
            // The native DV codec receives the original codec string, CSD, RPU and EL data.
            return super.getMediaCodecConfiguration(codecInfo, base == null ? restored : base,
                    crypto, codecOperatingRate);
        }

        @Override
        protected void onCodecInitialized(String name, MediaCodecAdapter.Configuration configuration,
                                           long initializedTimestampMs, long initializationDurationMs) {
            super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs);
            if (owner != null) {
                final boolean nativeDv = configuredNativeDolbyVision;
                final Format configuredFormat = configuration.format;
                owner.runOnMain(() -> {
                    if (!owner.released) {
                        owner.nativeDolbyVisionCodecActive = nativeDv;
                        owner.videoCodecInitialized = true;
                        owner.updateRuntimeVideoMode(configuredFormat, "codec-initialized");
                    }
                });
            }
        }

        @Override
        protected void onOutputFormatChanged(Format format, MediaFormat mediaFormat)
                {
            super.onOutputFormatChanged(format, mediaFormat);
            if (owner != null) {
                owner.onCodecOutputFormatChanged(format, mediaFormat);
            }
        }

        // Set when the current stream is a Dolby Vision track being decoded by the HEVC decoder
        // in HDR10-compat mode. Only then do we scan and strip DV RPU NAL units from the bitstream.
        private boolean stripDolbyVisionRpu;
        private final DolbyVisionRpuStripper rpuStripper = new DolbyVisionRpuStripper();

        @Override
        protected boolean codecNeedsSetOutputSurfaceWorkaround(String decoderName) {
            return SystemCodecSurfacePolicy.requiresSetOutputSurfaceWorkaround(decoderName)
                    || super.codecNeedsSetOutputSurfaceWorkaround(decoderName);
        }

        @Override
        protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
            // Some SoC HEVC decoders (e.g. Huawei/HiSilicon, MediaTek NVT) go down a Dolby Vision
            // decode path — and output a black picture — the moment they see a DV RPU SEI NAL in
            // the bitstream, even though we asked for video/hevc and the base layer is plain
            // HDR10. Kodi solves this with SetRemoveDovi: strip the DV RPU NAL units so the codec
            // sees a clean HDR10 HEVC stream. We do the same here for DV Profile 8 base-layer
            // playback. Plain HEVC streams never contain these NAL types, so this is a no-op there.
            if (stripDolbyVisionRpu && buffer != null && buffer.data != null) {
                try {
                    stripDolbyVisionRpuNalUnits(buffer.data);
                } catch (Throwable ignored) {
                    // Never let bitstream editing crash playback; fall through with original data.
                }
            }
            super.onQueueInputBuffer(buffer);
        }

        @Override
        protected MediaFormat getMediaFormat(Format format,
                                             String codecMimeType,
                                             CodecMaxValues codecMaxValues,
                                             float codecOperatingRate,
                                             boolean deviceNeedsNoPostProcessWorkaround,
                                             int tunnelingAudioSessionId) {
            // MediaCodec receives the actual source transfer (PQ/HLG), primaries, range and
            // mastering metadata before configuration. No invented 1000-nit HDR descriptor
            // and no blanket PQ override are applied to HLG or Dolby Vision Profile 5.
            // getMediaCodecConfiguration has already selected/restored native DV or its base.
            // Re-applying source DV MIME here would turn the selected HEVC base back into DV.
            return super.getMediaFormat(format, codecMimeType, codecMaxValues,
                    codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId);
        }
        // Strips Dolby Vision RPU (NAL unit type 62) and EL (type 63) NAL units from an HEVC
        // Annex-B bitstream. The HDR10/HLG base layer is left untouched, so a decoder that
        // black-screens on seeing DV NAL units gets a clean HDR10 HEVC stream. This is the
        // ExoPlayer equivalent of Kodi's CBitstreamConverter::SetRemoveDovi.
        //
        // The reusable filter performs a byte-exact single pass and reuses its largest scratch
        // array. This keeps the proven base-layer rewrite without allocating several megabytes
        // for every 4K access unit on the ExoPlayer playback thread.
        private void stripDolbyVisionRpuNalUnits(ByteBuffer data) {
            int inputBytes = data.remaining();
            int removed = rpuStripper.stripInPlace(data);
            if (removed > 0 && !dvStripLogged) {
                dvStripLogged = true;
                int outputBytes = data.remaining();
            }
        }

        private boolean dvStripLogged;
    }

    private static final class DeviceCodecSelector implements MediaCodecSelector {
        static final DeviceCodecSelector INSTANCE = new DeviceCodecSelector();

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                   boolean requiresSecureDecoder,
                                                   boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (MimeTypes.isAudio(mimeType)) {
                ArrayList<MediaCodecInfo> ordered = new ArrayList<>(decoders.size());
                for (MediaCodecInfo decoder : decoders) {
                    if (decoder != null
                            && SystemDecoderRoutePolicy.platformAudioDecoderPriority(
                            Build.VERSION.SDK_INT,
                            decoder.name,
                            decoder.hardwareAccelerated,
                            decoder.softwareOnly) == 0) {
                        ordered.add(decoder);
                    }
                }
                for (MediaCodecInfo decoder : decoders) {
                    if (decoder != null
                            && SystemDecoderRoutePolicy.platformAudioDecoderPriority(
                            Build.VERSION.SDK_INT,
                            decoder.name,
                            decoder.hardwareAccelerated,
                            decoder.softwareOnly) != 0) {
                        ordered.add(decoder);
                    }
                }
                return ordered;
            }
            if (!MimeTypes.isVideo(mimeType)) {
                return decoders;
            }
            ArrayList<MediaCodecInfo> hardware = new ArrayList<>();
            for (MediaCodecInfo decoder : decoders) {
                if (decoder != null
                        && SystemDecoderRoutePolicy.isEligibleHdrVideoCodec(
                        Build.VERSION.SDK_INT,
                        decoder.name,
                        decoder.hardwareAccelerated,
                        decoder.softwareOnly)) {
                    hardware.add(decoder);
                }
            }
            return hardware;
        }
    }
}
