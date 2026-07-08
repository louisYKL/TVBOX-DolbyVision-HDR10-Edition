package com.github.tvbox.osc.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.HdrOutputManager;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlaybackUrlNormalizer;
import com.github.tvbox.osc.util.ScreenUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import is.xyz.mpv.MPVLib;
import xyz.doikki.videoplayer.player.AbstractPlayer;

public class MPVCompatPlayer extends AbstractPlayer implements MPVLib.EventObserver, MPVLib.LogObserver {
    private static final String TAG = "MPVCompatPlayer";
    private static final int SUBTITLE_TRACK_STABLE_TICKS_REQUIRED = 5;
    private static final Pattern MPV_SUB_TRACK_LOG_PATTERN = Pattern.compile(
            "^[\\u25cf\\u25cb]\\s+Subs\\s+--sid=(\\d+)\\s+--slang=([^\\s]+)(?:\\s+'([^']*)')?\\s+\\(([^)]*)\\)(.*)$");
    private static final Pattern MPV_SID_SET_LOG_PATTERN = Pattern.compile("Set property: sid=\\\"([^\\\"]+)\\\"");

    private final Context playerContext;
    private final Context appContext;
    private final boolean subtitleHelperMode;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object parsedSubtitleTrackLock = new Object();
    private final LinkedHashMap<Integer, TrackInfoBean> parsedSubtitleTracks = new LinkedHashMap<>();
    private String dataSource;
    private Map<String, String> requestHeaders;
    private Surface pendingSurface;
    private int pendingSurfaceWidth;
    private int pendingSurfaceHeight;
    private boolean prepared;
    private boolean completed;
    private boolean started;
    private boolean released;
    private int bufferedPercentage;
    private long lastKnownPositionMs;
    private long lastKnownDurationMs;
    private long seekOnPreparedMs = -1L;
    private long lastSpeedBytesPerSecond;
    private long lastSurfaceLossPositionMs;
    private boolean surfaceAttached;
    private Surface attachedSurface;
    private boolean fileLoadRequested;
    private boolean playWhenPrepared;
    private boolean wasPlayingBeforeSurfaceLoss;
    private boolean pendingResumeAfterSurfaceAttach;
    private boolean pendingSurfaceLoss;
    private boolean pendingInitialSeekAfterRestart;
    private boolean firstPlaybackRestartSeen;
    private boolean runtimeStreamHdrDetected;
    private boolean runtimeStreamDolbyVisionDetected;
    private boolean runtimeHdrPromotionApplied;
    private long lastSeekRequestMs;
    private boolean deferredLoadScheduled;
    private String lastSubtitleText;
    private int lastKnownSubtitleTrackCount;
    private int lastObservedTrackListCount;
    private int lastObservedSubtitleTrackCount;
    private int subtitleTrackStableTicks;
    private boolean subtitleTrackListSettled;
    private OnSubtitleTextListener subtitleTextListener;
    private OnRuntimeVideoModeListener runtimeVideoModeListener;
    private boolean runtimeVideoModeNotified;
    private OnBridgeTrackInfoListener bridgeTrackInfoListener;

    public interface OnBridgeTrackInfoListener {
        void onTrackInfo(TrackInfo trackInfo);
    }

    public MPVCompatPlayer(Context context) {
        this(context, false);
    }

    public MPVCompatPlayer(Context context, boolean subtitleHelperMode) {
        this.playerContext = context;
        this.appContext = context.getApplicationContext();
        this.subtitleHelperMode = subtitleHelperMode;
    }

