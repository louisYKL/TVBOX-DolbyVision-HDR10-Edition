package com.github.tvbox.osc.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlaybackUrlNormalizer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.ColorInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;

public class Java64CodecPlayer extends AbstractPlayer implements CompatTrackSelectorPlayer, RuntimeVideoModeAwarePlayer {
    private static final String TAG = "Java64CodecPlayer";

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<Integer, TrackSelectionTarget> audioTargets = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, TrackSelectionTarget> subtitleTargets = new LinkedHashMap<>();

    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private DefaultHttpDataSource.Factory httpFactory;
    private Surface surface;
    private SurfaceHolder surfaceHolder;
    private String dataSource;
    private Map<String, String> requestHeaders = Collections.emptyMap();
    private boolean prepared;
    private boolean released;
    private long pendingSeekMs = C.TIME_UNSET;
    private long lastSpeedBytesPerSecond;
    private boolean firstFrameRendered;
    private TrackInfo lastTrackInfo = new TrackInfo();
    private CompatTrackSelectorPlayer.SubtitleTextListener subtitleTextListener;
    private MPVCompatPlayer.OnRuntimeVideoModeListener runtimeVideoModeListener;
    private boolean lastRuntimeHdr;
    private boolean lastRuntimeDolbyVision;
    private String lastRuntimeOutputMode = "sdr";
    private String lastRuntimeReason = "init";

