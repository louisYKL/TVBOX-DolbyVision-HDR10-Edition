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
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlaybackUrlNormalizer;
import com.github.tvbox.osc.util.SystemDecoderRoutePolicy;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
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
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.ColorInfo;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * Kodi-style Android pipeline: one ExoPlayer clock/demuxer, device MediaCodec video and audio,
 * FFmpeg audio fallback, and stereo PCM AudioTrack output.
 */
public final class SystemCodecPlayer extends AbstractPlayer implements CompatTrackSelectorPlayer {
    private static final String TAG = "SystemCodecPlayer";
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<Integer, TrackSelectionTarget> audioTargets = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, TrackSelectionTarget> subtitleTargets = new LinkedHashMap<>();

    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private DefaultHttpDataSource.Factory httpFactory;
    private LoopbackRangeDataSource.Factory loopbackRangeFactory;
    private DefaultBandwidthMeter bandwidthMeter;
    private RealtimeNetworkSpeedMeter networkSpeedMeter;
    private DefaultAllocator allocator;
    private Surface surface;
    private SurfaceHolder surfaceHolder;
    private Surface boundSurface;
    private String dataSource;
    private Map<String, String> requestHeaders = Collections.emptyMap();
    private boolean prepared;
    private boolean released;
    private boolean firstFrameRendered;
    private boolean renderingStartNotified;
    private boolean selectedVideoTrackPresent;
    private boolean selectedAudioTrackPresent;
    private boolean bufferingNotified;
    private boolean startRequested;
    private long firstFramePositionMs = C.TIME_UNSET;
    private long lastObservedPositionMs = C.TIME_UNSET;
    private int renderingStartPollCount;
    private static final long RENDERING_START_POLL_MS = 250L;
    private static final int MAX_RENDERING_START_POLLS = 240;
    private boolean passthroughVolumeLocked;
    private long pendingInitialSeekMs = C.TIME_UNSET;
    private long pendingSeekCompleteMs = C.TIME_UNSET;
    private int seekGeneration;
    private int bufferProgressTargetBytes;
    private int bufferingStartAllocatedBytes;
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
    // 6677 serves chunked Range entities from a remote drive. A 15-second inactivity cutoff
    // aborts valid slow windows before the next bytes arrive and presents as a permanent 0 B/s
    // source. The outer buffering watchdog still bounds a genuinely dead playback attempt.
    private static final int HTTP_READ_TIMEOUT_MS = 60_000;
    private static final String HTTP_USER_AGENT = "TVBox-SystemCodec/0.2.7";
    private TrackInfo lastTrackInfo = new TrackInfo();
    private SubtitleTextListener subtitleTextListener;
    private BitmapSubtitleCueListener bitmapSubtitleCueListener;
    private RuntimeVideoModeListener runtimeVideoModeListener;
    private boolean lastRuntimeHdr;
    private boolean lastRuntimeDolbyVision;
    private boolean nativeDolbyVisionCapable;
    private String lastRuntimeOutputMode = "sdr";
    private String lastRuntimeReason = "init";

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
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(appContext, loopbackRangeFactory)
                .setTransferListener(networkSpeedMeter);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

        ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClassMb = activityManager == null ? 256 : activityManager.getMemoryClass();
        int targetBufferBytes = SystemCodecBufferPolicy.targetBufferBytes(memoryClassMb);
        allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        bufferProgressTargetBytes = SystemCodecBufferPolicy.bufferProgressTargetBytes(memoryClassMb);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                        SystemCodecBufferPolicy.MIN_BUFFER_MS,
                        SystemCodecBufferPolicy.MAX_BUFFER_MS,
                        SystemCodecBufferPolicy.PLAYBACK_BUFFER_MS,
                        SystemCodecBufferPolicy.REBUFFER_MS)
                .setTargetBufferBytes(targetBufferBytes)
                // A track-sized byte target (the default is roughly 13 MB) is too small for a
                // high-bitrate 50+ GB remux. With the byte-priority mode ExoPlayer stops reading
                // as soon as that target is reached, even when the required 20-second time
                // runway has not been filled, which causes the recurring "plays, then buffers"
                // stall. The 50-second MAX_BUFFER_MS remains the hard time/memory bound.
                .setPrioritizeTimeOverSizeThresholds(
                        SystemCodecBufferPolicy.PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS)
                .setBackBuffer(SystemCodecBufferPolicy.BACK_BUFFER_MS, true)
                .build();

        try {
            nativeDolbyVisionCapable = com.github.tvbox.osc.util.HdrDeviceSupport
                    .query(appContext).supportsNativeDolbyVision();
        } catch (Throwable ignored) {
            nativeDolbyVisionCapable = false;
        }
        SystemCodecRenderersFactory renderersFactory = new SystemCodecRenderersFactory(
                appContext, nativeDolbyVisionCapable);
        player = new SimpleExoPlayer.Builder(appContext, renderersFactory)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                // PREVIOUS_SYNC (not CLOSEST_SYNC): resuming to a saved position seeks to the
                // keyframe at/before the target instead of the nearest one. On a network-drive
                // proxy serving a huge REMUX, this needs less forward reading from a random byte
                // offset, so the resume seek recovers faster instead of stalling at low buffer.
                .setSeekParameters(SeekParameters.PREVIOUS_SYNC)
                .setReleaseTimeoutMs(750L)
                .setDetachSurfaceTimeoutMs(750L)
                .build();
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
            public void onPlaybackStateChanged(int state) {
                logPlaybackState("state", state);
                handlePlaybackStateChanged(state);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                logPlaybackState("is-playing=" + isPlaying,
                        player == null ? Player.STATE_IDLE : player.getPlaybackState());
                if (!released && isPlaying) {
                    maybeNotifyRenderingStart("is-playing");
                    if (renderingStartNotified) {
                        notifyBufferingEnd();
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                log("echo-system-codec error code=" + error.errorCode
                        + " message=" + error.getMessage()
                        + " cause=" + describeThrowable(error.getCause()));
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
        passthroughVolumeLocked = hasHeaderValue(parsed.headers, "X-TVBox-Probe-AudioPassthrough", "1");
        requestHeaders = sanitizeRequestHeaders(parsed.headers);
        // A new source gets a fresh transient-error budget. Retry prepares do not call
        // setDataSource, so they keep the same bounded budget for this source session.
        sourceRetryCount = 0;
        resetPlaybackFlags();
        enforceExternalPcmVolume();
        log("echo-system-codec source network=" + isNetworkSource(dataSource)
                + " headers=" + requestHeaders.size()
                + " passthroughVolume=" + passthroughVolumeLocked);
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
        player.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        startRequested = false;
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
            startRequested = true;
            httpFactory.setDefaultRequestProperties(requestHeaders);
            loopbackRangeFactory.setDefaultRequestHeaders(requestHeaders);
            // Let ExoPlayer's LoadControl own the startup gate. Keeping playWhenReady=false
            // makes a paused player report READY before the configured playback buffer is met,
            // which deadlocks an additional application-level buffer gate on some TV firmware.
            player.setPlayWhenReady(true);
            player.clearMediaItems();
            MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(Uri.parse(dataSource));
            String mimeType = resolveMediaItemMimeType(dataSource);
            if (!TextUtils.isEmpty(mimeType)) {
                mediaItemBuilder.setMimeType(mimeType);
            }
            pendingInitialSeekMs = C.TIME_UNSET;
            if (initialPositionMs > 0L) {
                player.setMediaItem(mediaItemBuilder.build(), initialPositionMs);
            } else {
                player.setMediaItem(mediaItemBuilder.build());
            }
            player.prepare();
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
    public void seekTo(long time) {
        if (player == null || released) {
            return;
        }
        long safeTime = Math.max(0L, time);
        if (!prepared) {
            pendingInitialSeekMs = safeTime;
            return;
        }
        pendingSeekCompleteMs = safeTime;
        int generation = ++seekGeneration;
        notifyBufferingStart();
        player.seekTo(safeTime);
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
    public void release() {
        released = true;
        if (player != null) {
            try {
                clearBoundVideoOutput();
            } catch (Throwable ignored) {
            }
            player.release();
            player = null;
        }
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
        return player == null ? 0L : Math.max(0L, player.getCurrentPosition());
    }

    @Override
    public long getDuration() {
        if (player == null) {
            return 0L;
        }
        long duration = player.getDuration();
        return duration == C.TIME_UNSET ? 0L : Math.max(0L, duration);
    }

    @Override
    public int getBufferedPercentage() {
        SimpleExoPlayer current = player;
        if (current == null) {
            return 0;
        }
        if (current.getPlaybackState() == Player.STATE_BUFFERING) {
            long bufferedDurationMs = Math.max(0L, current.getBufferedPosition()
                    - current.getCurrentPosition());
            long allocatedDeltaBytes = allocator == null ? 0L
                    : Math.max(0L, (long) allocator.getTotalBytesAllocated()
                    - bufferingStartAllocatedBytes);
            // The direct 6677 DataSource commits bytes into its Range window before ExoPlayer's
            // allocator accounts for the corresponding load chunk. Include that
            // real forward cache so a healthy source does not display "0%" while its body is
            // already available to the decoder. This is a data-source snapshot, never a speed
            // or elapsed-time estimate, and the normal HTTP path remains unchanged.
            long committedRangeBytes = loopbackRangeFactory == null
                    ? 0L : loopbackRangeFactory.getBufferedAheadBytes();
            long bufferedBytes = Math.max(allocatedDeltaBytes, committedRangeBytes);
            int requiredBufferMs = firstFrameRendered
                    ? SystemCodecBufferPolicy.REBUFFER_MS
                    : SystemCodecBufferPolicy.PLAYBACK_BUFFER_MS;
            return SystemCodecBufferPolicy.bufferingProgressPercent(
                    bufferedDurationMs,
                    requiredBufferMs,
                    bufferedBytes,
                    bufferProgressTargetBytes,
                    false);
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
            notifyBufferingStart();
            return;
        }
        if (state == Player.STATE_READY) {
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
                || player == null || player.getPlaybackState() != Player.STATE_READY) {
            return;
        }
        long completedPosition = Math.max(0L, player.getCurrentPosition());
        pendingSeekCompleteMs = C.TIME_UNSET;
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

    private String stateName(int state) {
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
        if (tracks != null) {
            int audioId = 0;
            int subtitleId = 1000;
            for (Tracks.Group group : tracks.getGroups()) {
                TrackGroup mediaGroup = group.getMediaTrackGroup();
                int trackType = group.getType();
                for (int i = 0; i < mediaGroup.length; i++) {
                    Format format = mediaGroup.getFormat(i);
                    boolean selected = group.isTrackSelected(i);
                    if (trackType == C.TRACK_TYPE_AUDIO) {
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
        List<String> lines = new ArrayList<>();
        List<Cue> bitmapCues = new ArrayList<>();
        if (cues != null) {
            for (Cue cue : cues) {
                if (cue != null && cue.text != null && cue.text.length() > 0) {
                    lines.add(cue.text.toString());
                }
                if (cue != null && cue.bitmap != null) {
                    bitmapCues.add(cue);
                }
            }
        }
        final String text = TextUtils.join("\n", lines);
        final List<Cue> immutableBitmapCues = bitmapCues.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(bitmapCues));
        runOnMain(() -> {
            if (!released && subtitleTextListener != null) {
                subtitleTextListener.onSubtitleText(text);
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
        String sampleMimeType = nullToEmpty(format.sampleMimeType).toLowerCase(Locale.US);
        String codecs = nullToEmpty(format.codecs).toLowerCase(Locale.US);
        ColorInfo colorInfo = format.colorInfo;
        boolean dolbyVision = "video/dolby-vision".equals(sampleMimeType)
                || codecs.contains("dvhe") || codecs.contains("dvh1") || codecs.contains("dovi");
        boolean hdrTransfer = colorInfo != null
                && (colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                || colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG);
        boolean hdrColorspace = colorInfo != null && colorInfo.colorSpace == C.COLOR_SPACE_BT2020;
        boolean hdrStaticInfo = colorInfo != null
                && colorInfo.hdrStaticInfo != null && colorInfo.hdrStaticInfo.length > 0;
        lastRuntimeDolbyVision = dolbyVision;
        lastRuntimeHdr = dolbyVision || hdrTransfer || hdrColorspace || hdrStaticInfo;
        lastRuntimeOutputMode = dolbyVision
                ? (nativeDolbyVisionCapable ? "native-dv" : "dv-base-hdr")
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
                + " -> dv=" + dolbyVision + " hdr=" + lastRuntimeHdr + " mode=" + lastRuntimeOutputMode);
        dispatchRuntimeVideoModeIfNeeded(reason);
    }

    private void dispatchRuntimeVideoModeIfNeeded(String reason) {
        if (runtimeVideoModeListener == null || (!lastRuntimeHdr && !lastRuntimeDolbyVision)) {
            return;
        }
        final String callbackReason = TextUtils.isEmpty(reason) ? lastRuntimeReason : reason;
        runOnMain(() -> {
            if (!released && runtimeVideoModeListener != null) {
                runtimeVideoModeListener.onRuntimeVideoMode(lastRuntimeHdr, lastRuntimeDolbyVision,
                        lastRuntimeOutputMode, callbackReason);
            }
        });
    }

    private void resetPlaybackFlags() {
        prepared = false;
        firstFrameRendered = false;
        renderingStartNotified = false;
        selectedVideoTrackPresent = false;
        selectedAudioTrackPresent = false;
        bufferingNotified = false;
        startRequested = false;
        firstFramePositionMs = C.TIME_UNSET;
        lastObservedPositionMs = C.TIME_UNSET;
        renderingStartPollCount = 0;
        pendingInitialSeekMs = C.TIME_UNSET;
        pendingSeekCompleteMs = C.TIME_UNSET;
        seekGeneration++;
        bufferingStartAllocatedBytes = allocator == null ? 0 : allocator.getTotalBytesAllocated();
        audioTargets.clear();
        subtitleTargets.clear();
        lastTrackInfo = new TrackInfo();
        lastRuntimeHdr = false;
        lastRuntimeDolbyVision = false;
        lastRuntimeOutputMode = "sdr";
        lastRuntimeReason = "init";
    }

    private void notifyBufferingStart() {
        if (bufferingNotified || released) {
            return;
        }
        bufferingNotified = true;
        bufferingStartAllocatedBytes = allocator == null ? 0 : allocator.getTotalBytesAllocated();
        notifyInfo(MEDIA_INFO_BUFFERING_START, 0);
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
        int code = error.errorCode;
        int attempt = SystemCodecRetryPolicy.nextRetryAttempt(sourceRetryCount, code);
        if (attempt < 0) {
            return false;
        }
        sourceRetryCount = attempt;
        final long resumeMs = Math.max(0L, player.getCurrentPosition());
        log("echo-system-codec source-retry attempt=" + attempt + "/"
                + SystemCodecRetryPolicy.MAX_SOURCE_RETRIES
                + " resumeMs=" + resumeMs + " code=" + code);
        notifyBufferingStart();
        mainHandler.postDelayed(() -> {
            if (released || player == null) {
                return;
            }
            try {
                resetPlaybackFlagsForRetry();
                player.setPlayWhenReady(true);
                player.clearMediaItems();
                MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(Uri.parse(dataSource));
                String mimeType = resolveMediaItemMimeType(dataSource);
                if (!TextUtils.isEmpty(mimeType)) {
                    mediaItemBuilder.setMimeType(mimeType);
                }
                player.setMediaItem(mediaItemBuilder.build(), resumeMs);
                player.prepare();
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

    /** Like resetPlaybackFlags but preserves the source-retry counter across a retry prepare. */
    private void resetPlaybackFlagsForRetry() {
        prepared = false;
        firstFrameRendered = false;
        renderingStartNotified = false;
        selectedVideoTrackPresent = false;
        selectedAudioTrackPresent = false;
        pendingInitialSeekMs = C.TIME_UNSET;
        pendingSeekCompleteMs = C.TIME_UNSET;
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
        Log.i(TAG, message);
        LOG.i(message);
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
        // True only when the device can natively decode AND display Dolby Vision end to end. On
        // such devices we leave DV completely alone (native DV decode). On every other TV/box we
        // decode the DV HDR10/HLG base layer through the HEVC/AVC decoder and re-inject HDR
        // metadata + strip DV RPU, which is what makes DV files play on non-DV hardware.
        private final boolean nativeDvCapable;

        SystemCodecRenderersFactory(Context context, boolean nativeDvCapable) {
            super(context);
            this.context = context.getApplicationContext();
            this.nativeDvCapable = nativeDvCapable;
            Log.i(TAG, "echo-system-codec dv-route nativeDvCapable=" + nativeDvCapable);
            LOG.i("echo-system-codec dv-route nativeDvCapable=" + nativeDvCapable);
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
            Log.i(TAG, capsLog);
            LOG.i(capsLog);

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
                            eventHandler, eventListener, !nativeDvCapable));
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
                Log.i(TAG, message);
                LOG.i(message);
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
        // True when the device can NOT natively decode+display Dolby Vision, so every DV profile
        // must be played through its HDR10/HLG base layer on the HEVC decoder. When the device
        // has native DV support this stays false and DV streams are handed to the DV decoder
        // untouched.
        private final boolean hdr10CompatMode;

        SafeSurfaceMediaCodecVideoRenderer(Context context,
                                           MediaCodecAdapter.Factory codecAdapterFactory,
                                           MediaCodecSelector mediaCodecSelector,
                                           long allowedVideoJoiningTimeMs,
                                           boolean enableDecoderFallback,
                                           Handler eventHandler,
                                           VideoRendererEventListener eventListener,
                                           boolean hdr10CompatMode) {
            super(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                    enableDecoderFallback, eventHandler, eventListener, 50);
            this.hdr10CompatMode = hdr10CompatMode;
        }

        // Set when the current stream is a Dolby Vision track being decoded by the HEVC decoder
        // in HDR10-compat mode. Only then do we scan and strip DV RPU NAL units from the bitstream.
        private boolean stripDolbyVisionRpu;
        private final DolbyVisionRpuStripper rpuStripper = new DolbyVisionRpuStripper();

        @Override
        protected void onStreamChanged(Format[] formats, long startPositionUs, long offsetUs)
                throws ExoPlaybackException {
            super.onStreamChanged(formats, startPositionUs, offsetUs);
            // Decide once per stream whether to strip DV RPU: only when this device has no native
            // DV support (hdr10CompatMode) AND the incoming track is Dolby Vision. A device with
            // native DV support leaves the stream untouched for the DV decoder.
            boolean anyDolbyVision = false;
            if (formats != null) {
                for (Format f : formats) {
                    if (f != null && MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType)) {
                        anyDolbyVision = true;
                        break;
                    }
                }
            }
            // Strip DV RPU only when this device has no native DV support AND the stream is DV.
            // The 15:16 build with stripping ON produced picture+sound+HDR (just stuttery); with
            // stripping OFF the picture disappeared, so RPU stripping is REQUIRED here for the
            // NVT/Novatek HEVC decoder to emit a base-layer picture. Stutter is addressed
            // separately (zero-alloc single-pass compaction below), not by disabling stripping.
            stripDolbyVisionRpu = hdr10CompatMode && anyDolbyVision;
            Log.i(TAG, "echo-system-codec dv-strip-rpu=" + stripDolbyVisionRpu
                    + " hdr10CompatMode=" + hdr10CompatMode + " dvStream=" + anyDolbyVision);
            LOG.i("echo-system-codec dv-strip-rpu=" + stripDolbyVisionRpu
                    + " hdr10CompatMode=" + hdr10CompatMode + " dvStream=" + anyDolbyVision);
        }

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
            MediaFormat mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxValues,
                    codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId);
            // Dolby Vision Profile 8 (and 8.1) carries an HDR10 / HLG base layer inside an HEVC
            // stream. On a device with no single-layer DV decoder, ExoPlayer falls back to the
            // HEVC decoder (video/hevc) to decode that base layer — but the DV track's Format
            // usually has no ColorInfo, so the MediaFormat handed to the codec omits the HDR
            // color transfer/standard. The decoder then emits 10-bit samples with an unknown
            // color config into an HDR display window, which shows as a black picture. Kodi
            // handles this by playing the HDR10 base layer with the correct color metadata.
            // Re-inject the standard HDR10 color config so the HEVC decoder outputs a valid
            // BT.2020 / PQ (ST2084) picture. HLG-based DV keeps its transfer if already present.
            if (hdr10CompatMode
                    && MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                    && MimeTypes.VIDEO_H265.equals(codecMimeType)) {
                if (!mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER,
                            MediaFormat.COLOR_TRANSFER_ST2084);
                }
                if (!mediaFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                            MediaFormat.COLOR_STANDARD_BT2020);
                }
                if (!mediaFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                            MediaFormat.COLOR_RANGE_LIMITED);
                }
                // Some SoC decoders (Novatek/NVT, HiSilicon) refuse to output a picture for a
                // 10-bit HDR stream unless the mastering-display static metadata is present too.
                // The DV track carries no ColorInfo, so synthesize a standard HDR10 blob
                // (BT.2020 primaries, D65 white point, 1000-nit peak) — Kodi does the same when
                // it cannot extract mdcv/clli from the stream.
                if (!mediaFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                    mediaFormat.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO,
                            buildDefaultHdr10StaticInfo());
                }
                Log.i(TAG, "echo-system-codec dv-profile8-hdr10-baselayer codecMime="
                        + codecMimeType + " injected BT2020/ST2084+staticInfo");
                LOG.i("echo-system-codec dv-profile8-hdr10-baselayer codecMime="
                        + codecMimeType + " injected BT2020/ST2084+staticInfo");
            }
            return mediaFormat;
        }

        // Builds a 25-byte CTA-861.3 HDR static metadata descriptor for a generic HDR10 master
        // (BT.2020 primaries, D65 white point, 1000-nit peak, 0.0001-nit floor, 1000 MaxCLL /
        // 400 MaxFALL). Layout matches Android's MediaFormat.KEY_HDR_STATIC_INFO expectation:
        // byte[0]=0 type, then little-endian uint16 fields.
        private static ByteBuffer buildDefaultHdr10StaticInfo() {
            ByteBuffer buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 0); // descriptor id / type
            // Display primaries, scaled by 50000. Order R, G, B (x,y each).
            buffer.putShort((short) (0.708f * 50000)); // R x = 35400
            buffer.putShort((short) (0.292f * 50000)); // R y = 14600
            buffer.putShort((short) (0.170f * 50000)); // G x = 8500
            buffer.putShort((short) (0.797f * 50000)); // G y = 39850
            buffer.putShort((short) (0.131f * 50000)); // B x = 6550
            buffer.putShort((short) (0.046f * 50000)); // B y = 2300
            buffer.putShort((short) (0.3127f * 50000)); // White x = 15635
            buffer.putShort((short) (0.3290f * 50000)); // White y = 16450
            buffer.putShort((short) 1000);  // max display mastering luminance (nit)
            buffer.putShort((short) 1);      // min display mastering luminance (x0.0001 nit → 0.0001)
            buffer.putShort((short) 1000);  // MaxCLL (nit)
            buffer.putShort((short) 400);   // MaxFALL (nit)
            buffer.rewind();
            return buffer;
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
                Log.i(TAG, "echo-system-codec dv-strip removed=" + removed
                        + " bytes " + inputBytes + "->" + outputBytes
                        + " scratchCapacity=" + rpuStripper.scratchCapacity());
                LOG.i("echo-system-codec dv-strip removed=" + removed
                        + " bytes " + inputBytes + "->" + outputBytes);
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
                        && SystemDecoderRoutePolicy.isLikelyHardwareCodec(
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