    @Override
    public void initPlayer() {
        MPVCompatManager.ensureInitialized(appContext);
        MPVCompatManager.resetPlaybackState();
        MPVLib.addObserver(this);
        MPVLib.addLogObserver(this);
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("cache-speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("current-vo", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-format", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-codec-profile", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-params/primaries", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-params/gamma", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-params/colormatrix", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-params/sig-peak", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("video-dec-params/primaries", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-dec-params/gamma", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-dec-params/colormatrix", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.observeProperty("video-dec-params/sig-peak", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        prepared = false;
        completed = false;
        started = false;
        released = false;
        bufferedPercentage = 0;
        lastKnownPositionMs = 0L;
        lastKnownDurationMs = 0L;
        seekOnPreparedMs = -1L;
        lastSpeedBytesPerSecond = 0L;
        lastSurfaceLossPositionMs = 0L;
        fileLoadRequested = false;
        playWhenPrepared = false;
        attachedSurface = null;
        wasPlayingBeforeSurfaceLoss = false;
        pendingResumeAfterSurfaceAttach = false;
        pendingSurfaceLoss = false;
        pendingInitialSeekAfterRestart = false;
        firstPlaybackRestartSeen = false;
        runtimeStreamHdrDetected = false;
        runtimeStreamDolbyVisionDetected = false;
        runtimeHdrPromotionApplied = false;
        lastSeekRequestMs = -1L;
        deferredLoadScheduled = false;
        lastSubtitleText = null;
        lastKnownSubtitleTrackCount = 0;
        lastObservedTrackListCount = -1;
        lastObservedSubtitleTrackCount = -1;
        subtitleTrackStableTicks = 0;
        subtitleTrackListSettled = false;
        subtitleTextListener = null;
        runtimeVideoModeListener = null;
        runtimeVideoModeNotified = false;
        clearParsedSubtitleTracks();
        forceMaxVolume();
        if (subtitleHelperMode) {
            try {
                MPVLib.setPropertyString("vid", "no");
                MPVLib.setPropertyString("vo", "null");
                MPVLib.setPropertyString("aid", "no");
                MPVLib.setPropertyString("ao", "null");
                MPVLib.setPropertyString("sub-visibility", "no");
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        PlaybackUrlNormalizer.UrlWithHeaders parsed = PlaybackUrlNormalizer.splitUrlAndHeaders(path, headers);
        dataSource = PlaybackUrlNormalizer.normalizeHttpUrl(parsed.url);
        requestHeaders = parsed.headers == null ? new HashMap<String, String>() : new HashMap<>(parsed.headers);
        MPVCompatManager.setCurrentFileForcesTv32LocalProxyPcm(isTv32LocalProxyPlayback(dataSource));
        MPVCompatManager.setCurrentFileAllowsPassthrough(isAudioPassthroughAllowedForCurrentFile(requestHeaders));
        fileLoadRequested = false;
        prepared = false;
        completed = false;
        bufferedPercentage = 0;
        wasPlayingBeforeSurfaceLoss = false;
        pendingResumeAfterSurfaceAttach = false;
        pendingSurfaceLoss = false;
        pendingInitialSeekAfterRestart = false;
        firstPlaybackRestartSeen = false;
        runtimeStreamHdrDetected = false;
        runtimeStreamDolbyVisionDetected = false;
        runtimeHdrPromotionApplied = false;
        lastSeekRequestMs = -1L;
        deferredLoadScheduled = false;
        lastSubtitleText = null;
        lastKnownSubtitleTrackCount = 0;
        lastObservedTrackListCount = -1;
        lastObservedSubtitleTrackCount = -1;
        subtitleTrackStableTicks = 0;
        subtitleTrackListSettled = false;
        runtimeVideoModeNotified = false;
        clearParsedSubtitleTracks();
    }

    private boolean isAudioPassthroughAllowedForCurrentFile(@Nullable Map<String, String> headers) {
        if (isTv32LocalProxyPlayback(dataSource)) {
            logInfo("echo-mpv-audio force-pcm tv32-local-proxy url=" + safeUrlForLog(dataSource));
            return false;
        }
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        String passthrough = getHeaderValue(headers, "X-TVBox-Probe-AudioPassthrough");
        String allowed = getHeaderValue(headers, "X-TVBox-Probe-AudioPassthroughAllowed");
        if ("1".equals(passthrough)) {
            return "1".equals(allowed);
        }
        return false;
    }

    private boolean isTv32LocalProxyPlayback(@Nullable String url) {
        if (TextUtils.isEmpty(url) || !ScreenUtils.isTv32Device(appContext)) {
            return false;
        }
        String lower = decodeUrlForAudioRoute(url).toLowerCase(Locale.US);
        boolean localHost = lower.startsWith("http://127.0.0.1")
                || lower.startsWith("https://127.0.0.1")
                || lower.startsWith("http://localhost")
                || lower.startsWith("https://localhost");
        return localHost
                && lower.contains("/proxy/play/")
                && !PlaybackUrlNormalizer.isHlsLike(lower);
    }

    private String decodeUrlForAudioRoute(String value) {
        String decoded = value;
        for (int i = 0; i < 2; i++) {
            try {
                String next = java.net.URLDecoder.decode(decoded, "UTF-8");
                if (TextUtils.isEmpty(next) || TextUtils.equals(next, decoded)) {
                    break;
                }
                decoded = next;
            } catch (Throwable ignored) {
                break;
            }
        }
        return decoded;
    }

    private String safeUrlForLog(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        return url.length() > 220 ? url.substring(0, 220) + "..." : url;
    }

    private String getHeaderValue(@Nullable Map<String, String> headers, String key) {
        if (headers == null || TextUtils.isEmpty(key)) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? null : entry.getValue().trim();
            }
        }
        return null;
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        throw new UnsupportedOperationException("MPVCompatPlayer does not support AssetFileDescriptor");
    }

    @Override
    public void start() {
        if (released) {
            return;
        }
        if (!fileLoadRequested) {
            playWhenPrepared = true;
            loadCurrentFile();
            return;
        }
        MPVLib.setPropertyBoolean("pause", false);
        started = true;
        completed = false;
    }

    @Override
    public void pause() {
        if (released) {
            return;
        }
        MPVLib.setPropertyBoolean("pause", true);
        started = false;
    }

    @Override
    public void stop() {
        if (released) {
            return;
        }
        MPVLib.command(new String[]{"stop"});
        started = false;
        prepared = false;
        completed = true;
    }

    @Override
    public void prepareAsync() {
        if (released) {
            return;
        }
        if (subtitleHelperMode) {
            loadCurrentFileNow("subtitle-helper");
            return;
        }
        loadCurrentFile();
    }

    @Override
    public void reset() {
        if (released) {
            return;
        }
        try {
            MPVLib.command(new String[]{"stop"});
        } catch (Throwable ignored) {
        }
        try {
            MPVLib.setOptionString("http-header-fields", "");
            MPVLib.setOptionString("referrer", "");
            MPVLib.setOptionString("force-window", "no");
        } catch (Throwable ignored) {
        }
        detachSurfaceIfNeeded();
        dataSource = null;
        requestHeaders = null;
        prepared = false;
        completed = false;
        started = false;
        bufferedPercentage = 0;
        lastKnownPositionMs = 0L;
        lastKnownDurationMs = 0L;
        seekOnPreparedMs = -1L;
        lastSpeedBytesPerSecond = 0L;
        fileLoadRequested = false;
        playWhenPrepared = false;
        wasPlayingBeforeSurfaceLoss = false;
        pendingResumeAfterSurfaceAttach = false;
        pendingSurfaceLoss = false;
        pendingInitialSeekAfterRestart = false;
        firstPlaybackRestartSeen = false;
        runtimeStreamHdrDetected = false;
        runtimeStreamDolbyVisionDetected = false;
        runtimeHdrPromotionApplied = false;
        lastSeekRequestMs = -1L;
        deferredLoadScheduled = false;
        lastSubtitleText = null;
        lastKnownSubtitleTrackCount = 0;
        lastObservedTrackListCount = -1;
        lastObservedSubtitleTrackCount = -1;
        subtitleTrackStableTicks = 0;
        subtitleTrackListSettled = false;
        clearParsedSubtitleTracks();
    }

    @Override
    public boolean isPlaying() {
        return started && !Boolean.TRUE.equals(MPVLib.getPropertyBoolean("pause"));
    }

    @Override
    public void seekTo(long time) {
        if (released) {
            return;
        }
        long safeTimeMs = Math.max(0L, time);
        lastKnownPositionMs = safeTimeMs;
        if (!prepared) {
            logInfo("echo-mpv-skip-initial-seek pos=" + safeTimeMs + " reason=not-prepared");
            return;
        }
        if (!firstPlaybackRestartSeen && fileLoadRequested) {
            logInfo("echo-mpv-skip-initial-seek pos=" + safeTimeMs + " reason=before-first-restart");
            return;
        }
        if (!hasUsableAttachedSurface()) {
            seekOnPreparedMs = safeTimeMs;
            pendingInitialSeekAfterRestart = true;
            logInfo("echo-mpv-delay-seek pos=" + safeTimeMs + " reason=no-surface");
            return;
        }
        lastSeekRequestMs = safeTimeMs;
        try {
            MPVLib.command(new String[]{"seek", String.valueOf(safeTimeMs / 1000d), "absolute+keyframes"});
        } catch (Throwable th) {
            logInfo("echo-mpv-seek-command-failed " + th.getMessage());
            MPVLib.setPropertyDouble("time-pos", safeTimeMs / 1000d);
        }
        resumeAfterSeek("seekTo");
        dumpPlaybackStateDelayed("seekTo", 250L);
        dumpPlaybackStateDelayed("seekTo", 1300L);
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        released = true;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            MPVLib.setPropertyBoolean("pause", true);
        } catch (Throwable ignored) {
        }
        try {
            MPVLib.command(new String[]{"stop"});
        } catch (Throwable ignored) {
        }
        if (surfaceAttached) {
            try {
                MPVLib.detachSurface();
            } catch (Throwable ignored) {
            }
            surfaceAttached = false;
            attachedSurface = null;
        }
        MPVLib.removeObserver(this);
        MPVLib.removeLogObserver(this);
        try {
            MPVLib.setOptionString("http-header-fields", "");
            MPVLib.setOptionString("referrer", "");
            MPVLib.setOptionString("force-window", "no");
            MPVLib.setPropertyString("audio-device", "auto");
        } catch (Throwable ignored) {
        }
        started = false;
        prepared = false;
        completed = true;
        fileLoadRequested = false;
        wasPlayingBeforeSurfaceLoss = false;
        pendingResumeAfterSurfaceAttach = false;
        pendingSurfaceLoss = false;
        pendingInitialSeekAfterRestart = false;
        firstPlaybackRestartSeen = false;
        runtimeStreamHdrDetected = false;
        runtimeStreamDolbyVisionDetected = false;
        runtimeHdrPromotionApplied = false;
        lastSeekRequestMs = -1L;
        deferredLoadScheduled = false;
        lastSubtitleText = null;
        lastKnownSubtitleTrackCount = 0;
        lastObservedTrackListCount = -1;
        lastObservedSubtitleTrackCount = -1;
        subtitleTrackStableTicks = 0;
        subtitleTrackListSettled = false;
        subtitleTextListener = null;
        clearParsedSubtitleTracks();
    }

    @Override
    public long getCurrentPosition() {
        Double value = MPVLib.getPropertyDouble("time-pos");
        if (value != null) {
            lastKnownPositionMs = (long) (value * 1000d);
        }
        return lastKnownPositionMs;
    }

    @Override
    public long getDuration() {
        Double value = MPVLib.getPropertyDouble("duration/full");
        if (value != null) {
            lastKnownDurationMs = (long) (value * 1000d);
        }
        return lastKnownDurationMs;
    }

    @Override
    public int getBufferedPercentage() {
        return bufferedPercentage;
    }

    @Override
    public void setSurface(Surface surface) {
        updateSurface(surface, 0, 0);
        attachSurfaceIfNeeded();
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        int width = 0;
        int height = 0;
        if (holder != null && holder.getSurfaceFrame() != null) {
            width = Math.max(0, holder.getSurfaceFrame().width());
            height = Math.max(0, holder.getSurfaceFrame().height());
        }
        updateSurface(holder == null ? null : holder.getSurface(), width, height);
        attachSurfaceIfNeeded();
    }

    @Override
    public void setVolume(float v1, float v2) {
        forceMaxVolume();
    }

    @Override
    public void setLooping(boolean isLooping) {
        MPVLib.setPropertyString("loop-file", isLooping ? "inf" : "no");
    }

    @Override
    public void setOptions() {
    }

    @Override
    public void setSpeed(float speed) {
        MPVLib.setPropertyDouble("speed", speed);
    }

    @Override
    public float getSpeed() {
        Double speed = MPVLib.getPropertyDouble("speed");
        return speed == null ? 1f : speed.floatValue();
    }

    @Override
    public long getTcpSpeed() {
        return lastSpeedBytesPerSecond;
    }