    public Java64CodecPlayer(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void initPlayer() {
        released = false;
        prepared = false;
        firstFrameRendered = false;
        pendingSeekMs = C.TIME_UNSET;
        lastSpeedBytesPerSecond = 0L;
        audioTargets.clear();
        subtitleTargets.clear();
        lastTrackInfo = new TrackInfo();
        lastRuntimeHdr = false;
        lastRuntimeDolbyVision = false;
        lastRuntimeOutputMode = "sdr";
        lastRuntimeReason = "init";

        trackSelector = new DefaultTrackSelector(appContext);
        httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(requestHeaders);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(appContext, httpFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);
        player = new SimpleExoPlayer.Builder(appContext)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), false);
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                log("echo-java64-codec state state=" + playbackStateName(state)
                        + " playWhenReady=" + (player != null && player.getPlayWhenReady())
                        + " isPlaying=" + (player != null && player.isPlaying())
                        + " prepared=" + prepared
                        + " firstFrame=" + firstFrameRendered);
                handlePlaybackStateChanged(state);
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                log("echo-java64-codec play-when-ready ready=" + playWhenReady
                        + " reason=" + reason
                        + " state=" + currentPlaybackStateName()
                        + " isPlaying=" + (player != null && player.isPlaying()));
                if (!released && player != null && playWhenReady && player.getPlaybackState() == Player.STATE_READY) {
                    notifyInfo(MEDIA_INFO_RENDERING_START, 0);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                log("echo-java64-codec is-playing " + isPlaying
                        + " state=" + currentPlaybackStateName()
                        + " playWhenReady=" + (player != null && player.getPlayWhenReady()));
                if (!released && isPlaying) {
                    notifyInfo(MEDIA_INFO_RENDERING_START, 0);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                log("echo-java64-codec error code=" + error.errorCode
                        + " msg=" + error.getMessage()
                        + " cause=" + describeThrowable(error.getCause()));
                notifyError();
            }

            @Override
            public void onVideoSizeChanged(com.google.android.exoplayer2.video.VideoSize videoSize) {
                notifyVideoSizeChanged(videoSize.width, videoSize.height);
            }

            @Override
            public void onRenderedFirstFrame() {
                firstFrameRendered = true;
                log("echo-java64-codec first-frame");
                dispatchRuntimeVideoModeIfNeeded("first-frame");
                notifyInfo(MEDIA_INFO_RENDERING_START, 0);
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                rebuildTrackInfo(tracks);
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                notifyInfo(isLoading ? MEDIA_INFO_BUFFERING_START : MEDIA_INFO_BUFFERING_END, 0);
            }

            @Override
            public void onCues(CueGroup cueGroup) {
                dispatchSubtitleText(cueGroup == null ? null : cueGroup.cues);
            }
        });
        bindSurface();
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        PlaybackUrlNormalizer.UrlWithHeaders parsed = PlaybackUrlNormalizer.splitUrlAndHeaders(path, headers);
        dataSource = PlaybackUrlNormalizer.normalizeHttpUrl(parsed.url);
        requestHeaders = parsed.headers == null ? Collections.emptyMap() : new HashMap<>(parsed.headers);
        prepared = false;
        firstFrameRendered = false;
        pendingSeekMs = C.TIME_UNSET;
        audioTargets.clear();
        subtitleTargets.clear();
        lastTrackInfo = new TrackInfo();
        log("echo-java64-codec data-source url=" + dataSource + " headers=" + requestHeaders.size());
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        throw new UnsupportedOperationException("Java64CodecPlayer does not support AssetFileDescriptor");
    }

    @Override
    public void start() {
        if (player == null || released) {
            return;
        }
        log("echo-java64-codec start state=" + currentPlaybackStateName()
                + " prepared=" + prepared
                + " firstFrame=" + firstFrameRendered
                + " playWhenReadyBefore=" + player.getPlayWhenReady()
                + " isPlayingBefore=" + player.isPlaying());
        player.setPlayWhenReady(true);
        if (player.getPlaybackState() == Player.STATE_READY) {
            notifyInfo(MEDIA_INFO_RENDERING_START, 0);
        }
    }

    @Override
    public void pause() {
        if (player == null || released) {
            return;
        }
        log("echo-java64-codec pause state=" + currentPlaybackStateName()
                + " prepared=" + prepared
                + " firstFrame=" + firstFrameRendered
                + " playWhenReadyBefore=" + player.getPlayWhenReady()
                + " isPlayingBefore=" + player.isPlaying());
        player.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (player == null || released) {
            return;
        }
        player.stop();
        prepared = false;
    }

    @Override
    public void prepareAsync() {
        if (player == null || released || TextUtils.isEmpty(dataSource)) {
            notifyError();
            return;
        }
        try {
            if (httpFactory != null) {
                httpFactory.setDefaultRequestProperties(requestHeaders);
            }
            player.clearMediaItems();
            MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                    .setUri(Uri.parse(dataSource));
            String mimeType = resolveMediaItemMimeType(dataSource);
            if (!TextUtils.isEmpty(mimeType)) {
                mediaItemBuilder.setMimeType(mimeType);
            }
            player.setMediaItem(mediaItemBuilder.build());
            player.prepare();
            log("echo-java64-codec prepare url=" + dataSource + " mime=" + (TextUtils.isEmpty(mimeType) ? "auto" : mimeType));
        } catch (Throwable th) {
            log("echo-java64-codec prepare failed " + th.getMessage());
            notifyError();
        }
    }

    @Override
    public void reset() {
        if (player == null || released) {
            return;
        }
        player.stop();
        player.clearMediaItems();
        prepared = false;
        firstFrameRendered = false;
        pendingSeekMs = C.TIME_UNSET;
        audioTargets.clear();
        subtitleTargets.clear();
        lastTrackInfo = new TrackInfo();
    }

    @Override
    public boolean isPlaying() {
        boolean playing = player != null && player.isPlaying();
        if (player != null) {
            log("echo-java64-codec query-is-playing result=" + playing
                    + " state=" + currentPlaybackStateName()
                    + " playWhenReady=" + player.getPlayWhenReady());
        }
        return playing;
    }

    @Override
    public void seekTo(long time) {
        if (player == null || released) {
            return;
        }
        long safeTime = Math.max(0L, time);
        if (!prepared) {
            pendingSeekMs = safeTime;
            log("echo-java64-codec delay-seek pos=" + safeTime);
            return;
        }
        player.seekTo(safeTime);
        log("echo-java64-codec seek pos=" + safeTime);
    }

    @Override
    public void release() {
        released = true;
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
        trackSelector = null;
        httpFactory = null;
        surface = null;
        surfaceHolder = null;
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
        return player == null ? 0 : Math.max(0, player.getBufferedPercentage());
    }

    @Override
    public void setSurface(Surface surface) {
        this.surface = surface;
        this.surfaceHolder = null;
        bindSurface();
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        this.surfaceHolder = holder;
        this.surface = holder == null ? null : holder.getSurface();
        bindSurface();
    }

    @Override
    public void setVolume(float v1, float v2) {
        if (player != null) {
            player.setVolume(Math.max(0f, Math.min(1f, (v1 + v2) / 2f)));
        }
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
        return lastSpeedBytesPerSecond;
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
    public void setOnSubtitleTextListener(@Nullable CompatTrackSelectorPlayer.SubtitleTextListener listener) {
        subtitleTextListener = listener;
    }

    @Override
    public void setOnRuntimeVideoModeListener(@Nullable MPVCompatPlayer.OnRuntimeVideoModeListener listener) {
        runtimeVideoModeListener = listener;
        dispatchRuntimeVideoModeIfNeeded("listener-bind");
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
        rebuildTrackInfo(player == null ? null : player.getCurrentTracks());
    }

    @Override
    public boolean shouldDelaySubtitleSelection(int attempt) {
        return shouldDelaySubtitleSelection(0, attempt);
    }

    @Override
    public boolean shouldDelaySubtitleSelection(int currentSubtitleCount, int attempt) {
        return !prepared && attempt < 6;
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
            if (target != null && target.group != null && target.trackIndex >= 0) {
                builder.addOverride(new TrackSelectionOverride(target.group, target.trackIndex));
                log("echo-java64-codec select-track type=" + trackType + " id=" + track.trackId + " name=" + track.name);
            }
        } else if (disableWhenNull) {
            builder.setTrackTypeDisabled(trackType, true);
        }
        trackSelector.setParameters(builder.build());
        rebuildTrackInfo(player == null ? null : player.getCurrentTracks());
    }

    private void handlePlaybackStateChanged(int state) {
        if (released) {
            return;
        }
        switch (state) {
            case Player.STATE_BUFFERING:
                notifyInfo(MEDIA_INFO_BUFFERING_START, 0);
                break;
            case Player.STATE_READY:
                if (!prepared) {
                    prepared = true;
                    notifyPrepared();
                    if (pendingSeekMs != C.TIME_UNSET && player != null) {
                        long target = pendingSeekMs;
                        pendingSeekMs = C.TIME_UNSET;
                        player.seekTo(target);
                        log("echo-java64-codec apply-delay-seek pos=" + target);
                    }
                    if (player != null && !player.getPlayWhenReady()) {
                        log("echo-java64-codec auto-start prepared");
                        player.setPlayWhenReady(true);
                    }
                }
                dispatchRuntimeVideoModeIfNeeded("state-ready");
                notifyInfo(MEDIA_INFO_BUFFERING_END, 0);
                if (player != null && player.getPlayWhenReady() && firstFrameRendered) {
                    notifyInfo(MEDIA_INFO_RENDERING_START, 0);
                }
                break;
            case Player.STATE_ENDED:
                notifyCompletion();
                break;
            default:
                break;
        }
    }

    private void bindSurface() {
        if (player == null) {
            return;
        }
        if (surfaceHolder != null) {
            Surface holderSurface = surfaceHolder.getSurface();
            if (holderSurface != null && holderSurface.isValid()) {
                player.setVideoSurfaceHolder(surfaceHolder);
                log("echo-java64-codec holder-bound");
                return;
            }
        }
        if (surface != null && surface.isValid()) {
            player.setVideoSurface(surface);
            log("echo-java64-codec surface-bound");
            return;
        }
        player.clearVideoSurface();
    }

    private void rebuildTrackInfo(@Nullable Tracks tracks) {
        TrackInfo info = new TrackInfo();
        audioTargets.clear();
        subtitleTargets.clear();
        Format selectedVideoFormat = null;
        if (tracks == null) {
            lastTrackInfo = info;
            return;
        }
        int audioId = 0;
        int subtitleId = 1000;
        for (Tracks.Group group : tracks.getGroups()) {
            TrackGroup mediaGroup = group.getMediaTrackGroup();
            int trackType = group.getType();
            for (int i = 0; i < mediaGroup.length; i++) {
                Format format = mediaGroup.getFormat(i);
                boolean selected = group.isTrackSelected(i);
                if (trackType == C.TRACK_TYPE_AUDIO) {
                    TrackInfoBean bean = buildAudioTrack(audioId++, format, selected, i);
                    info.addAudio(bean);
                    audioTargets.put(bean.trackId, new TrackSelectionTarget(mediaGroup, i));
                } else if (trackType == C.TRACK_TYPE_TEXT) {
                    TrackInfoBean bean = buildSubtitleTrack(subtitleId++, format, selected, i);
                    info.addSubtitle(bean);
                    subtitleTargets.put(bean.trackId, new TrackSelectionTarget(mediaGroup, i));
                } else if (trackType == C.TRACK_TYPE_VIDEO && selected && selectedVideoFormat == null) {
                    selectedVideoFormat = format;
                }
            }
        }
        lastTrackInfo = info;
        log("echo-java64-codec tracks audio=" + info.getAudio().size() + " subtitle=" + info.getSubtitle().size());
        updateRuntimeVideoMode(selectedVideoFormat, "tracks-changed");
    }

    private TrackInfoBean buildAudioTrack(int trackId, @Nullable Format format, boolean selected, int index) {
        TrackInfoBean bean = new TrackInfoBean();
        bean.trackId = trackId;
        bean.index = index;
        bean.groupIndex = index;
        bean.trackGroupId = 0;
        bean.renderId = 2;
        bean.selected = selected;
        bean.rawLanguage = format == null ? "" : nullToEmpty(format.language);
        bean.language = SystemPlayerTrackManager.getFriendlyLanguage(bean.rawLanguage, format == null ? "" : format.label);
        bean.rawTitle = format == null ? "" : nullToEmpty(format.label);
        bean.rawCodec = format == null ? "" : nullToEmpty(format.codecs);
        bean.rawMimeType = format == null ? "" : nullToEmpty(format.sampleMimeType);
        bean.name = buildTrackName("音轨", trackId + 1, bean.language, bean.rawTitle, bean.rawCodec);
        return bean;
    }

    private TrackInfoBean buildSubtitleTrack(int trackId, @Nullable Format format, boolean selected, int index) {
        TrackInfoBean bean = new TrackInfoBean();
        bean.trackId = trackId;
        bean.index = index;
        bean.groupIndex = index;
        bean.trackGroupId = 1;
        bean.renderId = 3;
        bean.selected = selected;
        bean.rawLanguage = format == null ? "" : nullToEmpty(format.language);
        bean.language = SystemPlayerTrackManager.getFriendlyLanguage(bean.rawLanguage, format == null ? "" : format.label);
        bean.rawTitle = format == null ? "" : nullToEmpty(format.label);
        bean.rawCodec = format == null ? "" : nullToEmpty(format.codecs);
        bean.rawMimeType = format == null ? "" : nullToEmpty(format.sampleMimeType);
        bean.name = buildTrackName("字幕", index + 1, bean.language, bean.rawTitle, bean.rawMimeType);
        return bean;
    }

    private String buildTrackName(String prefix, int number, String language, String title, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(" ").append(number);
        if (!TextUtils.isEmpty(language)) {
            builder.append(" - ").append(language);
        }
        if (!TextUtils.isEmpty(title)) {
            builder.append(" ").append(title);
        } else if (!TextUtils.isEmpty(detail)) {
            builder.append(" ").append(detail);
        }
        return builder.toString().trim();
    }

    private void notifyPrepared() {
        if (mPlayerEventListener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onPrepared();
            }
        });
    }

    private void notifyInfo(int what, int extra) {
        if (mPlayerEventListener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onInfo(what, extra);
            }
        });
    }

    private void notifyCompletion() {
        if (mPlayerEventListener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onCompletion();
            }
        });
    }

    private void notifyError() {
        if (mPlayerEventListener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        });
    }

    private void notifyVideoSizeChanged(int width, int height) {
        if (mPlayerEventListener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onVideoSizeChanged(width, height);
            }
        });
    }

    private void dispatchSubtitleText(@Nullable List<Cue> cues) {
        if (subtitleTextListener == null || cues == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Cue cue : cues) {
            if (cue == null || cue.text == null || cue.text.length() == 0) {
                continue;
            }
            lines.add(cue.text.toString());
        }
        final String text = TextUtils.join("\n", lines);
        mainHandler.post(() -> {
            if (!released && subtitleTextListener != null) {
                subtitleTextListener.onSubtitleText(text);
            }
        });
    }

    private String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private String currentPlaybackStateName() {
        return player == null ? "null" : playbackStateName(player.getPlaybackState());
    }

    private String playbackStateName(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "IDLE";
            case Player.STATE_BUFFERING:
                return "BUFFERING";
            case Player.STATE_READY:
                return "READY";
            case Player.STATE_ENDED:
                return "ENDED";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    private void log(String message) {
        android.util.Log.i(TAG, message);
        LOG.i(message);
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
            if (uri == null) {
                return null;
            }
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

    private String describeThrowable(@Nullable Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(throwable.getClass().getSimpleName());
        String message = throwable.getMessage();
        if (!TextUtils.isEmpty(message)) {
            builder.append(":").append(message);
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            builder.append(" <- ").append(cause.getClass().getSimpleName());
            String causeMessage = cause.getMessage();
            if (!TextUtils.isEmpty(causeMessage)) {
                builder.append(":").append(causeMessage);
            }
        }
        return builder.toString();
    }

    private void updateRuntimeVideoMode(@Nullable Format format, String reason) {
        if (format == null) {
            return;
        }
        String sampleMimeType = nullToEmpty(format.sampleMimeType).toLowerCase();
        String codecs = nullToEmpty(format.codecs).toLowerCase();
        ColorInfo colorInfo = format.colorInfo;
        boolean dolbyVision = "video/dolby-vision".equals(sampleMimeType)
                || codecs.contains("dvhe")
                || codecs.contains("dvh1")
                || codecs.contains("dovi");
        boolean hdrTransfer = colorInfo != null
                && (colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                || colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG);
        boolean hdrColorspace = colorInfo != null && colorInfo.colorSpace == C.COLOR_SPACE_BT2020;
        boolean hdrStaticInfo = colorInfo != null && colorInfo.hdrStaticInfo != null && colorInfo.hdrStaticInfo.length > 0;
        boolean hdr = dolbyVision || hdrTransfer || hdrColorspace || hdrStaticInfo;
        String outputMode = dolbyVision ? "dv-base-hdr" : (hdr ? "base-hdr" : "sdr");
        lastRuntimeHdr = hdr;
        lastRuntimeDolbyVision = dolbyVision;
        lastRuntimeOutputMode = outputMode;
        lastRuntimeReason = reason;
        log("echo-java64-codec runtime-mode hdr=" + hdr
                + " dv=" + dolbyVision
                + " output=" + outputMode
                + " mime=" + sampleMimeType
                + " codecs=" + codecs
                + " transfer=" + (colorInfo == null ? -1 : colorInfo.colorTransfer)
                + " colorspace=" + (colorInfo == null ? -1 : colorInfo.colorSpace)
                + " reason=" + reason);
        dispatchRuntimeVideoModeIfNeeded(reason);
    }

    private void dispatchRuntimeVideoModeIfNeeded(String reason) {
        if (runtimeVideoModeListener == null || (!lastRuntimeHdr && !lastRuntimeDolbyVision)) {
            return;
        }
        final boolean hdr = lastRuntimeHdr;
        final boolean dolbyVision = lastRuntimeDolbyVision;
        final String outputMode = lastRuntimeOutputMode;
        final String callbackReason = TextUtils.isEmpty(reason) ? lastRuntimeReason : reason;
        mainHandler.post(() -> {
            if (!released && runtimeVideoModeListener != null) {
                try {
                    runtimeVideoModeListener.onRuntimeVideoMode(hdr, dolbyVision, outputMode, callbackReason);
                } catch (Throwable th) {
                    log("echo-java64-codec runtime-mode-callback failed reason=" + callbackReason
                            + " err=" + th.getMessage());
                }
            }
        });
    }

    private static final class TrackSelectionTarget {
        final TrackGroup group;
        final int trackIndex;

        TrackSelectionTarget(TrackGroup group, int trackIndex) {
            this.group = group;
            this.trackIndex = trackIndex;
        }
    }
}