    @Override
    public boolean supportsTrackSelection() {
        return true;
    }

    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        Integer count = safeGetMpvPropertyInt("track-list/count");
        if (count == null || count <= 0) {
            mergeParsedSubtitleTracks(data);
            if (data.getSubtitle().isEmpty()) {
                logInfo("echo-mpv-track list empty count=" + count);
                return data;
            }
            updateSubtitleTrackListState(Math.max(0, data.getSubtitle().size()), data.getSubtitle().size());
            return data;
        }
        int safeCount = Math.min(count, 64);
        for (int i = 0; i < safeCount; i++) {
            String type = safeGetMpvPropertyString("track-list/" + i + "/type");
            if (TextUtils.isEmpty(type)) {
                continue;
            }
            boolean subtitle = "sub".equalsIgnoreCase(type) || "subtitle".equalsIgnoreCase(type);
            boolean audio = "audio".equalsIgnoreCase(type);
            if (!subtitle && !audio) {
                continue;
            }
            TrackInfoBean bean = new TrackInfoBean();
            Integer id = safeGetMpvPropertyInt("track-list/" + i + "/id");
            bean.trackId = id == null ? i : id;
            bean.index = i;
            bean.groupIndex = audio ? data.getAudio().size() : data.getSubtitle().size();
            bean.trackGroupId = subtitle ? 1 : 0;
            bean.renderId = subtitle ? 3 : 2;
            String ffIndex = safeGetMpvPropertyString("track-list/" + i + "/ff-index");
            String demux = safeGetMpvPropertyString("track-list/" + i + "/demux");
            String defaultFlag = safeGetMpvPropertyString("track-list/" + i + "/default");
            String forcedFlag = safeGetMpvPropertyString("track-list/" + i + "/forced");
            String imageFlag = safeGetMpvPropertyString("track-list/" + i + "/image");
            String externalFlag = safeGetMpvPropertyString("track-list/" + i + "/external");
            String dependentFlag = safeGetMpvPropertyString("track-list/" + i + "/dependent");
            String mainSelectionFlag = safeGetMpvPropertyString("track-list/" + i + "/main-selection");
            bean.language = firstNonEmpty(
                    safeGetMpvPropertyString("track-list/" + i + "/lang"),
                    safeGetMpvPropertyString("track-list/" + i + "/language"),
                    "");
            bean.rawLanguage = bean.language;
            String title = firstNonEmpty(
                    safeGetMpvPropertyString("track-list/" + i + "/title"),
                    safeGetMpvPropertyString("track-list/" + i + "/external-filename"),
                    safeGetMpvPropertyString("track-list/" + i + "/codec-desc"),
                    safeGetMpvPropertyString("track-list/" + i + "/demux-w"),
                    safeGetMpvPropertyString("track-list/" + i + "/demux-h"),
                    safeGetMpvPropertyString("track-list/" + i + "/codec"),
                    "");
            bean.rawTitle = title;
            bean.rawCodec = firstNonEmpty(
                    safeGetMpvPropertyString("track-list/" + i + "/codec"),
                    safeGetMpvPropertyString("track-list/" + i + "/codec-desc"),
                    ffIndex,
                    "");
            bean.rawMimeType = firstNonEmpty(
                    safeGetMpvPropertyString("track-list/" + i + "/codec"),
                    demux,
                    "");
            bean.unreliableMetadata = subtitle
                    && TextUtils.isEmpty(bean.rawTitle)
                    && ("zh".equalsIgnoreCase(bean.rawLanguage)
                    || "chi".equalsIgnoreCase(bean.rawLanguage)
                    || "zho".equalsIgnoreCase(bean.rawLanguage)
                    || "cmn".equalsIgnoreCase(bean.rawLanguage));
            bean.autoSelectBlocked = subtitle && bean.unreliableMetadata;
            String displayLanguage = firstNonEmpty(
                    sanitizeTrackLanguageLabel(bean.rawLanguage, title),
                    SystemPlayerTrackManager.getFriendlyLanguage(bean.rawLanguage,
                            firstNonEmpty(title, bean.rawCodec, bean.rawMimeType)),
                    bean.rawLanguage,
                    "");
            bean.language = displayLanguage;
            if (!TextUtils.isEmpty(defaultFlag)
                    || !TextUtils.isEmpty(forcedFlag)
                    || !TextUtils.isEmpty(imageFlag)
                    || !TextUtils.isEmpty(externalFlag)
                    || !TextUtils.isEmpty(dependentFlag)
                    || !TextUtils.isEmpty(mainSelectionFlag)) {
                bean.rawTitle = (firstNonEmpty(bean.rawTitle, "") + " "
                        + firstNonEmpty(defaultFlag, "") + " "
                        + firstNonEmpty(forcedFlag, "") + " "
                        + firstNonEmpty(imageFlag, "") + " "
                        + firstNonEmpty(externalFlag, "") + " "
                        + firstNonEmpty(dependentFlag, "") + " "
                        + firstNonEmpty(mainSelectionFlag, "")).trim();
            }
            bean.name = buildMpvTrackName(audio ? "音轨" : "字幕",
                    audio ? data.getAudio().size() + 1 : data.getSubtitle().size() + 1,
                    bean.language,
                    title);
            bean.selected = safeGetMpvPropertyBoolean("track-list/" + i + "/selected");
            if (subtitle) {
                logInfo("echo-mpv-track-sub id=" + bean.trackId
                        + " idx=" + i
                        + " selected=" + bean.selected
                        + " lang=" + bean.rawLanguage
                        + " title=" + bean.rawTitle
                        + " codec=" + bean.rawCodec
                        + " unreliable=" + bean.unreliableMetadata
                        + " blocked=" + bean.autoSelectBlocked
                        + " default=" + defaultFlag
                        + " forced=" + forcedFlag
                        + " image=" + imageFlag
                        + " external=" + externalFlag
                        + " dependent=" + dependentFlag
                        + " mainSelection=" + mainSelectionFlag
                        + " ff-index=" + ffIndex);
            }
            if (audio) {
                data.addAudio(bean);
            } else {
                data.addSubtitle(bean);
            }
        }
        mergeParsedSubtitleTracks(data);
        logInfo("echo-mpv-track audio=" + data.getAudio().size()
                + " subtitle=" + data.getSubtitle().size());
        lastKnownSubtitleTrackCount = data.getSubtitle().size();
        updateSubtitleTrackListState(Math.max(count, lastKnownSubtitleTrackCount), lastKnownSubtitleTrackCount);
        return data;
    }

    public void selectSubtitleTrack(TrackInfoBean track) {
        if (track == null) {
            return;
        }
        try {
            track.autoSelectBlocked = false;
            MPVLib.setPropertyString("sid", String.valueOf(track.trackId));
            MPVLib.setPropertyString("secondary-sid", "no");
            boolean bitmapSubtitle = SystemPlayerTrackManager.isBitmapSubtitleTrack(track);
            // Text subtitles keep using our overlay via sub-text. Bitmap subtitles
            // must use MPV's native renderer or nothing is displayed.
            MPVLib.setPropertyString("sub-visibility", bitmapSubtitle ? "yes" : "no");
            logInfo("echo-mpv-subtitle select id=" + track.trackId + " name=" + track.name
                    + " overlayOnly=" + (!bitmapSubtitle)
                    + " bitmap=" + bitmapSubtitle);
        } catch (Throwable th) {
            logInfo("echo-mpv-subtitle select failed id=" + track.trackId + " err=" + th.getMessage());
        }
    }

    public void clearSubtitleTrackSelection() {
        try {
            MPVLib.setPropertyString("sid", "no");
            MPVLib.setPropertyString("secondary-sid", "no");
            MPVLib.setPropertyString("sub-visibility", "no");
            dispatchSubtitleText("");
            logInfo("echo-mpv-subtitle clear");
        } catch (Throwable th) {
            logInfo("echo-mpv-subtitle clear failed err=" + th.getMessage());
        }
    }

    public int getLastKnownSubtitleTrackCount() {
        return Math.max(0, lastKnownSubtitleTrackCount);
    }

    public boolean shouldDelaySubtitleSelection(int attempt) {
        return shouldDelaySubtitleSelection(0, attempt);
    }

    public boolean shouldDelaySubtitleSelection(int currentSubtitleCount, int attempt) {
        if (attempt >= 10) {
            return false;
        }
        refreshSubtitleTrackListState();
        if (!prepared || !firstPlaybackRestartSeen) {
            logInfo("echo-mpv-subtitle wait attempt=" + attempt
                    + " prepared=" + prepared
                    + " restarted=" + firstPlaybackRestartSeen
                    + " count=" + currentSubtitleCount);
            return true;
        }
        if (currentSubtitleCount <= 0) {
            return !subtitleTrackListSettled || subtitleTrackStableTicks < SUBTITLE_TRACK_STABLE_TICKS_REQUIRED;
        }
        if (lastKnownSubtitleTrackCount > currentSubtitleCount) {
            logInfo("echo-mpv-subtitle wait reason=track-count-growing attempt=" + attempt
                    + " current=" + currentSubtitleCount
                    + " max=" + lastKnownSubtitleTrackCount);
            return true;
        }
        return !subtitleTrackListSettled || subtitleTrackStableTicks < SUBTITLE_TRACK_STABLE_TICKS_REQUIRED;
    }

    public void setOnSubtitleTextListener(@Nullable OnSubtitleTextListener listener) {
        subtitleTextListener = listener;
    }

    public void setOnRuntimeVideoModeListener(@Nullable OnRuntimeVideoModeListener listener) {
        runtimeVideoModeListener = listener;
        if (listener != null && (runtimeStreamHdrDetected || runtimeStreamDolbyVisionDetected)) {
            dispatchRuntimeVideoMode(listener,
                    runtimeStreamHdrDetected,
                    runtimeStreamDolbyVisionDetected,
                    MPVCompatManager.getOutputMode(),
                    "listener-bind");
        }
    }

    public void setOnBridgeTrackInfoListener(@Nullable OnBridgeTrackInfoListener listener) {
        bridgeTrackInfoListener = listener;
    }

    public void postToMainThreadDelayed(@Nullable Runnable runnable, long delayMs) {
        if (runnable == null || released) {
            return;
        }
        mainHandler.postDelayed(() -> {
            if (!released) {
                runnable.run();
            }
        }, Math.max(0L, delayMs));
    }

    public void selectAudioTrack(TrackInfoBean track) {
        if (track == null) {
            return;
        }
        try {
            MPVLib.setPropertyString("aid", String.valueOf(track.trackId));
            logInfo("echo-mpv-audio select id=" + track.trackId + " name=" + track.name);
        } catch (Throwable th) {
            logInfo("echo-mpv-audio select failed id=" + track.trackId + " err=" + th.getMessage());
        }
    }

    @Override
    public boolean hasValidDataSource() {
        return !TextUtils.isEmpty(dataSource);
    }

    @Override
    public void eventProperty(String property) {
    }

    @Override
    public void eventProperty(String property, long value) {
        if ("time-pos".equals(property)) {
            lastKnownPositionMs = value * 1000L;
        }
    }

    @Override
    public void eventProperty(String property, boolean value) {
        if ("pause".equals(property)) {
            logInfo("echo-mpv-prop pause=" + value);
            started = !value;
        } else if ("paused-for-cache".equals(property)) {
            logInfo("echo-mpv-prop paused-for-cache=" + value);
            notifyInfo(value ? MEDIA_INFO_BUFFERING_START : MEDIA_INFO_BUFFERING_END, 0);
        }
    }

    @Override
    public void eventProperty(String property, String value) {
        if ("sub-text".equals(property)) {
            dispatchSubtitleText(value);
            return;
        }
        if ("hwdec-current".equals(property)
                || "current-vo".equals(property)
                || "video-codec".equals(property)
                || "video-format".equals(property)
                || "video-codec-profile".equals(property)
                || property.startsWith("video-params/")
                || property.startsWith("video-dec-params/")) {
            Log.i(TAG, "echo-mpv-prop " + property + "=" + value);
            LOG.i("echo-mpv-prop " + property + "=" + value);
            inspectRuntimeStreamMetadata("prop-" + property);
        }
    }

    @Override
    public void eventProperty(String property, double value) {
        if ("duration/full".equals(property)) {
            lastKnownDurationMs = (long) (value * 1000d);
        } else if ("cache-speed".equals(property)) {
            lastSpeedBytesPerSecond = (long) value;
        } else if ("video-params/sig-peak".equals(property)
                || "video-dec-params/sig-peak".equals(property)) {
            logInfo("echo-mpv-prop " + property + "=" + value);
            inspectRuntimeStreamMetadata("prop-" + property);
        }
    }

    @Override
    public void event(int eventId) {
        switch (eventId) {
            case MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED:
                logInfo("echo-mpv-event FILE_LOADED");
                subtitleTrackListSettled = false;
                subtitleTrackStableTicks = 0;
                lastObservedTrackListCount = -1;
                lastObservedSubtitleTrackCount = -1;
                if (!subtitleHelperMode) {
                    attachSurfaceIfNeeded();
                    inspectRuntimeStreamMetadata("file-loaded");
                    requestHdrWindowMode("file-loaded");
                    applyPlaybackModeOptionsIfSurfaceReady("file-loaded");
                }
                prepared = true;
                completed = false;
                forceMaxVolume();
                if (seekOnPreparedMs >= 0L) {
                    pendingInitialSeekAfterRestart = true;
                    logInfo("echo-mpv-delay-seek pos=" + seekOnPreparedMs + " reason=file-loaded");
                }
                if (playWhenPrepared) {
                    MPVLib.setPropertyBoolean("pause", false);
                    started = true;
                }
                dispatchBridgeTrackInfoIfNeeded("file-loaded");
                logInfo("echo-mpv-notify prepared");
                notifyPrepared();
                notifyInfo(MEDIA_INFO_BUFFERING_END, 0);
                break;
            case MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART:
                logInfo("echo-mpv-event PLAYBACK_RESTART");
                firstPlaybackRestartSeen = true;
                refreshSubtitleTrackListState();
                started = true;
                if (!subtitleHelperMode) {
                    inspectRuntimeStreamMetadata("playback-restart");
                    requestHdrWindowMode("playback-restart");
                }
                forceMaxVolume();
                applyPendingInitialSeekIfNeeded("playback-restart");
                dispatchBridgeTrackInfoIfNeeded("playback-restart");
                logInfo("echo-mpv-notify rendering-start");
                notifyInfo(MEDIA_INFO_RENDERING_START, 0);
                break;
            case MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG:
                logInfo("echo-mpv-event VIDEO_RECONFIG");
                if (!subtitleHelperMode) {
                    attachSurfaceIfNeeded();
                }
                refreshSubtitleTrackListState();
                if (!subtitleHelperMode) {
                    inspectRuntimeStreamMetadata("video-reconfig");
                    requestHdrWindowMode("video-reconfig");
                    notifyVideoSizeChanged();
                }
                dispatchBridgeTrackInfoIfNeeded("video-reconfig");
                if (firstPlaybackRestartSeen) {
                    applyPendingInitialSeekIfNeeded("video-reconfig");
                }
                break;
            case MPVLib.MpvEvent.MPV_EVENT_END_FILE:
                logInfo("echo-mpv-event END_FILE eof=" + MPVLib.getPropertyString("eof-reached"));
                if (!released && mPlayerEventListener != null) {
                    String eofReason = MPVLib.getPropertyString("eof-reached");
                    if ("yes".equalsIgnoreCase(eofReason) || Boolean.TRUE.equals(MPVLib.getPropertyBoolean("eof-reached"))) {
                        completed = true;
                        started = false;
                        notifyCompletion();
                    } else {
                        notifyError();
                    }
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        if (text == null) {
            return;
        }
        parseSubtitleTrackLog(prefix, text);
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("error") || lower.contains("failed") || lower.contains("underrun")) {
            Log.w(TAG, prefix + ": " + text);
            LOG.i("echo-mpv-log " + prefix + ": " + text);
        }
    }

    private void loadCurrentFile() {
        if (TextUtils.isEmpty(dataSource)) {
            notifyError();
            return;
        }
        if (subtitleHelperMode) {
            loadCurrentFileNow("subtitle-helper");
            return;
        }
        if (!hasUsableAttachedSurface()) {
            attachSurfaceIfNeeded();
        }
        if (!hasUsableAttachedSurface()) {
            playWhenPrepared = true;
            logInfo("echo-mpv-wait-surface-before-load");
            return;
        }
        scheduleLoadCurrentFile("loadCurrentFile");
    }

    private void scheduleLoadCurrentFile(String reason) {
        if (released || fileLoadRequested || TextUtils.isEmpty(dataSource)) {
            return;
        }
        if (deferredLoadScheduled) {
            logInfo("echo-mpv-load-deferred already reason=" + reason);
            return;
        }
        deferredLoadScheduled = true;
        logInfo("echo-mpv-load-deferred reason=" + reason);
        mainHandler.postDelayed(() -> {
            deferredLoadScheduled = false;
            loadCurrentFileNow("deferred-" + reason);
        }, 90L);
    }

    private void loadCurrentFileNow(String reason) {
        if (released || fileLoadRequested) {
            return;
        }
        if (TextUtils.isEmpty(dataSource)) {
            notifyError();
            return;
        }
        if (!subtitleHelperMode && !hasUsableAttachedSurface()) {
            playWhenPrepared = true;
            logInfo("echo-mpv-wait-surface-before-load reason=" + reason);
            return;
        }
        if (!subtitleHelperMode) {
            attachSurfaceIfNeeded();
        }
        if (!subtitleHelperMode && !hasUsableAttachedSurface()) {
            playWhenPrepared = true;
            logInfo("echo-mpv-wait-surface-before-load reason=" + reason + "-attach");
            return;
        }
        if (!subtitleHelperMode) {
            requestHdrWindowMode("loadfile");
            applyPlaybackModeOptionsIfSurfaceReady("loadfile");
        }
        setHeaders(requestHeaders);
        MPVLib.setPropertyBoolean("pause", true);
        String perFileOptions = MPVCompatManager.buildPlaybackPerFileOptions();
        if (subtitleHelperMode) {
            perFileOptions = appendSubtitleHelperOptions(perFileOptions);
        }
        logInfo("echo-mpv-loadfile reason=" + reason + " url=" + dataSource + " options=" + perFileOptions);
        if (TextUtils.isEmpty(perFileOptions)) {
            MPVLib.command(new String[]{"loadfile", dataSource, "replace"});
        } else {
            MPVLib.command(new String[]{"loadfile", dataSource, "replace", "-1", perFileOptions});
        }
        fileLoadRequested = true;
        prepared = false;
        started = false;
        completed = false;
        firstPlaybackRestartSeen = false;
        runtimeStreamHdrDetected = false;
        runtimeStreamDolbyVisionDetected = false;
        runtimeHdrPromotionApplied = false;
        lastSeekRequestMs = -1L;
        lastSubtitleText = null;
        pendingInitialSeekAfterRestart = seekOnPreparedMs >= 0L;
        bufferedPercentage = 0;
        lastSpeedBytesPerSecond = 0L;
        if (!playWhenPrepared) {
            playWhenPrepared = true;
        }
    }

    private String appendSubtitleHelperOptions(String options) {
        StringBuilder builder = new StringBuilder();
        appendPerFileOption(builder, "vid", "no");
        appendPerFileOption(builder, "vo", "null");
        appendPerFileOption(builder, "aid", "no");
        appendPerFileOption(builder, "ao", "null");
        appendPerFileOption(builder, "hwdec", "no");
        appendPerFileOption(builder, "vd", "auto");
        appendPerFileOption(builder, "video-sync", "display-desync");
        appendPerFileOption(builder, "cache", "yes");
        appendPerFileOption(builder, "cache-pause", "no");
        appendPerFileOption(builder, "sub-visibility", "no");
        appendPerFileOption(builder, "sid", "auto");
        appendPerFileOption(builder, "sub-auto", "fuzzy");
        appendPerFileOption(builder, "pause", "yes");
        return builder.toString();
    }

    private void appendPerFileOption(StringBuilder builder, String key, String value) {
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(key).append('=').append(value);
    }

    private void attachSurfaceIfNeeded() {
        if (pendingSurface == null || !pendingSurface.isValid()) {
            return;
        }
        if (surfaceAttached && pendingSurface == attachedSurface) {
            updateAndroidSurfaceSize();
            requestHdrWindowMode("surface-refresh");
            return;
        }
        // Attach first, then apply any VO-affecting properties. Setting force-window
        // or android-surface-size before the attach races vo/gpu into a null surface.
        MPVLib.attachSurface(pendingSurface);
        surfaceAttached = true;
        attachedSurface = pendingSurface;
        updateAndroidSurfaceSize();
        logInfo("echo-mpv-surface-attached width=" + pendingSurfaceWidth + " height=" + pendingSurfaceHeight);
        requestHdrWindowMode("surface-attach");
        applyPlaybackModeOptionsIfSurfaceReady("surface-attach");
        boolean hadPendingSurfaceLoss = pendingSurfaceLoss;
        pendingSurfaceLoss = false;
        pendingResumeAfterSurfaceAttach = false;
        if (!fileLoadRequested && !TextUtils.isEmpty(dataSource)) {
            scheduleLoadCurrentFile("surface-attach");
        } else if (fileLoadRequested && hadPendingSurfaceLoss) {
            recoverAfterSurfaceAttachIfNeeded();
        }
    }

    private void updateSurface(@Nullable Surface surface, int width, int height) {
        pendingSurface = surface;
        if (width > 0 && height > 0) {
            pendingSurfaceWidth = width;
            pendingSurfaceHeight = height;
            updateAndroidSurfaceSize();
        }
        if (surface == null || !surface.isValid()) {
            if (surfaceAttached) {
                lastSurfaceLossPositionMs = getCurrentPosition();
                wasPlayingBeforeSurfaceLoss = started;
                pendingResumeAfterSurfaceAttach = wasPlayingBeforeSurfaceLoss;
                pendingSurfaceLoss = true;
                surfaceAttached = false;
                attachedSurface = null;
                logInfo("echo-mpv-surface-null defer-detach loaded=" + fileLoadRequested
                        + " pos=" + lastSurfaceLossPositionMs
                        + " wasPlaying=" + wasPlayingBeforeSurfaceLoss);
            } else {
                logInfo("echo-mpv-surface-null keep-current-file loaded=" + fileLoadRequested);
            }
            return;
        }
        pendingSurfaceLoss = false;
    }

    private void updateAndroidSurfaceSize() {
        if (pendingSurfaceWidth <= 0 || pendingSurfaceHeight <= 0) {
            return;
        }
        String value = pendingSurfaceWidth + "x" + pendingSurfaceHeight;
        try {
            MPVLib.setPropertyString("android-surface-size", value);
            logInfo("echo-mpv-surface-size " + value);
        } catch (Throwable th) {
            Log.w(TAG, "set android-surface-size failed " + value, th);
        }
    }

    private void detachSurfaceIfNeeded() {
        if (!surfaceAttached) {
            attachedSurface = null;
            return;
        }
        try {
            MPVLib.detachSurface();
        } catch (Throwable ignored) {
        }
        surfaceAttached = false;
        attachedSurface = null;
    }

    private void recoverAfterSurfaceAttachIfNeeded() {
        if (!pendingResumeAfterSurfaceAttach || released) {
            return;
        }
        pendingResumeAfterSurfaceAttach = false;
        try {
            applyPlaybackModeOptionsIfSurfaceReady("surface-reattach");
            requestHdrWindowMode("surface-reattach");
            if (wasPlayingBeforeSurfaceLoss || playWhenPrepared) {
                MPVLib.setPropertyBoolean("pause", false);
                started = true;
            }
            logInfo("echo-mpv-surface-reattach resume pos=" + lastSurfaceLossPositionMs
                    + " playing=" + started);
        } catch (Throwable th) {
            logInfo("echo-mpv-surface-reattach failed " + th.getMessage());
        }
    }

    private boolean hasUsableAttachedSurface() {
        return surfaceAttached
                && attachedSurface != null
                && attachedSurface.isValid()
                && pendingSurface != null
                && pendingSurface.isValid();
    }

    private void applyPlaybackModeOptionsIfSurfaceReady(String reason) {
        if (!hasUsableAttachedSurface()) {
            logInfo("echo-mpvcompat skip-apply-no-surface reason=" + reason
                    + " attached=" + surfaceAttached);
            return;
        }
        MPVCompatManager.applyPlaybackModeOptions();
    }

    private void applyPendingInitialSeekIfNeeded(String reason) {
        if (!pendingInitialSeekAfterRestart || seekOnPreparedMs < 0L || released) {
            return;
        }
        final long targetMs = seekOnPreparedMs;
        pendingInitialSeekAfterRestart = false;
        seekOnPreparedMs = -1L;
        mainHandler.postDelayed(() -> {
            if (released || !fileLoadRequested) {
                return;
            }
            try {
                logInfo("echo-mpv-apply-delayed-seek pos=" + targetMs + " reason=" + reason);
                lastSeekRequestMs = targetMs;
                try {
                    MPVLib.command(new String[]{"seek", String.valueOf(targetMs / 1000d), "absolute+exact"});
                } catch (Throwable th) {
                    logInfo("echo-mpv-initial-seek-command-failed " + th.getMessage());
                    MPVLib.setPropertyDouble("time-pos", targetMs / 1000d);
                }
                if (playWhenPrepared) {
                    resumeAfterSeek("initial-" + reason);
                }
                dumpPlaybackStateDelayed("initial-" + reason, 300L);
            } catch (Throwable th) {
                logInfo("echo-mpv-apply-delayed-seek failed " + th.getMessage());
            }
        }, 350L);
    }

    private void resumeAfterSeek(String reason) {
        if (released) {
            return;
        }
        try {
            if (playWhenPrepared || started) {
                MPVLib.setPropertyBoolean("pause", false);
                started = true;
                completed = false;
                notifyInfo(MEDIA_INFO_BUFFERING_END, 0);
                logInfo("echo-mpv-resume-after-seek reason=" + reason + " pos=" + lastKnownPositionMs);
                mainHandler.postDelayed(() -> {
                    if (!released && (playWhenPrepared || started)) {
                        try {
                            MPVLib.setPropertyBoolean("pause", false);
                            notifyInfo(MEDIA_INFO_BUFFERING_END, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                }, 300L);
            }
        } catch (Throwable th) {
            logInfo("echo-mpv-resume-after-seek failed " + th.getMessage());
        }
    }

    private void dumpPlaybackStateDelayed(String reason, long delayMs) {
        mainHandler.postDelayed(() -> {
            if (released) {
                return;
            }
            try {
                Double timePos = MPVLib.getPropertyDouble("time-pos");
                Boolean pause = MPVLib.getPropertyBoolean("pause");
                Boolean pausedForCache = MPVLib.getPropertyBoolean("paused-for-cache");
                Double cacheSpeed = MPVLib.getPropertyDouble("cache-speed");
                String eof = MPVLib.getPropertyString("eof-reached");
                String hwdec = MPVLib.getPropertyString("hwdec-current");
                String vo = MPVLib.getPropertyString("current-vo");
                logInfo("echo-mpv-state reason=" + reason
                        + " delay=" + delayMs
                        + " seek=" + lastSeekRequestMs
                        + " time=" + timePos
                        + " started=" + started
                        + " pause=" + pause
                        + " pausedForCache=" + pausedForCache
                        + " cacheSpeed=" + cacheSpeed
                        + " eof=" + eof
                        + " hwdec=" + hwdec
                        + " vo=" + vo);
            } catch (Throwable th) {
                logInfo("echo-mpv-state failed reason=" + reason + " " + th.getMessage());
            }
        }, delayMs);
    }

    private void setHeaders(@Nullable Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            MPVLib.setOptionString("http-header-fields", "");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                continue;
            }
            if (isInternalPlaybackHeader(key)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(key.trim()).append(": ").append(value.trim());
        }
        MPVLib.setOptionString("http-header-fields", builder.toString());
        if (isLocalPlaybackUrl(dataSource)) {
            MPVLib.setOptionString("ytdl", "no");
        }
        String referer = headers.get("Referer");
        if (!TextUtils.isEmpty(referer)) {
            MPVLib.setOptionString("referrer", referer.trim());
        }
        String userAgent = headers.get("User-Agent");
        if (!TextUtils.isEmpty(userAgent)) {
            MPVLib.setOptionString("user-agent", userAgent.trim());
        }
    }

    private boolean isInternalPlaybackHeader(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        return isTvBoxInternalHeader(key);
    }

    private boolean isTvBoxInternalHeader(String key) {
        return !TextUtils.isEmpty(key)
                && key.trim().toLowerCase(Locale.US).startsWith("x-tvbox-probe-");
    }

    private boolean isLocalPlaybackUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && path != null
                    && (path.contains("/proxy/play/") || "/proxy".equals(path));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void notifyVideoSizeChanged() {
        Double width = MPVLib.getPropertyDouble("width");
        Double height = MPVLib.getPropertyDouble("height");
        if (width != null && height != null) {
            notifyVideoSizeChanged(width.intValue(), height.intValue());
        }
    }

    private void notifyVideoSizeChanged(final int width, final int height) {
        final PlayerEventListener listener = mPlayerEventListener;
        if (listener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onVideoSizeChanged(width, height);
            }
        });
    }

    private void forceMaxVolume() {
        MPVCompatManager.applyAudioOutputOptions();
        MPVLib.setPropertyDouble("volume", 100d);
        MPVLib.setPropertyBoolean("mute", false);
        MPVLib.setPropertyString("audio-device", "auto");
    }

    private void dispatchSubtitleText(@Nullable String text) {
        final OnSubtitleTextListener listener = subtitleTextListener;
        if (listener == null || released) {
            return;
        }
        final String safeText = text == null ? "" : text;
        if (TextUtils.equals(lastSubtitleText, safeText)) {
            return;
        }
        lastSubtitleText = safeText;
        mainHandler.post(() -> {
            if (!released && subtitleTextListener != null) {
                logInfo("echo-mpv-subtitle text len=" + safeText.length());
                subtitleTextListener.onSubtitleText(safeText);
            }
        });
    }

    private void dispatchBridgeTrackInfoIfNeeded(String reason) {
        final OnBridgeTrackInfoListener listener = bridgeTrackInfoListener;
        if (!subtitleHelperMode || listener == null || released) {
            return;
        }
        refreshSubtitleTrackListState();
        final TrackInfo trackInfo = getTrackInfo();
        if (trackInfo == null || trackInfo.getSubtitle() == null || trackInfo.getSubtitle().isEmpty()) {
            logInfo("echo-subtitle-bridge helper-trackinfo-skip reason=" + reason + " subtitle=0");
            return;
        }
        if (!subtitleTrackListSettled && subtitleTrackStableTicks < SUBTITLE_TRACK_STABLE_TICKS_REQUIRED) {
            logInfo("echo-subtitle-bridge helper-trackinfo-wait reason=" + reason
                    + " subtitle=" + trackInfo.getSubtitle().size()
                    + " stableTicks=" + subtitleTrackStableTicks
                    + " settled=" + subtitleTrackListSettled);
            return;
        }
        mainHandler.post(() -> {
            if (released || bridgeTrackInfoListener == null) {
                return;
            }
            logInfo("echo-subtitle-bridge helper-trackinfo reason=" + reason
                    + " subtitle=" + (trackInfo == null ? 0 : trackInfo.getSubtitle().size()));
            bridgeTrackInfoListener.onTrackInfo(trackInfo);
        });
    }

    private void notifyPrepared() {
        final PlayerEventListener listener = mPlayerEventListener;
        if (listener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onPrepared();
            }
        });
    }

    private void notifyInfo(final int what, final int extra) {
        final PlayerEventListener listener = mPlayerEventListener;
        if (listener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onInfo(what, extra);
            }
        });
    }

    private void notifyCompletion() {
        final PlayerEventListener listener = mPlayerEventListener;
        if (listener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onCompletion();
            }
        });
    }

    private void notifyError() {
        final PlayerEventListener listener = mPlayerEventListener;
        if (listener == null || released) {
            return;
        }
        mainHandler.post(() -> {
            if (!released && mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        });
    }

    private void requestHdrWindowMode(String reason) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext == null) {
            return;
        }
        if (!MPVCompatManager.shouldRequestHdrOutput() && !runtimeStreamHdrDetected && !runtimeStreamDolbyVisionDetected) {
            logInfo("echo-mpvcompat hdr-window-skip mode=sdr reason=" + reason);
            return;
        }
        try {
            HdrOutputManager.requestHdr(playerContext, "mpv-compat-" + reason);
        } catch (Throwable th) {
            Log.w(TAG, "echo-mpvcompat hdr-window-failed", th);
            LOG.i("echo-mpvcompat hdr-window-failed " + th.getMessage());
        }
    }

    private void inspectRuntimeStreamMetadata(String reason) {
        if (released || !fileLoadRequested) {
            return;
        }
        try {
            StringBuilder builder = new StringBuilder();
            appendProp(builder, "video-codec");
            appendProp(builder, "video-format");
            appendProp(builder, "video-codec-profile");
            appendProp(builder, "video-params/primaries");
            appendProp(builder, "video-params/gamma");
            appendProp(builder, "video-params/colormatrix");
            appendProp(builder, "video-params/sig-peak");
            appendProp(builder, "video-dec-params/primaries");
            appendProp(builder, "video-dec-params/gamma");
            appendProp(builder, "video-dec-params/colormatrix");
            appendProp(builder, "video-dec-params/sig-peak");
            appendTrackMetadata(builder);
            String summary = builder.toString();
            boolean hdr = containsRuntimeHdrMarker(summary);
            boolean dv = containsRuntimeDolbyVisionMarker(summary);
            if (hdr || dv) {
                runtimeStreamHdrDetected = true;
                runtimeStreamDolbyVisionDetected = runtimeStreamDolbyVisionDetected || dv;
                String desiredMode = resolveRuntimeHdrOutputMode(summary, hdr, dv);
                if (!runtimeHdrPromotionApplied
                        || !TextUtils.equals(MPVCompatManager.getOutputMode(), desiredMode)
                        || !MPVCompatManager.shouldRequestHdrOutput()) {
                    runtimeHdrPromotionApplied = MPVCompatManager.promoteRuntimeHdrOutput(
                            desiredMode,
                            reason + " " + shrink(summary)) || runtimeHdrPromotionApplied;
                }
                requestHdrWindowMode("runtime-" + reason);
                dispatchRuntimeVideoMode(runtimeVideoModeListener,
                        runtimeStreamHdrDetected,
                        runtimeStreamDolbyVisionDetected,
                        MPVCompatManager.getOutputMode(),
                        reason);
            }
            if (!TextUtils.isEmpty(summary)) {
                logInfo("echo-mpv-runtime-probe reason=" + reason
                        + " hdr=" + hdr
                        + " dv=" + dv
                        + " mode=" + MPVCompatManager.getOutputMode()
                        + " " + shrink(summary));
            }
        } catch (Throwable th) {
            logInfo("echo-mpv-runtime-probe failed reason=" + reason + " err=" + th.getMessage());
        }
    }

    private String resolveRuntimeHdrOutputMode(String summary, boolean hdr, boolean dv) {
        if (!hdr && !dv) {
            return "sdr";
        }
        String currentMode = MPVCompatManager.getOutputMode();
        if (dv) {
            int profile = extractRuntimeDolbyVisionProfile(summary);
            if (profile == 7 || profile == 8) {
                return "dv-base-hdr";
            }
            if (profile <= 0 && ("dv-base-hdr".equals(currentMode) || "base-hdr".equals(currentMode))) {
                return "dv-base-hdr";
            }
            if ("dv-base-hdr".equals(currentMode) || "base-hdr".equals(currentMode)) {
                return currentMode;
            }
            return "map-hdr";
        }
        return "base-hdr";
    }

    private int extractRuntimeDolbyVisionProfile(String summary) {
        if (TextUtils.isEmpty(summary)) {
            return -1;
        }
        String lower = summary.toLowerCase(Locale.US);
        String marker = "dolby-vision-profile=";
        int start = lower.indexOf(marker);
        if (start < 0) {
            return -1;
        }
        start += marker.length();
        int end = start;
        while (end < lower.length() && Character.isDigit(lower.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(lower.substring(start, end));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void appendProp(StringBuilder builder, String property) {
        if (builder == null || TextUtils.isEmpty(property)) {
            return;
        }
        String value = safeGetMpvPropertyString(property);
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(property).append('=').append(value);
    }

    private void appendTrackMetadata(StringBuilder builder) {
        Integer count = safeGetMpvPropertyInt("track-list/count");
        if (count == null || count <= 0) {
            return;
        }
        int safeCount = Math.min(count, 32);
        for (int i = 0; i < safeCount; i++) {
            String type = safeGetMpvPropertyString("track-list/" + i + "/type");
            if (!"video".equalsIgnoreCase(type)) {
                continue;
            }
            appendTrackProp(builder, i, "codec");
            appendTrackProp(builder, i, "codec-desc");
            appendTrackProp(builder, i, "codec-profile");
            appendTrackProp(builder, i, "demux-codec");
            appendTrackProp(builder, i, "dolby-vision-profile");
            appendTrackProp(builder, i, "hdr10-plus");
            appendTrackProp(builder, i, "metadata/HDR_Format");
            appendTrackProp(builder, i, "metadata/HDR format");
            appendTrackProp(builder, i, "metadata/color_transfer");
            appendTrackProp(builder, i, "metadata/color_primaries");
        }
    }

    private void appendTrackProp(StringBuilder builder, int trackIndex, String key) {
        String property = "track-list/" + trackIndex + "/" + key;
        String value = safeGetMpvPropertyString(property);
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(property).append('=').append(value);
    }

    private String safeGetMpvPropertyString(String property) {
        try {
            String value = MPVLib.getPropertyString(property);
            if (TextUtils.isEmpty(value) || "(null)".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)) {
                return null;
            }
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer safeGetMpvPropertyInt(String property) {
        try {
            return MPVLib.getPropertyInt(property);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean safeGetMpvPropertyBoolean(String property) {
        try {
            return MPVLib.getPropertyBoolean(property);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshSubtitleTrackListState() {
        Integer count = safeGetMpvPropertyInt("track-list/count");
        if (count == null || count <= 0) {
            int parsedCount = getParsedSubtitleTrackCount();
            updateSubtitleTrackListState(parsedCount, parsedCount);
            return;
        }
        int subtitleCount = 0;
        int safeCount = Math.min(count, 64);
        for (int i = 0; i < safeCount; i++) {
            String type = safeGetMpvPropertyString("track-list/" + i + "/type");
            if ("sub".equalsIgnoreCase(type) || "subtitle".equalsIgnoreCase(type)) {
                subtitleCount++;
            }
        }
        int parsedCount = getParsedSubtitleTrackCount();
        if (parsedCount > subtitleCount) {
            subtitleCount = parsedCount;
        }
        updateSubtitleTrackListState(Math.max(count, subtitleCount), subtitleCount);
    }

    private void updateSubtitleTrackListState(int trackListCount, int subtitleCount) {
        lastKnownSubtitleTrackCount = Math.max(lastKnownSubtitleTrackCount, subtitleCount);
        if (trackListCount <= 0) {
            subtitleTrackListSettled = false;
            subtitleTrackStableTicks = 0;
            lastObservedTrackListCount = 0;
            lastObservedSubtitleTrackCount = 0;
            return;
        }
        if (trackListCount != lastObservedTrackListCount
                || subtitleCount != lastObservedSubtitleTrackCount) {
            lastObservedTrackListCount = trackListCount;
            lastObservedSubtitleTrackCount = subtitleCount;
            subtitleTrackStableTicks = 0;
            subtitleTrackListSettled = false;
            logInfo("echo-mpv-track settling total=" + trackListCount
                    + " subtitle=" + subtitleCount
                    + " maxSeen=" + lastKnownSubtitleTrackCount);
            return;
        }
        subtitleTrackStableTicks++;
        if (subtitleTrackStableTicks >= SUBTITLE_TRACK_STABLE_TICKS_REQUIRED) {
            subtitleTrackListSettled = true;
        }
        logInfo("echo-mpv-track stable tick=" + subtitleTrackStableTicks
                + " total=" + trackListCount
                + " subtitle=" + subtitleCount
                + " settled=" + subtitleTrackListSettled);
    }

    private String sanitizeTrackLanguageLabel(String rawLanguage, String title) {
        String safeRaw = firstNonEmpty(rawLanguage, "");
        String safeTitle = firstNonEmpty(title, "");
        if (!TextUtils.isEmpty(safeTitle)) {
            return "";
        }
        String lower = safeRaw.toLowerCase(Locale.US);
        if ("chi".equals(lower) || "zho".equals(lower) || "zh".equals(lower)
                || "zh-cn".equals(lower) || "zh_hans".equals(lower)
                || "zh-hans".equals(lower) || "zh-tw".equals(lower)
                || "zh_hant".equals(lower) || "zh-hant".equals(lower)) {
            // 仅凭通用语言码无法区分“简中/繁中/汉语/韩语误标”。
            // 这里不直接把它渲染成“中文字幕”，避免 UI 误导用户。
            return safeRaw;
        }
        return safeRaw;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private void clearParsedSubtitleTracks() {
        synchronized (parsedSubtitleTrackLock) {
            parsedSubtitleTracks.clear();
        }
    }

    private int getParsedSubtitleTrackCount() {
        synchronized (parsedSubtitleTrackLock) {
            return parsedSubtitleTracks.size();
        }
    }

    private void parseSubtitleTrackLog(@Nullable String prefix, @Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        String safeText = text.trim();
        Matcher trackMatcher = MPV_SUB_TRACK_LOG_PATTERN.matcher(safeText);
        if (trackMatcher.matches()) {
            int sid = parseSafeInt(trackMatcher.group(1), -1);
            if (sid > 0) {
                boolean selected = safeText.startsWith("●");
                String rawLanguage = firstNonEmpty(trackMatcher.group(2), "");
                String rawTitle = firstNonEmpty(trackMatcher.group(3), "");
                String rawCodec = firstNonEmpty(trackMatcher.group(4), "");
                String suffix = firstNonEmpty(trackMatcher.group(5), "").trim();
                if (!TextUtils.isEmpty(suffix)) {
                    rawTitle = (rawTitle + " " + suffix).trim();
                }
                TrackInfoBean bean = buildLoggedSubtitleTrackBean(sid, rawLanguage, rawTitle, rawCodec, selected);
                synchronized (parsedSubtitleTrackLock) {
                    TrackInfoBean existing = parsedSubtitleTracks.get(sid);
                    if (existing == null) {
                        parsedSubtitleTracks.put(sid, bean);
                    } else {
                        mergeTrackBean(existing, bean);
                        existing.selected = bean.selected;
                    }
                }
                lastKnownSubtitleTrackCount = Math.max(lastKnownSubtitleTrackCount, getParsedSubtitleTrackCount());
            }
            return;
        }
        Matcher sidMatcher = MPV_SID_SET_LOG_PATTERN.matcher(safeText);
        if (!sidMatcher.find()) {
            return;
        }
        int sid = parseSafeInt(sidMatcher.group(1), -1);
        if (sid <= 0) {
            return;
        }
        synchronized (parsedSubtitleTrackLock) {
            for (TrackInfoBean bean : parsedSubtitleTracks.values()) {
                bean.selected = bean.trackId == sid;
            }
        }
    }

    private TrackInfoBean buildLoggedSubtitleTrackBean(int sid,
                                                       @Nullable String rawLanguage,
                                                       @Nullable String rawTitle,
                                                       @Nullable String rawCodec,
                                                       boolean selected) {
        TrackInfoBean bean = new TrackInfoBean();
        bean.trackId = sid;
        bean.renderId = 3;
        bean.trackGroupId = 1;
        bean.rawLanguage = firstNonEmpty(rawLanguage, "");
        bean.rawTitle = firstNonEmpty(rawTitle, "");
        bean.rawCodec = firstNonEmpty(rawCodec, "");
        bean.rawMimeType = bean.rawCodec;
        bean.selected = selected;
        bean.unreliableMetadata = TextUtils.isEmpty(bean.rawTitle)
                && ("zh".equalsIgnoreCase(bean.rawLanguage)
                || "chi".equalsIgnoreCase(bean.rawLanguage)
                || "zho".equalsIgnoreCase(bean.rawLanguage)
                || "cmn".equalsIgnoreCase(bean.rawLanguage));
        bean.autoSelectBlocked = bean.unreliableMetadata;
        bean.language = firstNonEmpty(
                sanitizeTrackLanguageLabel(bean.rawLanguage, bean.rawTitle),
                SystemPlayerTrackManager.getFriendlyLanguage(bean.rawLanguage,
                        firstNonEmpty(bean.rawTitle, bean.rawCodec, bean.rawMimeType)),
                "");
        return bean;
    }

    private void mergeParsedSubtitleTracks(@Nullable TrackInfo data) {
        if (data == null) {
            return;
        }
        List<TrackInfoBean> current = data.getSubtitle();
        LinkedHashMap<Integer, TrackInfoBean> parsedSnapshot = new LinkedHashMap<>();
        synchronized (parsedSubtitleTrackLock) {
            for (Map.Entry<Integer, TrackInfoBean> entry : parsedSubtitleTracks.entrySet()) {
                parsedSnapshot.put(entry.getKey(), copyTrackBean(entry.getValue()));
            }
        }
        if (parsedSnapshot.isEmpty()) {
            return;
        }
        LinkedHashMap<Integer, TrackInfoBean> mergedById = new LinkedHashMap<>();
        for (TrackInfoBean bean : current) {
            if (bean != null) {
                mergedById.put(bean.trackId, bean);
            }
        }
        for (Map.Entry<Integer, TrackInfoBean> entry : parsedSnapshot.entrySet()) {
            TrackInfoBean existing = mergedById.get(entry.getKey());
            if (existing == null) {
                mergedById.put(entry.getKey(), copyTrackBean(entry.getValue()));
            } else {
                mergeTrackBean(existing, entry.getValue());
            }
        }
        ArrayList<TrackInfoBean> rebuilt = new ArrayList<>();
        for (Map.Entry<Integer, TrackInfoBean> entry : parsedSnapshot.entrySet()) {
            TrackInfoBean bean = mergedById.remove(entry.getKey());
            if (bean != null) {
                rebuilt.add(bean);
            }
        }
        rebuilt.addAll(mergedById.values());
        if (rebuilt.isEmpty()) {
            return;
        }
        current.clear();
        for (int i = 0; i < rebuilt.size(); i++) {
            TrackInfoBean bean = rebuilt.get(i);
            bean.groupIndex = i;
            bean.index = i;
            bean.trackGroupId = 1;
            bean.renderId = 3;
            bean.language = firstNonEmpty(
                    sanitizeTrackLanguageLabel(bean.rawLanguage, bean.rawTitle),
                    SystemPlayerTrackManager.getFriendlyLanguage(bean.rawLanguage,
                            firstNonEmpty(bean.rawTitle, bean.rawCodec, bean.rawMimeType)),
                    "");
            bean.name = buildMpvTrackName("字幕",
                    i + 1,
                    bean.language,
                    firstNonEmpty(bean.rawTitle, bean.rawCodec, bean.rawMimeType));
            current.add(bean);
        }
    }

    private TrackInfoBean copyTrackBean(@Nullable TrackInfoBean source) {
        TrackInfoBean copy = new TrackInfoBean();
        if (source == null) {
            return copy;
        }
        copy.trackId = source.trackId;
        copy.renderId = source.renderId;
        copy.trackGroupId = source.trackGroupId;
        copy.name = source.name;
        copy.language = source.language;
        copy.rawLanguage = source.rawLanguage;
        copy.rawTitle = source.rawTitle;
        copy.rawCodec = source.rawCodec;
        copy.rawMimeType = source.rawMimeType;
        copy.groupIndex = source.groupIndex;
        copy.index = source.index;
        copy.selected = source.selected;
        copy.unreliableMetadata = source.unreliableMetadata;
        copy.autoSelectBlocked = source.autoSelectBlocked;
        return copy;
    }

    private void mergeTrackBean(@Nullable TrackInfoBean target, @Nullable TrackInfoBean source) {
        if (target == null || source == null) {
            return;
        }
        if (TextUtils.isEmpty(target.rawLanguage)) {
            target.rawLanguage = source.rawLanguage;
        }
        if (TextUtils.isEmpty(target.rawTitle)
                || TextUtils.equals(target.rawTitle, target.rawCodec)
                || "subrip".equalsIgnoreCase(target.rawTitle)) {
            target.rawTitle = firstNonEmpty(source.rawTitle, target.rawTitle);
        }
        if (TextUtils.isEmpty(target.rawCodec)) {
            target.rawCodec = source.rawCodec;
        }
        if (TextUtils.isEmpty(target.rawMimeType)) {
            target.rawMimeType = source.rawMimeType;
        }
        if (TextUtils.isEmpty(target.language)) {
            target.language = source.language;
        }
        target.unreliableMetadata = TextUtils.isEmpty(target.rawTitle)
                && ("zh".equalsIgnoreCase(target.rawLanguage)
                || "chi".equalsIgnoreCase(target.rawLanguage)
                || "zho".equalsIgnoreCase(target.rawLanguage)
                || "cmn".equalsIgnoreCase(target.rawLanguage));
        target.autoSelectBlocked = target.unreliableMetadata;
        target.selected = target.selected || source.selected;
    }

    private int parseSafeInt(@Nullable String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String buildMpvTrackName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(" ").append(number);
        if (!TextUtils.isEmpty(language)) {
            builder.append(" - ").append(language);
        }
        if (!TextUtils.isEmpty(detail)) {
            builder.append(" ").append(detail);
        }
        return builder.toString();
    }

    private boolean containsRuntimeHdrMarker(String summary) {
        if (TextUtils.isEmpty(summary)) {
            return false;
        }
        String lower = summary.toLowerCase(Locale.US);
        return lower.contains("bt.2020")
                || lower.contains("bt2020")
                || lower.contains("pq")
                || lower.contains("smpte")
                || lower.contains("st2084")
                || lower.contains("arib-std-b67")
                || lower.contains("hlg")
                || lower.contains("hdr10")
                || lower.contains("hdr10+")
                || lower.contains("hdr10plus")
                || lower.contains("dolby")
                || lower.contains("dovi")
                || lower.contains("dvhe")
                || lower.contains("dvh1");
    }

    private boolean containsRuntimeDolbyVisionMarker(String summary) {
        if (TextUtils.isEmpty(summary)) {
            return false;
        }
        String lower = summary.toLowerCase(Locale.US);
        return lower.contains("dolby vision")
                || lower.contains("dolby-vision")
                || lower.contains("dolby_vision")
                || lower.contains("dovi")
                || lower.contains("dvhe")
                || lower.contains("dvh1")
                || lower.contains("dolby-vision-profile");
    }

    private String shrink(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 360 ? value.substring(0, 360) : value;
    }

    private void logInfo(String message) {
        Log.i(TAG, message);
        LOG.i(message);
    }

    private void dispatchRuntimeVideoMode(@Nullable OnRuntimeVideoModeListener listener,
                                          boolean hdr,
                                          boolean dv,
                                          String outputMode,
                                          String reason) {
        if (listener == null || (!hdr && !dv)) {
            return;
        }
        runtimeVideoModeNotified = true;
        try {
            listener.onRuntimeVideoMode(hdr, dv, outputMode, reason);
        } catch (Throwable th) {
            logInfo("echo-mpv-runtime-mode-callback failed reason=" + reason + " err=" + th.getMessage());
        }
    }

    public interface OnSubtitleTextListener {
        void onSubtitleText(String text);
    }

    public interface OnRuntimeVideoModeListener {
        void onRuntimeVideoMode(boolean hdr, boolean dolbyVision, String outputMode, String reason);
    }
}
