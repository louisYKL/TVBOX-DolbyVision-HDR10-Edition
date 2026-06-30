package com.github.tvbox.osc.ui.fragment;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.MPVCompatManager;
import com.github.tvbox.osc.player.MPVCompatPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.SystemPlayerTrackManager;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleDialog;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.DolbyVisionPlaybackRouter;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HdrDeviceSupport;
import com.github.tvbox.osc.util.HdrOutputManager;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlaybackUrlNormalizer;
import com.github.tvbox.osc.util.PlayerCapability;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.ScreenUtils;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.VideoStreamProbe;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.XWalkUtils;
import com.github.tvbox.osc.util.parser.SuperParse;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import com.obsez.android.lib.filechooser.ChooserDialog;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.xwalk.core.XWalkJavascriptResult;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkSettings;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;
import org.xwalk.core.XWalkWebResourceRequest;
import org.xwalk.core.XWalkWebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.jessyan.autosize.AutoSize;
import xyz.doikki.videoplayer.player.AndroidMediaPlayer;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;

public class PlayFragment extends BaseLazyFragment {
    private static final String TAG = "PlayFragment";
    private static final int MSG_PARSE_TIMEOUT = 100;
    private static final int MSG_PLAY_TIMEOUT = 101;
    private static final long PLAY_TIMEOUT_MS = 12 * 1000L;
    private static final long PLAY_TIMEOUT_PROXY_MS = 30 * 1000L;
    private static final long PLAY_TIMEOUT_SYSTEM_PROXY_MS = 120 * 1000L;
    private static final long BUFFER_STALL_TIMEOUT_MS = 45 * 1000L;
    private static final long PLAYER_RELEASE_SETTLE_MS = 260L;
    private static final int SUBTITLE_INIT_MAX_ATTEMPTS = 12;
    private static final long SUBTITLE_INIT_RETRY_DELAY_MS = 650L;
    private MyVideoView mVideoView;
    private TextView mPlayLoadTip;
    private ImageView mPlayLoadErr;
    private ProgressBar mPlayLoading;
    private VodController mController;
    private SourceViewModel sourceViewModel;
    private Handler mHandler;
    private final ExecutorService playbackProbeExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService subtitleWorkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackPersistExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger playbackRequestSeq = new AtomicInteger(0);
    private final AtomicInteger playGeneration = new AtomicInteger(0);
    private final AtomicInteger subtitleInitSeq = new AtomicInteger(0);
    private volatile int activePlayGeneration = 0;
    private volatile int activeParseGeneration = 0;
    private volatile int activeM3u8Generation = 0;
    private volatile int activeParseSessionId = 0;
    private volatile String activeProgressKey = "";
    private volatile String activeSubtitleCacheKey = "";
    private volatile String activePlayRequestKey = "";
    private volatile long lastPlayerReleaseAtMs = 0L;

    private boolean suppressPauseForFullScreenTransition;
    private boolean playbackRenderedFirstFrame;
    private boolean fullscreenExitSwitchPending;
    private boolean lastReleasedPlaybackRequiresHdrOutput;
    private boolean keepHdrWindowDuringPlayerSwitch;
    private boolean currentPlaybackUsesNativeJava64DolbyVision;
    private boolean subtitleInitSettledForGeneration;
    private long nextPlayerReleaseSettleMs = PLAYER_RELEASE_SETTLE_MS;
    private List<Subtitle> sourceSubtitles = new java.util.ArrayList<>();
    private String playbackSourceKeySnapshot = "";
    private String playbackFlagSnapshot = "";
    private int playbackIndexSnapshot = -1;
    private String playbackProgressKeySnapshot = "";
    @Nullable
    private TrackInfo lastResolvedSubtitleTrackInfo;
    private String lastResolvedSubtitleTrackPlayer = "";
    private int lastResolvedSubtitleTrackGeneration = -1;
    private String pendingSystemFallbackSourceUrl;
    private HashMap<String, String> pendingSystemFallbackHeaders;
    private boolean systemFallbackTried;
    private int subtitleTextStyle = 0;

    private static final class PlaybackPreflight {
        final String rawUrl;
        final String url;
        final HashMap<String, String> headers;
        final VideoStreamProbe.Result probe;
        final DolbyVisionPlaybackRouter.Decision decision;
        PlaybackPreflight(String rawUrl,
                          String url,
                          HashMap<String, String> headers,
                          VideoStreamProbe.Result probe,
                          DolbyVisionPlaybackRouter.Decision decision) {
            this.rawUrl = rawUrl;
            this.url = url;
            this.headers = headers;
            this.probe = probe;
            this.decision = decision;
        }
    }

    private void persistPlaybackProgress() {
        persistPlaybackPositionCache();
        if (mVideoView != null) {
            try {
                mVideoView.persistProgressNow();
            } catch (Throwable ignored) {
            }
        }
        persistPlaybackHistoryRecordAsync();
    }

    private void persistPlaybackPositionCache() {
        String persistKey = getPlaybackProgressKeyForPersistence();
        if (mVideoView == null || TextUtils.isEmpty(persistKey)) {
            return;
        }
        long position = resolvePersistablePlaybackPosition();
        if (position > 0L) {
            CacheManager.save(MD5.string2MD5(persistKey), position);
            if (!TextUtils.isEmpty(progressKey) && !TextUtils.equals(progressKey, persistKey)) {
                CacheManager.save(MD5.string2MD5(progressKey), position);
            }
            LOG.i("echo-progress-cache fragment saved pos=" + position + " key=" + persistKey);
        }
    }

    private long resolvePersistablePlaybackPosition() {
        if (mVideoView == null) {
            return 0L;
        }
        try {
            long position = mVideoView.resolvePersistablePosition();
            if (position > 0L) {
                return position;
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private void persistPlaybackHistoryRecordAsync() {
        final VodRecordSnapshot snapshot = buildVodRecordSnapshot();
        if (snapshot == null) {
            return;
        }
        try {
            if (playbackPersistExecutor == null
                    || playbackPersistExecutor.isShutdown()
                    || playbackPersistExecutor.isTerminated()) {
                persistPlaybackHistoryRecord(snapshot);
                return;
            }
            playbackPersistExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    persistPlaybackHistoryRecord(snapshot);
                }
            });
        } catch (RejectedExecutionException ex) {
            persistPlaybackHistoryRecord(snapshot);
        }
    }

    @Nullable
    private VodRecordSnapshot buildVodRecordSnapshot() {
        try {
            String resolvedSourceKey = getPlaybackSourceKeyForPersistence();
            String playbackFlag = getPlaybackFlagForPersistence();
            int playbackIndex = getPlaybackIndexForPersistence();
            if (mVodInfo == null
                    || TextUtils.isEmpty(resolvedSourceKey)
                    || mVodInfo.seriesMap == null
                    || TextUtils.isEmpty(playbackFlag)
                    || mVodInfo.seriesMap.get(playbackFlag) == null
                    || playbackIndex < 0
                    || playbackIndex >= mVodInfo.seriesMap.get(playbackFlag).size()) {
                return null;
            }
            VodInfo.VodSeries currentSeries = mVodInfo.seriesMap.get(playbackFlag).get(playbackIndex);
            VodInfo recordVodInfo = copyVodInfoForPersistence(resolvedSourceKey, playbackFlag, playbackIndex, currentSeries);
            return new VodRecordSnapshot(resolvedSourceKey, playbackFlag, playbackIndex,
                    getPlaybackProgressKeyForPersistence(), recordVodInfo);
        } catch (Throwable th) {
            LOG.e("echo-progress-history fragment failed: " + th.getMessage());
            return null;
        }
    }

    private void persistPlaybackHistoryRecord(@NonNull VodRecordSnapshot snapshot) {
        try {
            com.github.tvbox.osc.cache.RoomDataManger.insertVodRecord(snapshot.sourceKey, snapshot.recordVodInfo);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
            LOG.i("echo-progress-history fragment saved index=" + snapshot.playbackIndex
                    + " flag=" + snapshot.playbackFlag + " key=" + snapshot.progressKey);
        } catch (Throwable th) {
            LOG.e("echo-progress-history fragment failed: " + th.getMessage());
        }
    }

    private static final class VodRecordSnapshot {
        final String sourceKey;
        final String playbackFlag;
        final int playbackIndex;
        final String progressKey;
        final VodInfo recordVodInfo;

        VodRecordSnapshot(String sourceKey,
                          String playbackFlag,
                          int playbackIndex,
                          String progressKey,
                          VodInfo recordVodInfo) {
            this.sourceKey = sourceKey;
            this.playbackFlag = playbackFlag;
            this.playbackIndex = playbackIndex;
            this.progressKey = progressKey;
            this.recordVodInfo = recordVodInfo;
        }
    }

    private VodInfo copyVodInfoForPersistence(String resolvedSourceKey,
                                              String playbackFlag,
                                              int playbackIndex,
                                              @Nullable VodInfo.VodSeries currentSeries) {
        VodInfo record = new VodInfo();
        record.last = mVodInfo.last;
        record.id = mVodInfo.id;
        record.tid = mVodInfo.tid;
        record.name = mVodInfo.name;
        record.type = mVodInfo.type;
        record.dt = mVodInfo.dt;
        record.pic = mVodInfo.pic;
        record.lang = mVodInfo.lang;
        record.area = mVodInfo.area;
        record.year = mVodInfo.year;
        record.state = mVodInfo.state;
        record.note = mVodInfo.note;
        record.actor = mVodInfo.actor;
        record.director = mVodInfo.director;
        record.seriesFlags = mVodInfo.seriesFlags;
        record.seriesMap = mVodInfo.seriesMap;
        record.des = mVodInfo.des;
        record.playFlag = playbackFlag;
        record.playIndex = playbackIndex;
        record.playNote = currentSeries == null ? "" : currentSeries.name;
        record.sourceKey = resolvedSourceKey;
        record.playerCfg = mVodInfo.playerCfg;
        record.reverseSort = mVodInfo.reverseSort;
        return record;
    }

    private void bindPlaybackIdentity(String resolvedSourceKey, String playbackFlag, int playbackIndex) {
        playbackSourceKeySnapshot = firstNonEmpty(resolvedSourceKey);
        playbackFlagSnapshot = firstNonEmpty(playbackFlag);
        playbackIndexSnapshot = Math.max(playbackIndex, 0);
        if (mVodInfo != null && !TextUtils.isEmpty(mVodInfo.id) && !TextUtils.isEmpty(playbackFlagSnapshot)) {
            playbackProgressKeySnapshot = playbackSourceKeySnapshot + "|" + mVodInfo.id + "|" + playbackFlagSnapshot + "|" + playbackIndexSnapshot;
        } else {
            playbackProgressKeySnapshot = "";
        }
    }

    private String getPlaybackSourceKeyForPersistence() {
        return firstNonEmpty(playbackSourceKeySnapshot, sourceKey, mVodInfo == null ? null : mVodInfo.sourceKey);
    }

    private String getPlaybackFlagForPersistence() {
        return firstNonEmpty(playbackFlagSnapshot, mVodInfo == null ? null : mVodInfo.playFlag);
    }

    private int getPlaybackIndexForPersistence() {
        if (playbackIndexSnapshot >= 0) {
            return playbackIndexSnapshot;
        }
        return mVodInfo == null ? 0 : Math.max(mVodInfo.playIndex, 0);
    }

    private String getPlaybackProgressKeyForPersistence() {
        if (!TextUtils.isEmpty(playbackProgressKeySnapshot)) {
            return playbackProgressKeySnapshot;
        }
        if (mVodInfo == null || TextUtils.isEmpty(mVodInfo.id)) {
            return "";
        }
        String source = getPlaybackSourceKeyForPersistence();
        String flag = getPlaybackFlagForPersistence();
        if (TextUtils.isEmpty(source) || TextUtils.isEmpty(flag)) {
            return "";
        }
        return source + "|" + mVodInfo.id + "|" + flag + "|" + getPlaybackIndexForPersistence();
    }

    private String resolveActiveSourceKey() {
        String resolvedSourceKey = firstNonEmpty(sourceKey, mVodInfo == null ? null : mVodInfo.sourceKey);
        if (!TextUtils.isEmpty(resolvedSourceKey) && mVodInfo != null && TextUtils.isEmpty(mVodInfo.sourceKey)) {
            mVodInfo.sourceKey = resolvedSourceKey;
        }
        return resolvedSourceKey;
    }

    private int beginPlayGeneration(String progressKey, String subtitleKey) {
        int generation = playGeneration.incrementAndGet();
        activePlayGeneration = generation;
        activeParseGeneration = generation;
        activeM3u8Generation = 0;
        activeProgressKey = progressKey == null ? "" : progressKey;
        activeSubtitleCacheKey = subtitleKey == null ? "" : subtitleKey;
        activePlayRequestKey = "pending|gen=" + generation;
        playbackRequestSeq.incrementAndGet();
        subtitleInitSeq.incrementAndGet();
        subtitleInitSettledForGeneration = false;
        pendingSystemFallbackSourceUrl = null;
        pendingSystemFallbackHeaders = null;
        systemFallbackTried = false;
        clearCurrentPlaybackProbe();
        LOG.i("echo-play-generation begin gen=" + generation + " progress=" + activeProgressKey);
        return generation;
    }

    private int currentPlayGeneration() {
        return activePlayGeneration;
    }

    private boolean isCurrentPlayGeneration(int generation) {
        return generation > 0 && generation == activePlayGeneration && generation == playGeneration.get();
    }

    private boolean isCurrentParseGeneration(int generation) {
        return generation > 0 && generation == activeParseGeneration && isCurrentPlayGeneration(generation);
    }

    private boolean isCurrentParseSession(int generation, int sessionId) {
        return sessionId > 0
                && generation > 0
                && sessionId == activeParseSessionId
                && isCurrentParseGeneration(generation);
    }

    private long buildWebViewParseTag(int generation, int sessionId) {
        return (((long) generation) << 32) | (sessionId & 0xffffffffL);
    }

    private int getWebViewGeneration(View view) {
        Object tag = view == null ? null : view.getTag();
        if (tag instanceof Long) {
            return (int) ((((Long) tag) >> 32) & 0xffffffffL);
        }
        if (tag instanceof Integer) {
            return (Integer) tag;
        }
        return currentPlayGeneration();
    }

    private int getWebViewParseSessionId(View view) {
        Object tag = view == null ? null : view.getTag();
        if (tag instanceof Long) {
            return (int) (((Long) tag) & 0xffffffffL);
        }
        return activeParseSessionId;
    }

    private boolean isCurrentProgressKey(String key) {
        return !TextUtils.isEmpty(key) && TextUtils.equals(key, activeProgressKey);
    }

    private boolean isCurrentPlayRequestKey(String key) {
        return !TextUtils.isEmpty(key) && TextUtils.equals(key, activePlayRequestKey);
    }

    private void logStalePlayback(String stage, int generation) {
        LOG.i("echo-play-stale " + stage + " gen=" + generation + " active=" + activePlayGeneration
                + " progress=" + activeProgressKey);
    }

    private boolean delayPlaybackUntilPlayerReleaseSettled(final Runnable action, final int generation, final String reason) {
        if (mHandler == null || action == null || !isCurrentPlayGeneration(generation)) {
            return false;
        }
        long settleWindowMs = Math.max(PLAYER_RELEASE_SETTLE_MS, nextPlayerReleaseSettleMs);
        long waitMs = settleWindowMs - (System.currentTimeMillis() - lastPlayerReleaseAtMs);
        nextPlayerReleaseSettleMs = PLAYER_RELEASE_SETTLE_MS;
        if (waitMs <= 0L) {
            return false;
        }
        final long delay = Math.min(settleWindowMs, Math.max(80L, waitMs));
        LOG.i("echo-player-release-settle delay=" + delay + " settleWindow=" + settleWindowMs
                + " reason=" + reason + " gen=" + generation);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentPlayGeneration(generation)) {
                    logStalePlayback("release-settle-" + reason, generation);
                    return;
                }
                action.run();
            }
        }, delay);
        return true;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_play;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            mController.mSubtitleView.setTextSize((int) event.obj);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
        Hawk.put(HawkConfig.PLAYER_IS_LIVE,false);
    }

    public long getSavedProgress(String url) {
        int st = 0;
        try {
            st = mVodPlayerCfg.getInt("st");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        long skip = st * 1000L;
        Object theCache=CacheManager.getCache(MD5.string2MD5(url));
        if (theCache == null) {
            return skip;
        }
        long rec = 0;
        if (theCache instanceof Number) {
            rec = ((Number) theCache).longValue();
        } else if (theCache instanceof String) {
            try {
                rec = Long.parseLong((String) theCache);
            } catch (NumberFormatException e) {
                LOG.i("echo-String value is not a valid long.");
            }
        } else {
            LOG.i("echo-Value cannot be converted to long.");
        }
        return Math.max(rec, skip);
    }

    private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_PARSE_TIMEOUT:
                        if (msg.arg1 > 0 && !isCurrentParseGeneration(msg.arg1)) {
                            LOG.i("echo-play-stale parse-timeout gen=" + msg.arg1 + " active=" + activePlayGeneration);
                            return true;
                        }
                        stopParse();
                        errorWithRetry("嗅探错误", false);
                        break;
                    case MSG_PLAY_TIMEOUT:
                        if (msg.arg1 > 0 && !isCurrentPlayGeneration(msg.arg1)) {
                            LOG.i("echo-play-stale play-timeout gen=" + msg.arg1 + " active=" + activePlayGeneration);
                            return true;
                        }
                        LOG.i("echo-playTimeout exceeded, no auto source/player fallback");
                        stopParse();
                        playbackRequestSeq.incrementAndGet();
                        subtitleInitSeq.incrementAndGet();
                        activePlayRequestKey = "";
                        activeProgressKey = "";
                        forceReleaseCurrentPlayer("fragment-play-timeout");
                        currentPlaybackUrl = null;
                        currentPlaybackHeaders = null;
                        clearCurrentPlaybackProbe();
                        pendingSystemFallbackSourceUrl = null;
                        pendingSystemFallbackHeaders = null;
                        systemFallbackTried = false;
                        playbackRenderedFirstFrame = false;
                        errorWithRetry("播放超时", false);
                        break;
                }
                return false;
            }
        });
        mVideoView = findViewById(R.id.mVideoView);
        mVideoView.setKeepSurfaceOnFullScreen(true);
        mVideoView.setEnableAudioFocus(true);
        mPlayLoadTip = findViewById(R.id.play_load_tip);
        mPlayLoading = findViewById(R.id.play_loading);
        mPlayLoadErr = findViewById(R.id.play_load_error);
        mController = new VodController(requireContext());
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(false);
        mController.setGestureEnabled(true);
        mController.setEmbeddedPreviewMode(true);
        ProgressManager progressManager = new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {
                CacheManager.save(MD5.string2MD5(url), progress);
            }

            @Override
            public long getSavedProgress(String url) {
                return PlayFragment.this.getSavedProgress(url);
            }
        };
        mVideoView.setProgressManager(progressManager);
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayerStateChanged(int playerState) {
                syncEmbeddedControllerMode();
                syncHdrWindowForCurrentPlayback("fragment-player-state-" + playerState);
                if (playerState == VideoView.PLAYER_NORMAL) {
                    restorePreviewPlayerFocus();
                }
            }

            @Override
            public void onPlayStateChanged(int playState) {
                LOG.i("echo-play-state:" + playState + " fragment url=" + safeLogSnippet(currentPlaybackUrl));
                syncEmbeddedControllerMode();
                if (playState == VideoView.STATE_PLAYING) {
                    playbackRenderedFirstFrame = true;
                    cancelPlayTimeout();
                    hideTipSafe();
                    syncHdrWindowForCurrentPlayback("fragment-state-" + playState);
                    scheduleSubtitleInitRetry(currentPlayGeneration(), 1);
                } else if (playState == VideoView.STATE_BUFFERED) {
                    cancelPlayTimeout();
                    if (playbackRenderedFirstFrame) {
                        hideTipSafe();
                        syncHdrWindowForCurrentPlayback("fragment-state-" + playState);
                        scheduleSubtitleInitRetry(currentPlayGeneration(), 1);
                    }
                } else if (playState == VideoView.STATE_PAUSED
                        || playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    // A user pause (or natural completion) is not a stall. Disarm any buffer-stall
                    // safety-net timeout so it can't fire a spurious "播放超时" while legitimately
                    // paused/finished. Buffering will re-arm it if it resumes and stalls again.
                    cancelPlayTimeout();
                    hideTipSafe();
                } else if (playState == VideoView.STATE_ERROR) {
                    // Player hit an error. Disarm the play/buffer-stall timeout immediately so it
                    // cannot fire a spurious "播放超时" on top of the error UI that VodController's
                    // errReplay() already shows (e.g. the 120s proxy timeout that would otherwise
                    // fire long after the error is already displayed to the user).
                    cancelPlayTimeout();
                }
                if (playState == VideoView.STATE_PREPARING && !playbackRenderedFirstFrame) {
                    setTip("正在准备播放", true, false);
                }
                if (playState == VideoView.STATE_BUFFERING) {
                    if (playbackRenderedFirstFrame) {
                        // Playback already started, so don't nag the user with a spinner for
                        // a transient mid-playback re-buffer (common right after a seek). But we
                        // MUST keep a safety-net timeout armed: on 32-bit TV with large MKV the
                        // MediaPlayer can stall permanently in BUFFERING after multiple seeks and
                        // never emit PLAYING/BUFFERED again, which previously froze the player with
                        // no recovery path. Arm the buffer-stall timeout so a stuck buffer recovers
                        // via errorWithRetry; it is cancelled the moment STATE_PLAYING/BUFFERED returns.
                        hideTipSafe();
                        startPlayTimeout(currentPlaybackUrl, BUFFER_STALL_TIMEOUT_MS, "buffer-stall-post-render");
                        return;
                    }
                    setTip("正在缓冲视频", true, false);
                    startPlayTimeout(currentPlaybackUrl, BUFFER_STALL_TIMEOUT_MS, "buffer-stall");
                }
            }
        });
        mController.setListener(new VodController.VodControlListener() {
            @Override
            public void playNext(boolean rmProgress) {
                String preProgressKey = progressKey;
                PlayFragment.this.playNext(rmProgress);
                if (rmProgress && preProgressKey != null)
                    CacheManager.delete(MD5.string2MD5(preProgressKey), 0);
            }

            @Override
            public void playPre() {
                PlayFragment.this.playPrevious();
            }

            @Override
            public void changeParse(ParseBean pb) {
                autoRetryCount = 0;
                hasAutoSwitchedPlayer = false;
                triedLineFlags.clear();
                doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                try {
                    mVodPlayerCfg.put(HawkConfig.PLAYER_SELECTION_MANUAL, true);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                mVodInfo.playerCfg = mVodPlayerCfg.toString();
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
            }

            @Override
            public void replay(boolean replay) {
                autoRetryCount = 0;
                hasAutoSwitchedPlayer = false;
                triedLineFlags.clear();
                if(replay){
                    play(true);
                }else {
                    if(webPlayUrl!=null && !webPlayUrl.isEmpty()) {
                        stopParse();
                        initParseLoadFound();
                        goPlayUrl(webPlayUrl,webHeaderMap);
                    }else {
                        play(false);
                    }
                }
            }

            @Override
            public void errReplay() {
                errorWithRetry("视频播放出错", false);
            }

            @Override
            public void selectSubtitle() {
                try {
                    selectMySubtitle();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void selectAudioTrack() {
                selectMyAudioTrack();
            }

            @Override
            public void prepared() {
                AbstractPlayer mediaPlayer = mVideoView == null ? null : mVideoView.getMediaPlayer();
                if (mediaPlayer instanceof MPVCompatPlayer) {
                    bindMpvSubtitleText((MPVCompatPlayer) mediaPlayer);
                }
                scheduleSubtitleInitRetry(currentPlayGeneration(), 0);
            }
            @Override
            public void startPlayUrl(String url, HashMap<String, String> headers) {
                int generation = activeM3u8Generation > 0 ? activeM3u8Generation : currentPlayGeneration();
                if (!isCurrentPlayGeneration(generation)) {
                    logStalePlayback("m3u8-startPlayUrl", generation);
                    return;
                }
                goPlayUrl(url, headers, generation);
            }
            @Override
            public void setAllowSwitchPlayer(boolean isAllow){allowSwitchPlayer=isAllow;}
        });
        mVideoView.setVideoController(mController);
    }

    //设置字幕
    void setSubtitle(String path) {
        applyExternalSubtitle(path);
    }

    private void applyExternalSubtitle(String path) {
        if (path == null || path.length() == 0 || mController == null || mController.mSubtitleView == null) {
            return;
        }
        LOG.i("echo-subtitle apply external fragment path=" + path);
        AbstractPlayer mediaPlayer = mVideoView == null ? null : mVideoView.getMediaPlayer();
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            SystemPlayerTrackManager.clearSubtitleSelections((AndroidMediaPlayer) mediaPlayer, null);
        } else if (mediaPlayer instanceof MPVCompatPlayer) {
            ((MPVCompatPlayer) mediaPlayer).clearSubtitleTrackSelection();
        }
        mController.mSubtitleView.bindToMediaPlayer(mVideoView.getMediaPlayer());
        applySubtitleToneForCurrentPlayback();
        mController.mSubtitleView.stop();
        mController.mSubtitleView.reset();
        mController.mSubtitleView.isInternal = false;
        mController.mSubtitleView.clearSubtitleCache();
        mController.mSubtitleView.setText("");
        mController.mSubtitleView.setSubtitlePath(path);
        mController.mSubtitleView.setVisibility(View.VISIBLE);
        mController.mSubtitleView.bringToFront();
    }

    private boolean shouldPreferInternalSubtitleByDefault() {
        return mController != null
                && mController.mSubtitleView != null
                && mController.mSubtitleView.hasInternal;
    }

    private boolean hasUserSelectedExternalSubtitle(String subtitlePathCache) {
        if (TextUtils.isEmpty(subtitlePathCache)) {
            return false;
        }
        if (!subtitlePathCache.startsWith("http://") && !subtitlePathCache.startsWith("https://")) {
            File localSubtitle = new File(subtitlePathCache);
            if (localSubtitle.exists() && localSubtitle.isFile()) {
                return true;
            }
        }
        if (sourceSubtitles == null || sourceSubtitles.isEmpty()) {
            return false;
        }
        for (Subtitle subtitle : sourceSubtitles) {
            if (subtitle != null && TextUtils.equals(subtitle.getUrl(), subtitlePathCache)) {
                return true;
            }
        }
        return false;
    }

    private void applyMappedSubtitleTrackAsync(@Nullable TrackInfoBean track, boolean showFailureToast) {
        if (track == null || TextUtils.isEmpty(currentPlaybackUrl)) {
            if (showFailureToast) {
                Toast.makeText(mContext, "当前内置字幕轨暂无法映射", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final int generation = currentPlayGeneration();
        final String playbackUrl = currentPlaybackUrl;
        final HashMap<String, String> playbackHeaders = currentPlaybackHeaders == null
                ? null : new HashMap<>(currentPlaybackHeaders);
        subtitleWorkExecutor.execute(() -> {
            String mappedPath = VideoStreamProbe.resolveMappedSubtitlePath(
                    mContext,
                    playbackUrl,
                    playbackHeaders,
                    track);
            if (mHandler == null) {
                return;
            }
            mHandler.post(() -> {
                if (!isCurrentPlayGeneration(generation)) {
                    logStalePlayback("mapped-subtitle", generation);
                    return;
                }
                if (!TextUtils.isEmpty(mappedPath)) {
                    track.mappedSubtitlePath = mappedPath;
                    LOG.i("echo-subtitle apply mapped fragment track=" + track.trackId
                            + " extractor=" + track.extractorTrackIndex
                            + " path=" + mappedPath);
                    applyExternalSubtitle(mappedPath);
                } else if (showFailureToast) {
                    Toast.makeText(mContext,
                            SystemPlayerTrackManager.isBitmapSubtitleTrack(track)
                                    ? "当前图形内置字幕轨暂不支持系统映射"
                                    : "当前内置字幕轨暂无法映射",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Nullable
    private TrackInfo resolveSubtitleTrackInfo(AbstractPlayer mediaPlayer) {
        String currentPlayerTag = mediaPlayer == null ? "" : mediaPlayer.getClass().getSimpleName();
        int currentGeneration = currentPlayGeneration();
        TrackInfo trackInfo = mediaPlayer instanceof AndroidMediaPlayer
                ? SystemPlayerTrackManager.getTrackInfo((AndroidMediaPlayer) mediaPlayer)
                : mediaPlayer instanceof MPVCompatPlayer
                ? ((MPVCompatPlayer) mediaPlayer).getTrackInfo()
                : null;
        if (mediaPlayer instanceof AndroidMediaPlayer && !TextUtils.isEmpty(currentPlaybackUrl)) {
            VideoStreamProbe.Result probe = getCurrentPlaybackProbe();
            if (probe == null) {
                LOG.i("echo-subtitle probe-cache-miss fragment skip-active-probe url="
                        + safeLogSnippet(currentPlaybackUrl));
            }
            trackInfo = SystemPlayerTrackManager.mergeSubtitleMetadata(
                    trackInfo,
                    VideoStreamProbe.toSubtitleTrackBeans(probe));
        }
        if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
            lastResolvedSubtitleTrackInfo = trackInfo;
            lastResolvedSubtitleTrackPlayer = currentPlayerTag;
            lastResolvedSubtitleTrackGeneration = currentGeneration;
            return trackInfo;
        }
        if (lastResolvedSubtitleTrackInfo != null
                && currentGeneration == lastResolvedSubtitleTrackGeneration
                && TextUtils.equals(lastResolvedSubtitleTrackPlayer, currentPlayerTag)) {
            return lastResolvedSubtitleTrackInfo;
        }
        return null;
    }

    private void clearResolvedSubtitleTrackCache() {
        lastResolvedSubtitleTrackInfo = null;
        lastResolvedSubtitleTrackPlayer = "";
        lastResolvedSubtitleTrackGeneration = -1;
    }

    @Nullable
    private VideoStreamProbe.Result getCurrentPlaybackProbe() {
        if (TextUtils.isEmpty(currentPlaybackUrl)
                || TextUtils.isEmpty(currentPlaybackProbeUrl)
                || !TextUtils.equals(currentPlaybackUrl, currentPlaybackProbeUrl)) {
            return null;
        }
        return currentPlaybackProbe;
    }

    private void rememberCurrentPlaybackProbe(@Nullable String playbackUrl,
                                              @Nullable VideoStreamProbe.Result probe) {
        currentPlaybackProbeUrl = TextUtils.isEmpty(playbackUrl) ? null : playbackUrl;
        currentPlaybackProbe = probe;
    }

    private void clearCurrentPlaybackProbe() {
        currentPlaybackProbeUrl = null;
        currentPlaybackProbe = null;
    }

    private void enableSystemSubtitleOverlayPassThrough(AbstractPlayer mediaPlayer) {
        if (!(mediaPlayer instanceof AndroidMediaPlayer)) {
            return;
        }
        ((AndroidMediaPlayer) mediaPlayer).setOnTimedTextListener(text -> {
            if (mController == null || mController.mSubtitleView == null || !mController.mSubtitleView.isInternal) {
                return;
            }
            mController.mSubtitleView.onSubtitleChanged(SystemPlayerTrackManager.createInternalSubtitle(text));
        });
    }

    private void disableSystemSubtitleOverlayPassThrough(AbstractPlayer mediaPlayer) {
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            ((AndroidMediaPlayer) mediaPlayer).setOnTimedTextListener(null);
        }
    }

    private void hideOverlaySubtitleView() {
        if (mController == null || mController.mSubtitleView == null) {
            return;
        }
        mController.mSubtitleView.stop();
        mController.mSubtitleView.reset();
        mController.mSubtitleView.isInternal = false;
        mController.mSubtitleView.setText("");
        mController.mSubtitleView.setVisibility(View.GONE);
    }

    private void handleBridgeSubtitleText(String text) {
        if (mHandler == null) {
            return;
        }
        mHandler.post(() -> {
            if (mController == null || mController.mSubtitleView == null || !mController.mSubtitleView.isInternal) {
                return;
            }
            mController.mSubtitleView.onSubtitleChanged(SystemPlayerTrackManager.createInternalSubtitle(text));
        });
    }

    private String buildPlayRequestKey(String resolvedSourceKey,
                                       String playFlagValue,
                                       int playIndexValue,
                                       VodInfo.VodSeries series,
                                       int generationValue) {
        String seriesUrl = series == null ? "" : firstNonEmpty(series.url);
        String seriesName = series == null ? "" : firstNonEmpty(series.name);
        return firstNonEmpty(resolvedSourceKey) + "|"
                + (mVodInfo == null ? "" : firstNonEmpty(mVodInfo.id)) + "|"
                + firstNonEmpty(playFlagValue) + "|"
                + playIndexValue + "|"
                + seriesName + "|"
                + seriesUrl + "|gen=" + generationValue;
    }

    void selectSourceSubtitle() {
        if (sourceSubtitles == null || sourceSubtitles.isEmpty()) {
            Toast.makeText(mContext, "没有片源字幕", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sourceSubtitles.size() == 1) {
            setSubtitle(sourceSubtitles.get(0).getUrl());
            return;
        }
        SelectDialog<Subtitle> dialog = new SelectDialog<>(getActivity());
        dialog.setTip("选择片源字幕");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Subtitle>() {
            @Override
            public void click(Subtitle value, int pos) {
                setSubtitle(value.getUrl());
                dialog.dismiss();
            }

            @Override
            public String getDisplay(Subtitle val) {
                return TextUtils.isEmpty(val.getName()) ? "字幕 " + (sourceSubtitles.indexOf(val) + 1) : val.getName();
            }
        }, new DiffUtil.ItemCallback<Subtitle>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull Subtitle oldItem, @NonNull @NotNull Subtitle newItem) {
                return TextUtils.equals(oldItem.getUrl(), newItem.getUrl());
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull Subtitle oldItem, @NonNull @NotNull Subtitle newItem) {
                return TextUtils.equals(oldItem.getUrl(), newItem.getUrl())
                        && TextUtils.equals(oldItem.getName(), newItem.getName());
            }
        }, sourceSubtitles, 0);
        dialog.show();
    }

    void selectMySubtitle() throws Exception {
        refreshInternalSubtitleState();
        SubtitleDialog subtitleDialog = new SubtitleDialog(getActivity());
        if (mController.mSubtitleView.hasInternal) {
            subtitleDialog.selectInternal.setVisibility(View.VISIBLE);
        } else {
            subtitleDialog.selectInternal.setVisibility(View.GONE);
        }
        if (sourceSubtitles != null && !sourceSubtitles.isEmpty()) {
            subtitleDialog.selectSource.setVisibility(View.VISIBLE);
        } else {
            subtitleDialog.selectSource.setVisibility(View.GONE);
        }
        subtitleDialog.setSubtitleViewListener(new SubtitleDialog.SubtitleViewListener() {
            @Override
            public void setTextSize(int size) {
                mController.mSubtitleView.setTextSize(SubtitleHelper.normalizeTextSize(getActivity(), size));
            }
            @Override
            public void setSubtitleDelay(int milliseconds) {
                mController.mSubtitleView.setSubtitleDelay(milliseconds);
            }
            @Override
            public void selectInternalSubtitle() {
                selectMyInternalSubtitle();
            }
            @Override
            public void selectSourceSubtitle() {
                PlayFragment.this.selectSourceSubtitle();
            }
            @Override
            public void setTextStyle(int style) {
                setSubtitleViewTextStyle(style);
            }
        });
        subtitleDialog.setSearchSubtitleListener(new SubtitleDialog.SearchSubtitleListener() {
            @Override
            public void openSearchSubtitleDialog() {
                SearchSubtitleDialog searchSubtitleDialog = new SearchSubtitleDialog(getActivity());
                searchSubtitleDialog.setSubtitleLoader(new SearchSubtitleDialog.SubtitleLoader() {
                    @Override
                    public void loadSubtitle(Subtitle subtitle) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String zimuUrl = subtitle.getUrl();
                                LOG.i("echo-Remote Subtitle Url: " + zimuUrl);
                                setSubtitle(zimuUrl);//设置字幕
                                searchSubtitleDialog.dismiss();
                            }
                        });
                    }
                });
                if(mVodInfo.playFlag.contains("Ali")||mVodInfo.playFlag.contains("parse")){
                    searchSubtitleDialog.setSearchWord(mVodInfo.playNote);
                }else {
                    searchSubtitleDialog.setSearchWord(mVodInfo.name);
                }
                searchSubtitleDialog.show();
            }
        });
        subtitleDialog.setLocalFileChooserListener(new SubtitleDialog.LocalFileChooserListener() {
            @Override
            public void openLocalFileChooserDialog() {
                new ChooserDialog(getActivity())
                        .withFilter(false, false, "srt", "ass", "ssa", "scc", "stl", "ttml", "vtt")
                        .withStartFile("/storage/emulated/0/Download")
                        .withChosenListener(new ChooserDialog.Result() {
                            @Override
                            public void onChoosePath(String path, File pathFile) {
                                LOG.i("echo-Local Subtitle Path: " + path);
                                setSubtitle(path);//设置字幕
                            }
                        })
                        .build()
                        .show();
            }
        });
        subtitleDialog.show();
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    void setSubtitleViewTextStyle(int style) {
        subtitleTextStyle = style;
        mController.mSubtitleView.setSubtitleTextColor(getContext().getResources().getColor(R.color.color_FFFFFF));
        applySubtitleToneForCurrentPlayback();
    }

    private void applySubtitleToneForCurrentPlayback() {
        if (mController == null || mController.mSubtitleView == null) {
            return;
        }
        mController.mSubtitleView.setHdrSubtitleMode(currentPlaybackRequiresHdrOutput);
    }

    private boolean isSameTrack(TrackInfoBean left, TrackInfoBean right) {
        return left.renderId == right.renderId
                && left.trackGroupId == right.trackGroupId
                && left.trackId == right.trackId
                && left.extractorTrackIndex == right.extractorTrackIndex
                && left.metadataOnly == right.metadataOnly;
    }

    void selectMyAudioTrack() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = mediaPlayer instanceof AndroidMediaPlayer
                ? SystemPlayerTrackManager.getTrackInfo((AndroidMediaPlayer) mediaPlayer)
                : mediaPlayer instanceof MPVCompatPlayer
                ? ((MPVCompatPlayer) mediaPlayer).getTrackInfo()
                : null;
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) {
            Toast.makeText(mContext, "没有音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(getActivity());
        dialog.setTip("切换音轨");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                try {
                    for (TrackInfoBean audio : bean) {
                        audio.selected = isSameTrack(audio, value);
                    }
                    if (mediaPlayer instanceof AndroidMediaPlayer) {
                        SystemPlayerTrackManager.selectTrack((AndroidMediaPlayer) mediaPlayer, value);
                    } else if (mediaPlayer instanceof MPVCompatPlayer) {
                        ((MPVCompatPlayer) mediaPlayer).selectAudioTrack(value);
                    }
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换音轨出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                return val.name;
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return isSameTrack(oldItem, newItem);
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return isSameTrack(oldItem, newItem);
            }
        }, bean, trackInfo.getAudioSelected(false));
        dialog.show();
    }

    void selectMyInternalSubtitle() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = resolveSubtitleTrackInfo(mediaPlayer);
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有内置字幕", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) {
            Toast.makeText(mContext, "没有内置字幕", Toast.LENGTH_SHORT).show();
            return;
        }
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(getActivity());
        dialog.setTip("切换内置字幕");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                try {
                    for (TrackInfoBean subtitle : bean) {
                        subtitle.selected = isSameTrack(subtitle, value);
                    }
                    if (mediaPlayer instanceof AndroidMediaPlayer) {
                        if (SystemPlayerTrackManager.isMetadataOnlySubtitleTrack(value)) {
                            applyMappedSubtitleTrackAsync(value, true);
                        } else if (SystemPlayerTrackManager.usesNativeSubtitleRenderer(value)) {
                            disableSystemSubtitleOverlayPassThrough(mediaPlayer);
                            hideOverlaySubtitleView();
                        } else {
                            prepareInternalSubtitleOverlay(mediaPlayer);
                            enableSystemSubtitleOverlayPassThrough(mediaPlayer);
                            SystemPlayerTrackManager.selectTrack((AndroidMediaPlayer) mediaPlayer, value);
                        }
                        if (SystemPlayerTrackManager.usesNativeSubtitleRenderer(value)) {
                            SystemPlayerTrackManager.selectTrack((AndroidMediaPlayer) mediaPlayer, value);
                        }
                    } else if (mediaPlayer instanceof MPVCompatPlayer) {
                        if (SystemPlayerTrackManager.isBitmapSubtitleTrack(value)) {
                            hideOverlaySubtitleView();
                        } else {
                            prepareInternalSubtitleOverlay(mediaPlayer);
                        }
                        ((MPVCompatPlayer) mediaPlayer).selectSubtitleTrack(value);
                    }
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换内置字幕出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                return val.name;
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return isSameTrack(oldItem, newItem);
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return isSameTrack(oldItem, newItem);
            }
        }, bean, trackInfo.getSubtitleSelected(false));
        dialog.show();
    }

    private void refreshInternalSubtitleState() {
        if (mVideoView == null || mController == null || mController.mSubtitleView == null) {
            return;
        }
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = resolveSubtitleTrackInfo(mediaPlayer);
        boolean hasInternal = trackInfo != null && !trackInfo.getSubtitle().isEmpty();
        mController.mSubtitleView.hasInternal = hasInternal;
        LOG.i("echo-subtitle refresh internal=" + hasInternal
                + " player=" + (mediaPlayer == null ? "null" : mediaPlayer.getClass().getSimpleName())
                + " count=" + (trackInfo == null ? 0 : trackInfo.getSubtitle().size()));
    }

    void setTip(String msg, boolean loading, boolean err) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(new Runnable() { //影魔
            @Override
            public void run() {
                mPlayLoadTip.setText(msg);
                mPlayLoadTip.setVisibility(View.VISIBLE);
                mPlayLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
                mPlayLoadErr.setVisibility(err ? View.VISIBLE : View.GONE);
            }
        });
    }

    void hideTip() {
        mPlayLoadTip.setVisibility(View.GONE);
        mPlayLoading.setVisibility(View.GONE);
        mPlayLoadErr.setVisibility(View.GONE);
    }

    void hideTipSafe() {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(this::hideTip);
    }

    void errorWithRetry(String err, boolean finish) {
        if (!finish && tryDolbyVisionFallback()) {
            return;
        }
        if (!isAdded()) return;
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (finish) {
                    setTip(err, false, true);
                    Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show();
                } else {
                    setTip(err, false, true);
                }
            }
        });
    }

    void playUrl(String url, HashMap<String, String> headers) {
        playUrl(url, headers, currentPlayGeneration());
    }

    private void playUrl(String url, HashMap<String, String> headers, int generation) {
        if (!isCurrentPlayGeneration(generation)) {
            logStalePlayback("playUrl", generation);
            return;
        }
        if (TextUtils.isEmpty(url)) {
            handleMissingPlayableUrl("playUrl-empty-arg", null);
            return;
        }
        if(!url.startsWith("data:application"))EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, url));//更新播放地址
        if (!Hawk.get(HawkConfig.M3U8_PURIFY, false)) {
            goPlayUrl(url,headers, generation);
            return;
        }
        if (url.startsWith("http://127.0.0.1") || !url.contains(".m3u8")) {
            goPlayUrl(url,headers, generation);
            return;
        }
        if(DefaultConfig.noAd(mVodInfo.playFlag)){
            goPlayUrl(url,headers, generation);
            return;
        }
        LOG.i("echo-playM3u8:" + url);
        activeM3u8Generation = generation;
        mController.playM3u8(url,headers);
    }
    public void goPlayUrl(String url, HashMap<String, String> headers) {
        goPlayUrl(url, headers, currentPlayGeneration());
    }

    private void goPlayUrl(String url, HashMap<String, String> headers, int generation) {
        if (!isCurrentPlayGeneration(generation)) {
            logStalePlayback("goPlayUrl", generation);
            return;
        }
        stopParse();
        LOG.i("echo-goPlayUrl:" + url);
        final String normalizedUrl = url == null ? "" : url.trim();
        if (TextUtils.isEmpty(normalizedUrl)) {
            handleMissingPlayableUrl("empty-goPlayUrl");
            return;
        }
        if(autoRetryCount==0)webPlayUrl=normalizedUrl;
        webHeaderMap = headers == null ? null : new HashMap<>(headers);
        if (mActivity == null) return;
        if (!isAdded()) return;
        final String finalUrl = normalizedUrl;
        final HashMap<String, String> finalHeaders = headers == null ? null : new HashMap<>(headers);
        final int requestSeq = playbackRequestSeq.incrementAndGet();
        final int requestGeneration = generation;
        playbackProbeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentPlayGeneration(requestGeneration)) {
                    logStalePlayback("preflight-before", requestGeneration);
                    return;
                }
                final PlaybackPreflight preflight = buildPlaybackPreflight(finalUrl, finalHeaders);
                if (mActivity == null || !isAdded() || requestSeq != playbackRequestSeq.get()
                        || !isCurrentPlayGeneration(requestGeneration)) {
                    logStalePlayback("preflight-after", requestGeneration);
                    return;
                }
                requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startPlaybackPreflightOnMain(preflight, finalHeaders, requestSeq, requestGeneration);
            }
        });
            }
        });
    }

    private void startPlaybackPreflightOnMain(final PlaybackPreflight preflight,
                                              final HashMap<String, String> originalHeaders,
                                              final int requestSeq,
                                              final int requestGeneration) {
        if (requestSeq != playbackRequestSeq.get() || !isCurrentPlayGeneration(requestGeneration)) {
            LOG.i("echo-playback-request stale fragment seq=" + requestSeq);
            logStalePlayback("goPlayUrl-main", requestGeneration);
            return;
        }
        stopParse();
        if (mVideoView == null || preflight == null || TextUtils.isEmpty(preflight.url)) {
            return;
        }
        try {
            int playerType = mVodPlayerCfg.getInt("pl");
            if (PlayerHelper.isExternalPlayerType(playerType)) {
                VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                String playTitle = mVodInfo.name + " " + vs.name;
                setTip("调用" + PlayerHelper.getPlayerName(playerType) + "进行播放", true, false);
                long progress = getSavedProgress(getPlaybackProgressKeyForPersistence());
                boolean callResult = PlayerHelper.runExternalPlayer(playerType, requireActivity(), preflight.url,
                        playTitle, playSubtitle, originalHeaders, progress);
                if (callResult) {
                    setTip("调用" + PlayerHelper.getPlayerName(playerType) + "成功", true, false);
                    return;
                }
                setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + "失败", false, true);
                return;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        setTip("正在准备播放", true, false);
        cancelPlayTimeout();
        currentPlaybackUrl = null;
        playbackRenderedFirstFrame = false;
        try {
            final JSONObject playbackPlayerCfg = copyPlayerConfigForPlayback();
            HashMap<String, String> activeHeaders = preflight.headers == null ? null : new HashMap<>(preflight.headers);
            int requestedPlayerType = PlayerHelper.PLAYER_TYPE_SYSTEM;
            final DolbyVisionPlaybackRouter.Decision dvDecision = preflight.decision;
            VideoStreamProbe.Result streamProbe = preflight.probe;
            if (activeHeaders == null && shouldCreateInternalPlaybackHeaders(preflight.url, streamProbe)) {
                activeHeaders = new HashMap<>();
            }
            applyProbePlaybackHeaders(activeHeaders, preflight.url, streamProbe);
            final HashMap<String, String> finalActiveHeaders = activeHeaders;
            LOG.i("echo-hdr-classify fragment probed=" + streamProbe.probed
                    + " dv=" + streamProbe.hasDolbyVision
                    + " hdr10=" + streamProbe.hasHdr10
                    + " hdr10Plus=" + streamProbe.hasHdr10Plus
                    + " dvProfile=" + streamProbe.dolbyVisionProfile
                    + " hdr10Base=" + streamProbe.hasHdr10BaseLayer
                    + " matroska=" + streamProbe.isMatroska
                    + " videoMime=" + streamProbe.primaryVideoMime
                    + " audioMime=" + streamProbe.primaryAudioMime
                    + " avc=" + streamProbe.hasAvcVideo
                    + " hevc=" + streamProbe.hasHevcVideo
                    + " eac3=" + streamProbe.hasEac3Audio
                    + " ac3=" + streamProbe.hasAc3Audio
                    + " dts=" + streamProbe.hasDtsAudio
                    + " truehd=" + streamProbe.hasTrueHdAudio
                    + " atmos=" + streamProbe.hasAtmosLikeAudio
                    + " requiresHdr=" + dvDecision.requiresHdrOutput
                    + " routeRequiresHdr=" + dvDecision.requiresHdrOutput
                    + " compatMode=" + dvDecision.compatMode
                    + " summary=" + streamProbe.summary);
            try {
                String outputMode = dvDecision.compatMode;
                if (TextUtils.isEmpty(outputMode)) {
                    outputMode = dvDecision.requiresHdrOutput ? "base-hdr" : "sdr";
                }
                playbackPlayerCfg.put("dvm", outputMode);
            } catch (JSONException e) {
                LOG.e("echo-dolby-route put compat mode failed: " + e.getMessage());
            }
            final int resolvedPlayerType = dvDecision.useCompatPlayer ? dvDecision.playerType : requestedPlayerType;
            if (resolvedPlayerType != playbackPlayerCfg.optInt("pl", PlayerHelper.PLAYER_TYPE_SYSTEM)) {
                try {
                    playbackPlayerCfg.put("pl", resolvedPlayerType);
                    LOG.i("echo-dolby-route player=" + resolvedPlayerType + " reason=" + dvDecision.reason);
                } catch (JSONException e) {
                    LOG.e("echo-dolby-route put failed: " + e.getMessage());
                }
            }
            configureReleaseSettleForPlayback(dvDecision.requiresHdrOutput, resolvedPlayerType);
            boolean released = forceReleaseCurrentPlayer("fragment-new-url");
            if (!isCurrentPlayGeneration(requestGeneration)) {
                logStalePlayback("after-release-before-seturl", requestGeneration);
                return;
            }
            Runnable startResolvedPlayback = new Runnable() {
                @Override
                public void run() {
                    startResolvedPlaybackOnMain(preflight.url, playbackPlayerCfg, finalActiveHeaders,
                            dvDecision, streamProbe, resolvedPlayerType, requestGeneration);
                }
            };
            if (released && delayPlaybackUntilPlayerReleaseSettled(startResolvedPlayback, requestGeneration, "fragment-new-url")) {
                return;
            }
            startResolvedPlayback.run();
        } catch (Throwable th) {
            handlePlayerStartFailure(th);
        }
    }

    private void startResolvedPlaybackOnMain(String sourceUrl,
                                             JSONObject playbackPlayerCfg,
                                             HashMap<String, String> activeHeaders,
                                             DolbyVisionPlaybackRouter.Decision dvDecision,
                                             VideoStreamProbe.Result streamProbe,
                                             int resolvedPlayerType,
                                             int requestGeneration) {
        if (!isCurrentPlayGeneration(requestGeneration) || mVideoView == null) {
            logStalePlayback("start-resolved", requestGeneration);
            return;
        }
        try {
            String url = sourceUrl;
            clearResolvedSubtitleTrackCache();
            lastPlayLooksLikeDolbyVision = dvDecision.looksLikeDolbyVision;
            useHdrFallbackPlayer = dvDecision.preferHdrFallback;
            useSdrFallbackPlayer = dvDecision.preferSdrFallback;
            currentPlaybackRequiresHdrOutput = dvDecision.requiresHdrOutput;
            currentPlaybackUsesNativeJava64DolbyVision = App.isJava64Build()
                    && !(requireActivity() instanceof BaseActivity
                    && ((BaseActivity) requireActivity()).isTvDevice())
                    && PlayerHelper.isSystemPlayerType(resolvedPlayerType)
                    && ("native-dolby-vision".equals(dvDecision.reason)
                    || "native-dolby-vision-java64".equals(dvDecision.reason));
            applySubtitleToneForCurrentPlayback();
            int activePlayerType = playbackPlayerCfg.optInt("pl", PlayerHelper.PLAYER_TYPE_SYSTEM);
            markHdrOutputRequested(playbackPlayerCfg, dvDecision.requiresHdrOutput);
            prepareHdrWindowForPlaybackStart(activePlayerType, dvDecision.requiresHdrOutput,
                    "fragment-player-" + resolvedPlayerType);
            if (url.startsWith("data:application/dash+xml;base64,")) {
                PlayerHelper.updateCfg(mVideoView, playbackPlayerCfg);
                App.getInstance().setDashData(url.split("base64,")[1]);
                url = ControlManager.get().getAddress(true) + "dash/proxy.mpd";
            } else {
                PlayerHelper.updateCfg(mVideoView, playbackPlayerCfg);
            }
            mVideoView.setProgressKey(getPlaybackProgressKeyForPersistence());
            boolean systemPlayer = PlayerHelper.isSystemPlayerType(activePlayerType);
            String playbackUrl = PlayerHelper.isBuiltInCompatPlayerType(activePlayerType)
                    ? PlaybackUrlNormalizer.resolveCompatPlaybackUrl(url, activeHeaders, false)
                    : (systemPlayer
                    ? PlaybackUrlNormalizer.resolveSystemPlaybackUrl(url, activeHeaders, false)
                    : PlaybackUrlNormalizer.resolvePlaybackUrl(url, activeHeaders, false));
            if (systemPlayer) {
                pendingSystemFallbackSourceUrl = url;
                pendingSystemFallbackHeaders = activeHeaders == null ? null : new HashMap<>(activeHeaders);
                systemFallbackTried = false;
            } else {
                pendingSystemFallbackSourceUrl = null;
                pendingSystemFallbackHeaders = null;
                systemFallbackTried = false;
            }
            LOG.i("echo-system-route player=" + activePlayerType
                    + " source=" + safeLogSnippet(url)
                    + " playback=" + safeLogSnippet(playbackUrl));
            if (PlayerHelper.isBuiltInCompatPlayerType(activePlayerType)) {
                com.github.tvbox.osc.player.MPVCompatManager.setCurrentPlayIsDolbyVision(dvDecision.looksLikeDolbyVision);
            }
            if (activeHeaders != null) {
                mVideoView.setUrl(playbackUrl, activeHeaders);
            } else {
                mVideoView.setUrl(playbackUrl);
            }
            currentPlaybackUrl = playbackUrl;
            currentPlaybackHeaders = activeHeaders == null ? null : new HashMap<>(activeHeaders);
            rememberCurrentPlaybackProbe(playbackUrl, streamProbe);
            startPlayTimeout(playbackUrl);
            mVideoView.start();
        } catch (Throwable th) {
            handlePlayerStartFailure(th);
        }
    }

    private PlaybackPreflight buildPlaybackPreflight(String rawUrl, HashMap<String, String> rawHeaders) {
        try {
            HashMap<String, String> activeHeaders = rawHeaders == null ? null : new HashMap<>(rawHeaders);
            PlaybackUrlNormalizer.UrlWithHeaders split = PlaybackUrlNormalizer.splitUrlAndHeaders(rawUrl, activeHeaders);
            String probeUrl = PlaybackUrlNormalizer.normalizeHttpUrl(split.url == null ? "" : split.url.trim());
            HashMap<String, String> mergedHeaders = new HashMap<>();
            if (split.headers != null) {
                mergedHeaders.putAll(split.headers);
            }
            activeHeaders = mergedHeaders.isEmpty() ? null : mergedHeaders;
            if (!TextUtils.isEmpty(probeUrl) && PlaybackUrlNormalizer.isHlsLike(probeUrl)) {
                VideoStreamProbe.Result probe = VideoStreamProbe.Result.unknown("skip-hls-probe");
                DolbyVisionPlaybackRouter.Decision decision = DolbyVisionPlaybackRouter.resolve(mContext,
                        PlayerHelper.PLAYER_TYPE_SYSTEM, probeUrl, activeHeaders, probe,
                        buildContainerHintText(rawUrl, probeUrl));
                LOG.i("echo-probe-prefetch fragment skip-hls url=" + safeLogSnippet(probeUrl));
                return new PlaybackPreflight(rawUrl, probeUrl, activeHeaders, probe, decision);
            }
            String probeTargetUrl = PlaybackUrlNormalizer.resolveSystemPlaybackUrl(probeUrl, activeHeaders, false);
            if (TextUtils.isEmpty(probeTargetUrl)) {
                probeTargetUrl = probeUrl;
            }
            if (!TextUtils.equals(probeTargetUrl, probeUrl)) {
                LOG.i("echo-probe-prefetch fragment align-system-url src="
                        + safeLogSnippet(probeUrl)
                        + " target=" + safeLogSnippet(probeTargetUrl));
            }
            VideoStreamProbe.Result probe = VideoStreamProbe.probeForPlaybackPreflight(
                    mContext,
                    probeTargetUrl,
                    activeHeaders,
                    resolveProbeTimeoutMs(probeTargetUrl));
            DolbyVisionPlaybackRouter.Decision decision = DolbyVisionPlaybackRouter.resolve(mContext,
                    PlayerHelper.PLAYER_TYPE_SYSTEM, probeUrl, activeHeaders, probe,
                    buildContainerHintText(rawUrl, probeUrl, probeTargetUrl));
            LOG.i("echo-probe-prefetch fragment probed=" + probe.probed
                    + " dv=" + probe.hasDolbyVision
                    + " hdr10=" + probe.hasHdr10
                    + " hdr10Plus=" + probe.hasHdr10Plus
                    + " dvProfile=" + probe.dolbyVisionProfile
                    + " hdr10Base=" + probe.hasHdr10BaseLayer
                    + " matroska=" + probe.isMatroska
                    + " videoMime=" + probe.primaryVideoMime
                    + " audioMime=" + probe.primaryAudioMime
                    + " avc=" + probe.hasAvcVideo
                    + " hevc=" + probe.hasHevcVideo
                    + " eac3=" + probe.hasEac3Audio
                    + " ac3=" + probe.hasAc3Audio
                    + " dts=" + probe.hasDtsAudio
                    + " truehd=" + probe.hasTrueHdAudio
                    + " atmos=" + probe.hasAtmosLikeAudio
                    + " target=" + safeLogSnippet(probeTargetUrl)
                    + " summary=" + probe.summary);
            return new PlaybackPreflight(rawUrl, probeUrl, activeHeaders, probe, decision);
        } catch (Throwable th) {
            LOG.e("echo-probe-prefetch fragment failed: " + th.getMessage());
            VideoStreamProbe.Result probe = VideoStreamProbe.Result.unknown("preflight-" + th.getClass().getSimpleName());
            DolbyVisionPlaybackRouter.Decision decision = DolbyVisionPlaybackRouter.resolve(mContext,
                    PlayerHelper.PLAYER_TYPE_SYSTEM, rawUrl, rawHeaders, probe,
                    buildContainerHintText(rawUrl));
            return new PlaybackPreflight(rawUrl,
                    PlaybackUrlNormalizer.normalizeHttpUrl(rawUrl == null ? "" : rawUrl.trim()),
                    rawHeaders, probe, decision);
        }
    }

    private void prepareInternalSubtitleOverlay(AbstractPlayer mediaPlayer) {
        if (mController == null || mController.mSubtitleView == null) {
            return;
        }
        mController.mSubtitleView.stop();
        mController.mSubtitleView.reset();
        mController.mSubtitleView.prepareForInternalSubtitle();
        mController.mSubtitleView.bindToMediaPlayer(mediaPlayer);
        applySubtitleToneForCurrentPlayback();
        if (mediaPlayer instanceof AndroidMediaPlayer && !mController.mSubtitleView.hasInternal) {
            SystemPlayerTrackManager.clearSubtitleSelections((AndroidMediaPlayer) mediaPlayer, null);
        }
        if (mediaPlayer instanceof MPVCompatPlayer) {
            bindMpvSubtitleText((MPVCompatPlayer) mediaPlayer);
        }
    }

    private boolean shouldIgnoreLateParsePlaybackResult(int generation, @Nullable String candidateUrl) {
        if (!isCurrentPlayGeneration(generation)) {
            return true;
        }
        if (!playbackRenderedFirstFrame || TextUtils.isEmpty(currentPlaybackUrl) || TextUtils.isEmpty(candidateUrl)) {
            return false;
        }
        String active = PlaybackUrlNormalizer.normalizeHttpUrl(currentPlaybackUrl.trim());
        String candidate = PlaybackUrlNormalizer.normalizeHttpUrl(candidateUrl.trim());
        boolean sameUrl = TextUtils.equals(active, candidate);
        if (!sameUrl) {
            LOG.i("echo-parse-ignore late-result active=" + safeLogSnippet(active)
                    + " candidate=" + safeLogSnippet(candidate));
        }
        return !sameUrl;
    }

    private void bindMpvSubtitleText(MPVCompatPlayer player) {
        if (player == null || mController == null || mController.mSubtitleView == null) {
            return;
        }
        player.setOnSubtitleTextListener(text -> {
            if (mHandler == null || mController == null || mController.mSubtitleView == null) {
                return;
            }
            mHandler.post(() -> {
                if (mController == null || mController.mSubtitleView == null || !mController.mSubtitleView.isInternal) {
                    return;
                }
                mController.mSubtitleView.onSubtitleChanged(SystemPlayerTrackManager.createInternalSubtitle(text));
            });
        });
        player.setOnRuntimeVideoModeListener((hdr, dolbyVision, outputMode, reason) -> {
            if (mHandler == null) {
                return;
            }
            mHandler.post(() -> promoteRuntimeHdrState(hdr, dolbyVision, outputMode, "fragment-" + reason));
        });
    }

    private void promoteRuntimeHdrState(boolean hdr, boolean dolbyVision, String outputMode, String reason) {
        if ((!hdr && !dolbyVision) || mVideoView == null) {
            return;
        }
        boolean changed = !currentPlaybackRequiresHdrOutput;
        currentPlaybackRequiresHdrOutput = true;
        if (dolbyVision) {
            lastPlayLooksLikeDolbyVision = true;
        }
        if (!TextUtils.isEmpty(outputMode)) {
            try {
                if (mVodPlayerCfg != null) {
                    mVodPlayerCfg.put("dvm", outputMode);
                }
            } catch (JSONException e) {
                LOG.e("echo-runtime-hdr fragment put dvm failed: " + e.getMessage());
            }
        }
        applySubtitleToneForCurrentPlayback();
        syncHdrWindowForCurrentPlayback(reason + (changed ? "-promote" : "-refresh"));
    }

    private long resolveProbeTimeoutMs(String probeUrl) {
        if (TextUtils.isEmpty(probeUrl)) {
            return 2500L;
        }
        String lower = probeUrl.toLowerCase();
        if (lower.contains("127.0.0.1")
                || lower.contains("localhost")
                || lower.contains(".mkv")
                || lower.contains(".webm")) {
            return 4500L;
        }
        return 4500L;
    }

    private void handlePlayerStartFailure(Throwable th) {
        LOG.e("echo-player-start-failed " + th.getClass().getSimpleName() + ": " + th.getMessage());
        cancelPlayTimeout();
        keepHdrWindowDuringPlayerSwitch = false;
        forceReleaseCurrentPlayer("fragment-start-failure");
        currentPlaybackUrl = null;
        currentPlaybackHeaders = null;
        clearCurrentPlaybackProbe();
        currentPlaybackRequiresHdrOutput = false;
        currentPlaybackUsesNativeJava64DolbyVision = false;
        applySubtitleToneForCurrentPlayback();
        HdrOutputManager.releaseHdr(mContext, "fragment-start-failure");
        errorWithRetry("播放器初始化失败", false);
    }

    private boolean forceReleaseCurrentPlayer(String reason) {
        cancelPlayTimeout();
        boolean releasedPlayer = false;
        if (currentPlaybackUrl != null || playbackRenderedFirstFrame || currentPlaybackRequiresHdrOutput) {
            lastReleasedPlaybackRequiresHdrOutput = currentPlaybackRequiresHdrOutput;
        }
        if (keepHdrWindowDuringPlayerSwitch) {
            LOG.i("echo-hdr-window preserve reason=" + reason
                    + " currentHdr=" + currentPlaybackRequiresHdrOutput);
        } else {
            HdrOutputManager.releaseHdr(mContext, reason + "-pre-release");
        }
        if (mVideoView != null) {
            releasedPlayer = mVideoView.getMediaPlayer() != null
                    || mVideoView.isPlaybackActive()
                    || mVideoView.isFullScreen()
                    || mVideoView.isFullScreenViewMoving();
            try {
                mVideoView.release();
            } catch (Throwable th) {
                LOG.e("echo-player-force-release view failed " + reason + ": " + th.getMessage());
            }
        }
        try {
            MPVCompatManager.hardResetForNextPlayback();
        } catch (Throwable th) {
            LOG.e("echo-player-force-release mpv failed " + reason + ": " + th.getMessage());
        }
        currentPlaybackUrl = null;
        currentPlaybackHeaders = null;
        clearCurrentPlaybackProbe();
        applySubtitleToneForCurrentPlayback();
        playbackRenderedFirstFrame = false;
        if (releasedPlayer) {
            lastPlayerReleaseAtMs = System.currentTimeMillis();
        }
        LOG.i("echo-player-force-release " + reason + " released=" + releasedPlayer);
        return releasedPlayer;
    }

    private void syncHdrWindowForCurrentPlayback(String reason) {
        if (currentPlaybackRequiresHdrOutput) {
            if (currentPlaybackUsesNativeJava64DolbyVision) {
                boolean fullscreenBrightnessBoost = shouldBoostHdrBrightness();
                HdrOutputManager.requestHdr(mContext,
                        reason + "-native-java64-dv",
                        fullscreenBrightnessBoost);
                LOG.i("echo-hdr-window native-java64-dv hdr reason=" + reason
                        + " boost=" + fullscreenBrightnessBoost);
                return;
            }
            boolean fullscreenBrightnessBoost = shouldBoostHdrBrightness();
            HdrOutputManager.requestHdr(mContext, reason, fullscreenBrightnessBoost);
        } else {
            if (keepHdrWindowDuringPlayerSwitch) {
                LOG.i("echo-hdr-window keep-during-switch reason=" + reason);
                return;
            }
            HdrOutputManager.releaseHdr(mContext, reason + "-sdr");
        }
    }

    private boolean shouldBoostHdrBrightness() {
        Activity activity = getActivity();
        boolean tvLike = activity instanceof BaseActivity && ((BaseActivity) activity).isTvDevice();
        if (!App.isJava64Build() || tvLike) {
            return mVideoView != null && mVideoView.isFullScreen();
        }
        return currentPlaybackRequiresHdrOutput;
    }

    private JSONObject copyPlayerConfigForPlayback() {
        try {
            return mVodPlayerCfg == null ? new JSONObject() : new JSONObject(mVodPlayerCfg.toString());
        } catch (Throwable th) {
            LOG.e("echo-player-config-copy failed: " + th.getMessage());
            return new JSONObject();
        }
    }

    private void rememberPlaybackOutputStateBeforeReset() {
        if (currentPlaybackUrl != null || playbackRenderedFirstFrame || currentPlaybackRequiresHdrOutput) {
            lastReleasedPlaybackRequiresHdrOutput = currentPlaybackRequiresHdrOutput;
        }
    }

    private void configureReleaseSettleForPlayback(boolean nextRequiresHdrOutput, int resolvedPlayerType) {
        keepHdrWindowDuringPlayerSwitch = nextRequiresHdrOutput;
        boolean systemPlayer = PlayerHelper.isSystemPlayerType(resolvedPlayerType);
        boolean compatPlayer = PlayerHelper.isBuiltInCompatPlayerType(resolvedPlayerType);
        boolean crossDynamicRangeSwitch = lastReleasedPlaybackRequiresHdrOutput != nextRequiresHdrOutput;
        boolean tvLike = requireActivity() instanceof BaseActivity
                && ((BaseActivity) requireActivity()).isTvDevice();
        if (compatPlayer && tvLike && (crossDynamicRangeSwitch || nextRequiresHdrOutput || lastReleasedPlaybackRequiresHdrOutput)) {
            nextPlayerReleaseSettleMs = 900L;
        } else if (compatPlayer && tvLike) {
            nextPlayerReleaseSettleMs = 650L;
        } else if (systemPlayer && crossDynamicRangeSwitch && tvLike) {
            nextPlayerReleaseSettleMs = 560L;
        } else if (systemPlayer && crossDynamicRangeSwitch) {
            nextPlayerReleaseSettleMs = 420L;
        } else if (systemPlayer && (lastReleasedPlaybackRequiresHdrOutput || nextRequiresHdrOutput)) {
            nextPlayerReleaseSettleMs = tvLike ? 420L : 340L;
        } else {
            nextPlayerReleaseSettleMs = PLAYER_RELEASE_SETTLE_MS;
        }
        LOG.i("echo-hdr-release-settle nextHdr=" + nextRequiresHdrOutput
                + " lastHdr=" + lastReleasedPlaybackRequiresHdrOutput
                + " cross=" + crossDynamicRangeSwitch
                + " tvLike=" + tvLike
                + " player=" + resolvedPlayerType
                + " compat=" + compatPlayer
                + " settleMs=" + nextPlayerReleaseSettleMs);
    }

    private void markHdrOutputRequested(JSONObject playbackPlayerCfg, boolean requiresHdrOutput) {
        int hdrOut = requiresHdrOutput ? 1 : 0;
        try {
            if (playbackPlayerCfg != null) {
                playbackPlayerCfg.put("hro", hdrOut);
            }
            if (mVodPlayerCfg != null) {
                mVodPlayerCfg.put("hro", hdrOut);
            }
        } catch (JSONException e) {
            LOG.e("echo-hdr-window mark hro failed: " + e.getMessage());
        }
    }

    private void prepareHdrWindowForPlaybackStart(int activePlayerType, boolean requiresHdrOutput, String reason) {
        if (!requiresHdrOutput) {
            HdrOutputManager.releaseHdr(mContext, reason + "-prepare-sdr");
            return;
        }
        if (currentPlaybackUsesNativeJava64DolbyVision) {
            boolean fullscreenBrightnessBoost = shouldBoostHdrBrightness();
            HdrOutputManager.requestHdr(mContext,
                    reason + "-native-java64-dv-prepare",
                    fullscreenBrightnessBoost);
            LOG.i("echo-hdr-window native-java64-dv hdr-prepare reason=" + reason
                    + " boost=" + fullscreenBrightnessBoost);
            return;
        }
        syncHdrWindowForCurrentPlayback(reason + "-prepare");
    }

    private void applyProbePlaybackHeaders(HashMap<String, String> headers, String playbackUrl, VideoStreamProbe.Result probe) {
        if (headers == null || probe == null) {
            return;
        }
        Activity activity = getActivity();
        boolean tvLike = activity instanceof BaseActivity && ((BaseActivity) activity).isTvDevice();
        HdrDeviceSupport.Capabilities caps = HdrDeviceSupport.query(mContext);
        boolean java64TouchPhone = App.isJava64Build() && !tvLike;
        if (probe.isMatroska || isMatroskaPlaybackUrl(playbackUrl)) {
            headers.put("X-TVBox-Probe-Container", "matroska");
        }
        headers.put("X-TVBox-Probe-AudioPassthrough",
                Hawk.get(HawkConfig.PLAYER_AUDIO_PASSTHROUGH, false) ? "1" : "0");
        headers.put("X-TVBox-Probe-AudioPassthroughAllowed",
                isAudioPassthroughAllowed(probe) ? "1" : "0");
        if (probe.hasDolbyVision) {
            headers.put("X-TVBox-Probe-DolbyVision", "1");
        }
        if (App.isJava64Build()
                && !tvLike
                && !TextUtils.isEmpty(probe.summary)
                && probe.summary.toLowerCase(Locale.US).contains("java64")) {
            headers.put("X-TVBox-Probe-Java64LocalProxyFast", "1");
        }
        if (caps.supportsNativeDolbyVisionRoute(java64TouchPhone)) {
            headers.put("X-TVBox-Probe-NativeDvDevice", "1");
        }
        if (probe.hasHdr10) {
            headers.put("X-TVBox-Probe-Hdr10", "1");
        }
        if (probe.hasHdr10Plus) {
            headers.put("X-TVBox-Probe-Hdr10Plus", "1");
        }
        if (shouldForceTv32SystemSafePcm(playbackUrl, probe)) {
            headers.put("X-TVBox-Probe-Tv32SafePcm", "1");
        }
    }

    private boolean shouldCreateInternalPlaybackHeaders(String playbackUrl, VideoStreamProbe.Result probe) {
        return probe != null && (probe.isMatroska
                || probe.hasDolbyVision
                || probe.hasHdr10
                || probe.hasHdr10Plus
                || shouldForceTv32SystemSafePcm(playbackUrl, probe))
                || isMatroskaPlaybackUrl(playbackUrl);
    }

    private boolean shouldForceTv32SystemSafePcm(String playbackUrl, VideoStreamProbe.Result probe) {
        if (mContext == null || probe == null || !ScreenUtils.isTv32Device(mContext)) {
            return false;
        }
        if (!isLocalProxyPlayUrl(playbackUrl) || PlaybackUrlNormalizer.isHlsLike(playbackUrl)) {
            return false;
        }
        if (probe.hasDolbyVision || probe.hasHdr10 || probe.hasHdr10Plus) {
            return true;
        }
        return probe.hasHevcVideo && probe.hasImmersiveOrCompressedAudio();
    }

    private boolean isLocalProxyPlayUrl(String playbackUrl) {
        if (TextUtils.isEmpty(playbackUrl)) {
            return false;
        }
        String lower = playbackUrl.toLowerCase(Locale.US);
        boolean localHost = lower.startsWith("http://127.0.0.1")
                || lower.startsWith("https://127.0.0.1")
                || lower.startsWith("http://localhost")
                || lower.startsWith("https://localhost");
        return localHost && lower.contains("/proxy/play/");
    }

    private boolean isMatroskaPlaybackUrl(String playbackUrl) {
        if (TextUtils.isEmpty(playbackUrl)) {
            return false;
        }
        String lower = playbackUrl.toLowerCase();
        return lower.contains(".mkv") || lower.contains(".webm");
    }

    private boolean isAudioPassthroughAllowed(VideoStreamProbe.Result probe) {
        if (probe == null) {
            return false;
        }
        if (!Hawk.get(HawkConfig.PLAYER_AUDIO_PASSTHROUGH, false)) {
            return false;
        }
        if (!hasPassthroughAudio(probe)) {
            return false;
        }
        if (probe.hasTrueHdAudio || probe.hasAtmosLikeAudio) {
            LOG.i("echo-audio-passthrough blocked lossless-or-atmos audioMime=" + probe.primaryAudioMime);
            return false;
        }
        boolean supported = PlayerCapability.supportsAudioPassthrough(probe);
        if (!supported) {
            LOG.i("echo-audio-passthrough blocked unsupported-output audioMime=" + probe.primaryAudioMime
                    + " ac3=" + probe.hasAc3Audio
                    + " eac3=" + probe.hasEac3Audio
                    + " dts=" + probe.hasDtsAudio);
        }
        return supported;
    }

    private boolean hasPassthroughAudio(VideoStreamProbe.Result probe) {
        return probe != null
                && (probe.hasAc3Audio
                || probe.hasEac3Audio
                || probe.hasDtsAudio
                || probe.hasTrueHdAudio
                || probe.hasAtmosLikeAudio);
    }

    private void handleMissingPlayableUrl(String reason) {
        handleMissingPlayableUrl(reason, null);
    }

    private void handleMissingPlayableUrl(String reason, String userMessage) {
        LOG.i("echo-missing-play-url:" + reason + " msg=" + userMessage);
        cancelPlayTimeout();
        keepHdrWindowDuringPlayerSwitch = false;
        webPlayUrl = null;
        currentPlaybackUrl = null;
        currentPlaybackHeaders = null;
        currentPlaybackRequiresHdrOutput = false;
        currentPlaybackUsesNativeJava64DolbyVision = false;
        applySubtitleToneForCurrentPlayback();
        HdrOutputManager.releaseHdr(mContext, "fragment-missing-url");
        errorWithRetry(TextUtils.isEmpty(userMessage) ? "播放地址为空" : userMessage, false);
    }

    private boolean trySystemStreamProxyFallback(String reason) {
        LOG.i("echo-system-fallback disabled reason=" + reason);
        return false;
    }

    private String safeLogSnippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 220 ? value.substring(0, 220) : value;
    }

    private String extractPlayErrorMessage(JSONObject info) {
        if (info == null) {
            return "";
        }
        return firstMeaningfulPlayMessage(
                info.optString("errMsg", ""),
                info.optString("msg", ""),
                info.optString("message", ""),
                info.optString("error", "")
        );
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                String trimmed = value.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                return trimmed;
            }
        }
        return "";
    }

    private String firstMeaningfulPlayMessage(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                String trimmed = value.trim();
                if (shouldIgnorePlayMessage(trimmed)) {
                    continue;
                }
                return trimmed;
            }
        }
        return "";
    }

    private boolean shouldIgnorePlayMessage(String value) {
        if (TextUtils.isEmpty(value)) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || "null".equalsIgnoreCase(trimmed)
                || "exception:null".equalsIgnoreCase(trimmed)
                || "异常-null".equalsIgnoreCase(trimmed)
                || "异常: null".equalsIgnoreCase(trimmed)
                || "异常：null".equalsIgnoreCase(trimmed)) {
            return true;
        }
        String normalized = trimmed.toLowerCase(Locale.US);
        return "异常-time".equalsIgnoreCase(trimmed)
                || "异常: time".equalsIgnoreCase(trimmed)
                || "异常：time".equalsIgnoreCase(trimmed)
                || normalized.contains("exception:time")
                || normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("read timed out")
                || normalized.contains("connect timed out");
    }

    private boolean isSubtitleInitSettled(int generation) {
        return subtitleInitSettledForGeneration && isCurrentPlayGeneration(generation);
    }

    private void markSubtitleInitSettled(int generation, String reason) {
        if (!isCurrentPlayGeneration(generation)) {
            return;
        }
        if (!subtitleInitSettledForGeneration) {
            LOG.i("echo-subtitle init-settled gen=" + generation + " reason=" + reason);
        }
        subtitleInitSettledForGeneration = true;
        subtitleInitSeq.incrementAndGet();
    }

    private String buildContainerHintText(String... extraValues) {
        StringBuilder builder = new StringBuilder();
        try {
            if (mVodInfo != null
                    && mVodInfo.seriesMap != null
                    && mVodInfo.playFlag != null
                    && mVodInfo.seriesMap.get(mVodInfo.playFlag) != null
                    && mVodInfo.playIndex >= 0
                    && mVodInfo.playIndex < mVodInfo.seriesMap.get(mVodInfo.playFlag).size()) {
                VodInfo.VodSeries series = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                appendContainerHint(builder, series == null ? null : series.url);
            }
        } catch (Throwable ignored) {
        }
        if (extraValues != null) {
            for (String value : extraValues) {
                appendContainerHint(builder, value);
            }
        }
        return builder.toString();
    }

    private void appendContainerHint(StringBuilder builder, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        String trimmed = value.trim();
        if (!containsMatroskaOrVideoMarker(trimmed)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(trimmed);
    }

    private boolean containsMatroskaOrVideoMarker(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.US);
        return lower.contains(".mkv")
                || lower.contains(".mp4")
                || lower.contains(".webm");
    }

    private void initSubtitleView() {
        initSubtitleView(currentPlayGeneration(), 0);
    }

    private void scheduleSubtitleInitRetry(final int generation, final int attempt) {
        if (!isCurrentPlayGeneration(generation) || mHandler == null || isSubtitleInitSettled(generation)) {
            return;
        }
        final int seq = subtitleInitSeq.incrementAndGet();
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (seq != subtitleInitSeq.get()
                        || !isCurrentPlayGeneration(generation)
                        || isSubtitleInitSettled(generation)) {
                    logStalePlayback("subtitle-init", generation);
                    return;
                }
                initSubtitleView(generation, attempt);
            }
        }, attempt == 0 ? 0L : SUBTITLE_INIT_RETRY_DELAY_MS);
    }

    private boolean shouldDelayExternalSubtitleFallback(AbstractPlayer mediaPlayer,
                                                        TrackInfo trackInfo,
                                                        boolean userSelectedExternalSubtitle,
                                                        int attempt) {
        if (userSelectedExternalSubtitle || attempt >= SUBTITLE_INIT_MAX_ATTEMPTS) {
            return false;
        }
        if (mediaPlayer instanceof MPVCompatPlayer) {
            MPVCompatPlayer mpvPlayer = (MPVCompatPlayer) mediaPlayer;
            if (trackInfo == null || trackInfo.getSubtitle().isEmpty()) {
                return mpvPlayer.shouldDelaySubtitleSelection(attempt);
            }
            return mpvPlayer.shouldDelaySubtitleSelection(trackInfo.getSubtitle().size(), attempt);
        }
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            AndroidMediaPlayer systemPlayer = (AndroidMediaPlayer) mediaPlayer;
            int subtitleCount = trackInfo == null ? 0 : trackInfo.getSubtitle().size();
            return systemPlayer.shouldDelaySubtitleSelection(subtitleCount, attempt);
        }
        return false;
    }

    private boolean shouldKeepRetryingSubtitleInit(AbstractPlayer mediaPlayer,
                                                   TrackInfo trackInfo,
                                                   int attempt) {
        if (attempt >= SUBTITLE_INIT_MAX_ATTEMPTS) {
            return false;
        }
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            AndroidMediaPlayer systemPlayer = (AndroidMediaPlayer) mediaPlayer;
            int subtitleCount = trackInfo == null ? 0 : trackInfo.getSubtitle().size();
            return systemPlayer.shouldDelaySubtitleSelection(subtitleCount, attempt);
        }
        if (mediaPlayer instanceof MPVCompatPlayer) {
            MPVCompatPlayer mpvPlayer = (MPVCompatPlayer) mediaPlayer;
            if (trackInfo == null || trackInfo.getSubtitle().isEmpty()) {
                return mpvPlayer.shouldDelaySubtitleSelection(attempt);
            }
            return mpvPlayer.shouldDelaySubtitleSelection(trackInfo.getSubtitle().size(), attempt);
        }
        return false;
    }

    private void applyPreferredInternalSubtitleTrack(AbstractPlayer mediaPlayer, TrackInfo trackInfo) {
        if (mediaPlayer == null || trackInfo == null || trackInfo.getSubtitle().isEmpty()) {
            return;
        }
        TrackInfoBean preferredTrack = SystemPlayerTrackManager.findPreferredSubtitleTrackBean(trackInfo);
        if (preferredTrack == null || preferredTrack.trackId < 0) {
            return;
        }
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            LOG.i("echo-subtitle select internal fragment track=" + preferredTrack.trackId
                    + " name=" + preferredTrack.name + " lang=" + preferredTrack.language);
            if (SystemPlayerTrackManager.isMetadataOnlySubtitleTrack(preferredTrack)) {
                applyMappedSubtitleTrackAsync(preferredTrack, false);
            } else {
                ((AndroidMediaPlayer) mediaPlayer).selectTrack(preferredTrack.trackId);
            }
        } else if (mediaPlayer instanceof MPVCompatPlayer) {
            if (SystemPlayerTrackManager.isBitmapSubtitleTrack(preferredTrack)) {
                hideOverlaySubtitleView();
            } else {
                prepareInternalSubtitleOverlay(mediaPlayer);
            }
            LOG.i("echo-subtitle select mpv internal fragment track=" + preferredTrack.trackId
                    + " name=" + preferredTrack.name + " lang=" + preferredTrack.language);
            ((MPVCompatPlayer) mediaPlayer).selectSubtitleTrack(preferredTrack);
        }
    }

    private void initSubtitleView(int generation, int attempt) {
        if (!isCurrentPlayGeneration(generation)) {
            logStalePlayback("initSubtitleView", generation);
            return;
        }
        if (mVideoView == null || mController == null || mController.mSubtitleView == null) {
            return;
        }
        TrackInfo trackInfo;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        mController.mSubtitleView.hasInternal = false;
        trackInfo = resolveSubtitleTrackInfo(mediaPlayer);
        LOG.i("echo-subtitle track-scan fragment player="
                + (mediaPlayer == null ? "null" : mediaPlayer.getClass().getSimpleName())
                + " count=" + (trackInfo == null ? 0 : trackInfo.getSubtitle().size())
                + " attempt=" + attempt);
        if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
            mController.mSubtitleView.hasInternal = true;
        }
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            disableSystemSubtitleOverlayPassThrough(mediaPlayer);
        } else if (mediaPlayer instanceof MPVCompatPlayer) {
            bindMpvSubtitleText((MPVCompatPlayer) mediaPlayer);
        }
        mController.mSubtitleView.bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.mSubtitleView.setPlaySubtitleCacheKey(subtitleCacheKey);
        String subtitlePathCache = (String)CacheManager.getCache(MD5.string2MD5(subtitleCacheKey));
        LOG.i("echo-subtitle init fragment internal=" + mController.mSubtitleView.hasInternal
                + " attempt=" + attempt
                + " externalCount=" + (sourceSubtitles == null ? 0 : sourceSubtitles.size())
                + " cache=" + subtitlePathCache
                + " playSubtitle=" + playSubtitle);
        boolean userSelectedExternalSubtitle = hasUserSelectedExternalSubtitle(subtitlePathCache);
        if (shouldDelayExternalSubtitleFallback(mediaPlayer, trackInfo,
                userSelectedExternalSubtitle, attempt)) {
            scheduleSubtitleInitRetry(generation, attempt + 1);
            return;
        }
        boolean preferInternalSubtitle = shouldPreferInternalSubtitleByDefault();
        if (userSelectedExternalSubtitle) {
            applyExternalSubtitle(subtitlePathCache);
            markSubtitleInitSettled(generation, "user-external");
        } else {
            if (!preferInternalSubtitle && playSubtitle != null && playSubtitle.length() > 0) {
                applyExternalSubtitle(playSubtitle);
                markSubtitleInitSettled(generation, "play-subtitle");
            } else if (!preferInternalSubtitle && !sourceSubtitles.isEmpty()) {
                Subtitle preferred = SystemPlayerTrackManager.findPreferredExternalSubtitle(sourceSubtitles);
                if (preferred != null) {
                    applyExternalSubtitle(preferred.getUrl());
                    markSubtitleInitSettled(generation, "preferred-external");
                }
            } else {
                if (mController.mSubtitleView.hasInternal) {
                    TrackInfoBean preferredTrack = SystemPlayerTrackManager.findPreferredSubtitleTrackBean(trackInfo);
                    if (preferredTrack == null) {
                        LOG.i("echo-subtitle auto-select skipped fragment reliableTrack=false");
                        hideOverlaySubtitleView();
                        markSubtitleInitSettled(generation, "no-reliable-track");
                        return;
                    }
                    if (mediaPlayer instanceof AndroidMediaPlayer
                            && SystemPlayerTrackManager.isMetadataOnlySubtitleTrack(preferredTrack)) {
                        applyMappedSubtitleTrackAsync(preferredTrack, false);
                    } else if (mediaPlayer instanceof AndroidMediaPlayer
                            && SystemPlayerTrackManager.usesNativeSubtitleRenderer(preferredTrack)) {
                        disableSystemSubtitleOverlayPassThrough(mediaPlayer);
                        hideOverlaySubtitleView();
                        SystemPlayerTrackManager.selectTrack((AndroidMediaPlayer) mediaPlayer, preferredTrack);
                    } else {
                        prepareInternalSubtitleOverlay(mediaPlayer);
                        if (mediaPlayer instanceof AndroidMediaPlayer) {
                            enableSystemSubtitleOverlayPassThrough(mediaPlayer);
                        }
                        applyPreferredInternalSubtitleTrack(mediaPlayer, trackInfo);
                    }
                    markSubtitleInitSettled(generation, "internal-ready");
                } else {
                    LOG.i("echo-subtitle none fragment");
                    if (shouldKeepRetryingSubtitleInit(mediaPlayer, trackInfo, attempt)) {
                        scheduleSubtitleInitRetry(generation, attempt + 1);
                    } else {
                        markSubtitleInitSettled(generation, "no-internal-track");
                    }
                }
            }
        }
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.playResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject info) {
                webPlayUrl = null;
                if (info != null) {
                    try {
                        String resultProgressKey = info.optString("proKey", null);
                        if (!isCurrentProgressKey(resultProgressKey)) {
                            LOG.i("echo-play-stale playResult progress=" + resultProgressKey
                                    + " active=" + activeProgressKey);
                            return;
                        }
                        String resultRequestKey = info.optString("reqKey", null);
                        if (!isCurrentPlayRequestKey(resultRequestKey)) {
                            LOG.i("echo-play-stale playResult request=" + resultRequestKey
                                    + " active=" + activePlayRequestKey);
                            return;
                        }
                        final int generation = currentPlayGeneration();
                        if (!TextUtils.isEmpty(resultProgressKey)) {
                            progressKey = resultProgressKey;
                            activeProgressKey = resultProgressKey;
                        }
                        boolean parse = info.optString("parse", "1").equals("1");
                        boolean jx = info.optString("jx", "0").equals("1");
                        playSubtitle = info.optString("subt", "");
                        sourceSubtitles = SystemPlayerTrackManager.buildExternalSubtitleList(info.optJSONArray("subs"));
                        Subtitle preferredExternalSubtitle = SystemPlayerTrackManager.findPreferredExternalSubtitle(sourceSubtitles);
                        if (!shouldPreferInternalSubtitleByDefault()
                                && playSubtitle.isEmpty()
                                && preferredExternalSubtitle != null) {
                            playSubtitle = preferredExternalSubtitle.getUrl();
                        }
                        LOG.i("echo-subtitle playResult fragment externalCount=" + sourceSubtitles.size()
                                + " selected=" + playSubtitle);
                        subtitleCacheKey = info.optString("subtKey", null);
                        String playUrl = info.optString("playUrl", "");
                        String msg = extractPlayErrorMessage(info);
                        String flag = info.optString("flag");
                        String url = info.optString("url", "").trim();
                        if (TextUtils.isEmpty(url) && !msg.isEmpty()) {
                            Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
                        }
                        if (TextUtils.isEmpty(url)) {
                            handleMissingPlayableUrl("empty-play-result-url", msg);
                            return;
                        }
                        if(url.startsWith("[")){
                            url=mController.firstUrlByArray(url);
                        }
                        HashMap<String, String> headers = null;
                        webUserAgent = null;
                        webHeaderMap = null;
                        if (info.has("header")) {
                            try {
                                JSONObject hds = new JSONObject(info.getString("header"));
                                Iterator<String> keys = hds.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (headers == null) {
                                        headers = new HashMap<>();
                                    }
                                    headers.put(key, hds.getString(key));
                                    if (key.equalsIgnoreCase("user-agent")) {
                                        webUserAgent = hds.getString(key).trim();
                                    }
                                }
                                webHeaderMap = headers;
                            } catch (Throwable th) {
                                LOG.e("echo-playResult-header-parse-error fragment: " + th.getMessage());
                            }
                        }
                        if (parse || jx) {
                            if (TextUtils.isEmpty(url == null ? "" : url.trim())) {
                                handleMissingPlayableUrl("empty-parse-url");
                                return;
                            }
                            boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                            initParse(flag, userJxList, playUrl, url, generation);
                        } else {
                            mController.showParse(false);
                            String directUrl = (playUrl + url).trim();
                            if (TextUtils.isEmpty(directUrl)) {
                                handleMissingPlayableUrl("empty-direct-url");
                            } else {
                                playUrl(directUrl, headers, generation);
                            }
                        }
                    } catch (Throwable th) {
                        LOG.e("echo-playResult-observe-error fragment: " + th.getClass().getSimpleName() + ": " + th.getMessage() + " info=" + String.valueOf(info));
                        errorWithRetry("获取播放信息错误", false);
                    }
                } else {
                    LOG.e("echo-playResult-null fragment");
                    errorWithRetry("获取播放信息错误", false);
                }
            }
        });
    }

    public void setData(Bundle bundle) {
//        mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        mVodInfo = App.getInstance().getVodInfo();
        sourceKey = bundle.getString("sourceKey");
        sourceKey = firstNonEmpty(sourceKey, mVodInfo == null ? null : mVodInfo.sourceKey);
        if (mVodInfo != null && !TextUtils.isEmpty(sourceKey)) {
            mVodInfo.sourceKey = sourceKey;
        }
        sourceBean = ApiConfig.get().getSource(sourceKey);
        initPlayerCfg();
        triedLineFlags.clear();
        dolbyVisionFallbackTried = false;
        play(false);
    }

    private boolean tryDolbyVisionFallback() {
        if (!lastPlayLooksLikeDolbyVision || dolbyVisionFallbackTried) {
            return false;
        }
        dolbyVisionFallbackTried = true;
        if (!useHdrFallbackPlayer && !useSdrFallbackPlayer) {
            return false;
        }
        try {
            int fallbackPlayerType = PlayerHelper.getHdrCompatiblePlayerType();
            if (!PlayerHelper.getPlayerExist(fallbackPlayerType)) {
                return false;
            }
            mVodPlayerCfg.put("pl", fallbackPlayerType);
            mVodPlayerCfg.put("dvm", useHdrFallbackPlayer ? "base-hdr" : "sdr");
            PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg);
            LOG.i("echo-dolby-vision-fallback use player=" + fallbackPlayerType
                    + " hdr=" + useHdrFallbackPlayer + " sdr=" + useSdrFallbackPlayer);
            if (!TextUtils.isEmpty(webPlayUrl)) {
                goPlayUrl(webPlayUrl, webHeaderMap == null ? null : new HashMap<>(webHeaderMap), currentPlayGeneration());
            }
            return true;
        } catch (Throwable th) {
            LOG.e("echo-dolby-vision-fallback failed: " + th.getMessage());
            return false;
        }
    }

    private void initData() {
        /*Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {

        }*/
    }

    void initPlayerCfg() {
        try {
            mVodPlayerCfg = new JSONObject(mVodInfo.playerCfg);
        } catch (Throwable th) {
            mVodPlayerCfg = new JSONObject();
        }
        try {
            mVodPlayerCfg.put("pl", PlayerHelper.PLAYER_TYPE_SYSTEM);
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 1));
            }
            mVodPlayerCfg.put("sc", VideoView.SCREEN_SCALE_DEFAULT);
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
            if (!mVodPlayerCfg.has(HawkConfig.PLAYER_SELECTION_MANUAL)) {
                mVodPlayerCfg.put(HawkConfig.PLAYER_SELECTION_MANUAL, false);
            }
            mVodPlayerCfg.put(HawkConfig.PLAYER_SELECTION_MANUAL, true);
        } catch (Throwable th) {

        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    public boolean onBackPressed() {
        int requestedOrientation = requireActivity().getRequestedOrientation();
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            mController.mLandscapePortraitBtn.setText("竖屏");
        }
        if (dismissParseOverlayIfNeeded()) {
            return true;
        }
        if (mController.onBackPressed()) {
            persistPlaybackProgress();
            return true;
        }
        persistPlaybackProgress();
        return false;
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!shouldRouteKeyToController()) {
            return false;
        }
        if (event != null) {
            Log.i(TAG, "dispatchKey action=" + event.getAction()
                    + " code=" + event.getKeyCode()
                    + " full=" + (mVideoView != null && mVideoView.isFullScreen())
                    + " visualFull=" + isPlayerVisuallyFullScreen());
            if (mController.onKeyEvent(event)) {
                return true;
            }
        }
        return false;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!shouldRouteKeyToController()) {
            return false;
        }
        if (event !=null) {
            if (mController.onKeyDown(keyCode,event)) {
                return true;
            }
        }
        return false;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!shouldRouteKeyToController()) {
            return false;
        }
        if (event !=null) {
            if (mController.onKeyUp(keyCode,event)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mVideoView != null && PlayerHelper.isInternalPlayerType(mVodPlayerCfg == null ? -1 : mVodPlayerCfg.optInt("pl", -1))) {
            if (suppressPauseForFullScreenTransition && mVideoView.isFullScreen()) {
                LOG.i("echo-skipPauseForFullScreen fragment");
                suppressPauseForFullScreenTransition = false;
                return;
            }
            persistPlaybackProgress();
            mVideoView.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        suppressPauseForFullScreenTransition = false;
        if (mVideoView != null && PlayerHelper.isInternalPlayerType(mVodPlayerCfg == null ? -1 : mVodPlayerCfg.optInt("pl", -1))) {
            mVideoView.resume();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) {
            if (mVideoView != null) {
                persistPlaybackProgress();
                mVideoView.pause();
            }
        } else {
            if (mVideoView != null) {
                mVideoView.resume();
            }
        }
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playbackRequestSeq.incrementAndGet();
        playbackProbeExecutor.shutdownNow();
        subtitleWorkExecutor.shutdownNow();
        playbackPersistExecutor.shutdownNow();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        cancelPlayTimeout();
        EventBus.getDefault().unregister(this);
        if (mVideoView != null) {
            persistPlaybackProgress();
            forceReleaseCurrentPlayer("fragment-destroy-view");
            mVideoView = null;
        }
        currentPlaybackRequiresHdrOutput = false;
        currentPlaybackUsesNativeJava64DolbyVision = false;
        applySubtitleToneForCurrentPlayback();
        HdrOutputManager.releaseHdr(mContext, "fragment-destroy-view");
        stopLoadWebView(true);
        stopParse();
        mController.stopOther();
    }

    private VodInfo mVodInfo;
    private JSONObject mVodPlayerCfg;
    private String sourceKey;
    private SourceBean sourceBean;

    private void playNext(boolean isProgress) {
        triedLineFlags.clear();
        boolean hasNext;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasNext = false;
        } else {
            hasNext = mVodInfo.playIndex + 1 < mVodInfo.seriesMap.get(mVodInfo.playFlag).size();
        }
        if (!hasNext) {
            Toast.makeText(requireContext(), "已经是最后一集了!", Toast.LENGTH_SHORT).show();
            return;
        }else {
            mVodInfo.playIndex++;
        }
        play(false);
    }

    private void playPrevious() {
        triedLineFlags.clear();
        boolean hasPre = true;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasPre = false;
        } else {
            hasPre = mVodInfo.playIndex - 1 >= 0;
        }
        if (!hasPre) {
            Toast.makeText(requireContext(), "已经是第一集了!", Toast.LENGTH_SHORT).show();
            return;
        }
        mVodInfo.playIndex--;
        play(false);
    }

    private int autoRetryCount = 0;
    private long lastRetryTime = 0;  // 记录上次调用时间（毫秒）

    private boolean allowSwitchPlayer = true;
    private boolean hasAutoSwitchedPlayer = false;
    private java.util.Set<String> triedLineFlags = new java.util.HashSet<>();  // 记录已尝试过的线路
    private boolean lastPlayLooksLikeDolbyVision = false;
    private boolean dolbyVisionFallbackTried = false;
    private boolean useHdrFallbackPlayer = false;
    private boolean useSdrFallbackPlayer = false;
    private boolean currentPlaybackRequiresHdrOutput = false;
    boolean autoRetry() {
        LOG.i("echo-autoRetry disabled: keep current source and current player");
        autoRetryCount = 0;
        lastRetryTime = System.currentTimeMillis();
        allowSwitchPlayer = false;
        hasAutoSwitchedPlayer = false;
        triedLineFlags.clear();
        return false;
    }

    boolean tryNextLine() {
        LOG.i("echo-autoRetry line switch disabled");
        autoRetryCount = 0;
        triedLineFlags.clear();
        return false;
    }

    private VodInfo.VodSeries getCurrentSeries(String flag, int index) {
        if (flag == null || mVodInfo == null || mVodInfo.seriesMap == null) {
            return null;
        }
        List<VodInfo.VodSeries> currentList = mVodInfo.seriesMap.get(flag);
        if (currentList == null || currentList.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(index, currentList.size() - 1));
        return currentList.get(safeIndex);
    }

    private int findSameEpisodeIndex(VodInfo.VodSeries currentSeries, List<VodInfo.VodSeries> targetList, int fallbackIndex) {
        if (targetList == null || targetList.isEmpty()) {
            return 0;
        }
        if (currentSeries != null && !TextUtils.isEmpty(currentSeries.name)) {
            String currentName = normalizeEpisodeName(currentSeries.name);
            for (int i = 0; i < targetList.size(); i++) {
                VodInfo.VodSeries targetSeries = targetList.get(i);
                if (targetSeries != null && currentName.equals(normalizeEpisodeName(targetSeries.name))) {
                    return i;
                }
            }
            int currentEpisode = extractEpisodeNumber(currentSeries.name);
            if (currentEpisode >= 0) {
                for (int i = 0; i < targetList.size(); i++) {
                    VodInfo.VodSeries targetSeries = targetList.get(i);
                    if (targetSeries != null && extractEpisodeNumber(targetSeries.name) == currentEpisode) {
                        return i;
                    }
                }
            }
        }
        return Math.max(0, Math.min(fallbackIndex, targetList.size() - 1));
    }

    private String normalizeEpisodeName(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[\\[\\]【】()（）]", "")
                .replace("第", "")
                .replace("集", "")
                .replace("话", "")
                .replace("期", "");
    }

    private int extractEpisodeNumber(String name) {
        if (name == null) {
            return -1;
        }
        Matcher episodeMatcher = Pattern.compile("(?:第)?(\\d+)(?:集|话|期|$)").matcher(name);
        if (episodeMatcher.find()) {
            try {
                return Integer.parseInt(episodeMatcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(name);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    void autoRetryFromLoadFoundVideoUrls() {
        String videoUrl = loadFoundVideoUrls.poll();
        HashMap<String,String> header = loadFoundVideoUrlsHeader.get(videoUrl);
        playUrl(videoUrl, header, currentPlayGeneration());
    }

    void initParseLoadFound() {
        loadFoundCount.set(0);
        loadFoundVideoUrls = new LinkedList<String>();
        loadFoundVideoUrlsHeader = new HashMap<String, HashMap<String, String>>();
    }

    private void clearParseLoadFound() {
        loadFoundCount.set(0);
        if (loadFoundVideoUrls != null) {
            loadFoundVideoUrls.clear();
        }
        if (loadFoundVideoUrlsHeader != null) {
            loadFoundVideoUrlsHeader.clear();
        }
    }

    public void setPlayTitle(boolean show)
    {
        if(show){
            String playTitleInfo= "";
            if(mVodInfo!=null){
                playTitleInfo = mVodInfo.name + " " + mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex).name;
            }
            mController.setTitle(playTitleInfo);
        }else {
            mController.setTitle("");
        }
    }

    public boolean enterEmbeddedFullScreen() {
        if (mVideoView == null) {
            return false;
        }
        if (!mVideoView.isFullScreen()) {
            persistPlaybackProgress();
            mVideoView.startFullScreen();
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT);
            mVideoView.post(new Runnable() {
                @Override
                public void run() {
                    syncEmbeddedControllerMode();
                    if (mController != null) {
                        mController.setForceFullScreenInputMode(mVideoView != null && mVideoView.isFullScreen());
                        mController.syncFullScreenControlState();
                    }
                }
            });
        }
        return true;
    }

    public boolean isEmbeddedPlayerFullScreen() {
        return mVideoView != null && (mVideoView.isFullScreen() || (!App.isJava64Build() && isPlayerVisuallyFullScreen()));
    }

    public void syncEmbeddedControllerMode() {
        if (mController != null) {
            boolean fullScreenInput = App.isJava64Build()
                    ? (mVideoView != null && mVideoView.isFullScreen())
                    : (mVideoView != null && (mVideoView.isFullScreen() || isPlayerVisuallyFullScreen()));
            mController.setForceFullScreenInputMode(fullScreenInput);
            mController.setEmbeddedPreviewMode(!fullScreenInput);
        }
        if (mVideoView != null && !mVideoView.isFullScreen() && (!App.isJava64Build() && !isPlayerVisuallyFullScreen())) {
            restorePreviewPlayerFocus();
        }
    }

    private boolean shouldRouteKeyToController() {
        return mVideoView != null && (mVideoView.isFullScreen() || (!App.isJava64Build() && isPlayerVisuallyFullScreen()));
    }

    private boolean isPlayerVisuallyFullScreen() {
        if (mVideoView == null || mVideoView.getVisibility() != View.VISIBLE) {
            return false;
        }
        int width = mVideoView.getWidth();
        int height = mVideoView.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        View root = getActivity() == null || getActivity().getWindow() == null
                ? null
                : getActivity().getWindow().getDecorView();
        int rootWidth = root == null ? getResources().getDisplayMetrics().widthPixels : root.getWidth();
        int rootHeight = root == null ? getResources().getDisplayMetrics().heightPixels : root.getHeight();
        if (rootWidth <= 0 || rootHeight <= 0) {
            return false;
        }
        return width >= rootWidth * 0.90f && height >= rootHeight * 0.85f;
    }

    private void restorePreviewPlayerFocus() {
        if (mVideoView == null) {
            return;
        }
        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                if (mVideoView == null || mVideoView.isFullScreen()) {
                    return;
                }
                mVideoView.setFocusable(true);
                mVideoView.setFocusableInTouchMode(true);
                mVideoView.requestFocus();
                mVideoView.requestFocusFromTouch();
            }
        });
    }

    public void persistEmbeddedProgress() {
        persistPlaybackProgress();
    }

    public void play(boolean reset) {
        if(mVodInfo==null)return;
        if (delayPlayUntilFullScreenExitStable(reset)) {
            return;
        }
        VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
        String resolvedSourceKey = resolveActiveSourceKey();
        if (TextUtils.isEmpty(resolvedSourceKey)) {
            handleMissingPlayableUrl("missing-source-key", "播放源信息错误");
            return;
        }
        if (reset) {
            dolbyVisionFallbackTried = false;
        }
        lastPlayLooksLikeDolbyVision = false;
        useHdrFallbackPlayer = false;
        useSdrFallbackPlayer = false;
        rememberPlaybackOutputStateBeforeReset();
        currentPlaybackRequiresHdrOutput = false;
        currentPlaybackUsesNativeJava64DolbyVision = false;
        applySubtitleToneForCurrentPlayback();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodInfo));
        setTip("正在获取播放信息", true, false);
        String playTitleInfo = mVodInfo.name + " " + vs.name;
        mController.setTitle(playTitleInfo);

        stopParse();
        webPlayUrl = null;
        webHeaderMap = null;
        initParseLoadFound();
        allowSwitchPlayer=true;
        hasAutoSwitchedPlayer=false;
        mController.stopOther();
        sourceSubtitles.clear();
        playSubtitle = "";
        clearResolvedSubtitleTrackCache();
        if (mVideoView != null) {
            disableSystemSubtitleOverlayPassThrough(mVideoView.getMediaPlayer());
        }
        hideOverlaySubtitleView();
        // 先用上一集的 key 落盘进度并释放播放器，再切换到新集的 key，
        // 避免在重设 progressKey 之后再持久化，把旧进度错误写到新集（32 位预览播放器特有顺序）。
        if(mVideoView!=null) {
            persistPlaybackProgress();
            forceReleaseCurrentPlayer("fragment-play-switch");
        }
        subtitleCacheKey = resolvedSourceKey + "-" + mVodInfo.id + "-" + mVodInfo.playFlag + "-" + mVodInfo.playIndex+ "-" + vs.name + "-subt";
        progressKey = resolvedSourceKey + "|" + mVodInfo.id + "|" + mVodInfo.playFlag + "|" + mVodInfo.playIndex;
        final int generation = beginPlayGeneration(progressKey, subtitleCacheKey);
        activePlayRequestKey = buildPlayRequestKey(resolvedSourceKey, mVodInfo.playFlag, mVodInfo.playIndex, vs, generation);
        //重新播放清除现有进度
        bindPlaybackIdentity(resolvedSourceKey, mVodInfo.playFlag, mVodInfo.playIndex);
        if (reset) {
            CacheManager.delete(MD5.string2MD5(progressKey), 0);
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), 0);
        }else{
            try{
                int playerType = mVodPlayerCfg.getInt("pl");
                if(PlayerHelper.isBuiltInCompatPlayerType(playerType)){
                    mController.mSubtitleView.setVisibility(View.VISIBLE);
                }else {
                    mController.mSubtitleView.setVisibility(View.GONE);
                }
            }catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if(Jianpian.isJpUrl(vs.url)){//荐片地址特殊判断
            String jp_url= vs.url;
            mController.showParse(false);
            if(vs.url.startsWith("tvbox-xg:")){
                playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null, generation);
            }else {
                playUrl(Jianpian.JPUrlDec(jp_url), null, generation);
            }
            return;
        }
        if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
            @Override
            public void status(int code, String info) {
                if (!isCurrentPlayGeneration(generation)) {
                    return;
                }
                if (code < 0) {
                    setTip(info, false, true);
                } else {
                    setTip(info, true, false);
                }
            }

            @Override
            public void list(Map<Integer, String> urlMap) {
            }

            @Override
            public void play(String url) {
                playUrl(url, null, generation);
            }
        })) {
            mController.showParse(false);
            return;
        }
        sourceViewModel.getPlay(resolvedSourceKey, mVodInfo.playFlag, progressKey, vs.url, subtitleCacheKey, activePlayRequestKey);
    }

    private boolean delayPlayUntilFullScreenExitStable(final boolean reset) {
        if (mVideoView == null || mHandler == null || !mVideoView.isFullScreenViewMoving()) {
            fullscreenExitSwitchPending = false;
            return false;
        }
        if (fullscreenExitSwitchPending) {
            LOG.i("echo-play-delay-fullscreen-exit already-pending");
            return true;
        }
        fullscreenExitSwitchPending = true;
        LOG.i("echo-play-delay-fullscreen-exit wait-before-switch");
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                fullscreenExitSwitchPending = false;
                if (!isAdded() || mVideoView == null) {
                    return;
                }
                play(reset);
            }
        }, 520L);
        return true;
    }

    private String playSubtitle;
    private String subtitleCacheKey;
    private String progressKey;
    private String parseFlag;
    private String webUrl;
    private String webUserAgent;
    private HashMap<String, String > webHeaderMap;
    private String webPlayUrl;
    private String currentPlaybackUrl;
    private HashMap<String, String> currentPlaybackHeaders;
    private String currentPlaybackProbeUrl;
    private VideoStreamProbe.Result currentPlaybackProbe;

    private void initParse(String flag, boolean useParse, String playUrl, final String url) {
        initParse(flag, useParse, playUrl, url, currentPlayGeneration());
    }

    private void initParse(String flag, boolean useParse, String playUrl, final String url, int generation) {
        if (!isCurrentPlayGeneration(generation)) {
            logStalePlayback("initParse", generation);
            return;
        }
        activeParseGeneration = generation;
        parseFlag = flag;
        webUrl = url;
        ParseBean parseBean = null;
        mController.showParse(useParse);
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse();
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = new ParseBean();
                parseBean.setType(1);
                parseBean.setUrl(playUrl.substring(5));
            } else if (playUrl.startsWith("parse:")) {
                String parseRedirect = playUrl.substring(6);
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(parseRedirect)) {
                        parseBean = pb;
                        break;
                    }
                }
            }
            if (parseBean == null) {
                parseBean = new ParseBean();
                parseBean.setType(0);
                parseBean.setUrl(playUrl);
            }
        }
        doParse(parseBean, generation);
    }

    JSONObject jsonParse(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        //小窗版解析方法改到这了  之前那个位置data解析无效
        String url;
        if (jsonPlayData.has("data")) {
            url = jsonPlayData.getJSONObject("data").getString("url");
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) {
            url = "http:" + url;
        }
        if (!url.startsWith("http")) {
            return null;
        }
        JSONObject headers = new JSONObject();
        String ua = jsonPlayData.optString("user-agent", "");
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", " " + ua);
        }
        String referer = jsonPlayData.optString("referer", "");
        if (referer.trim().length() > 0) {
            headers.put("Referer", " " + referer);
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        return taskResult;
    }

    void startPlayTimeout(String playbackUrl) {
        cancelPlayTimeout();
        long timeoutMs = resolvePlayTimeoutMs(playbackUrl);
        LOG.i("echo-startPlayTimeout:" + timeoutMs + " url=" + playbackUrl);
        Message message = mHandler.obtainMessage(MSG_PLAY_TIMEOUT);
        message.arg1 = currentPlayGeneration();
        mHandler.sendMessageDelayed(message, timeoutMs);
    }

    void startPlayTimeout(String playbackUrl, long timeoutMs, String reason) {
        cancelPlayTimeout();
        LOG.i("echo-startPlayTimeout:" + timeoutMs + " reason=" + reason + " url=" + playbackUrl);
        Message message = mHandler.obtainMessage(MSG_PLAY_TIMEOUT);
        message.arg1 = currentPlayGeneration();
        mHandler.sendMessageDelayed(message, timeoutMs);
    }

    void cancelPlayTimeout() {
        mHandler.removeMessages(MSG_PLAY_TIMEOUT);
    }

    long resolvePlayTimeoutMs(String playbackUrl) {
        if (TextUtils.isEmpty(playbackUrl)) {
            return PLAY_TIMEOUT_MS;
        }
        String lower = playbackUrl.toLowerCase(Locale.US);
        if (lower.contains("127.0.0.1:6677/proxy/play/")
                || lower.contains("127.0.0.1:9978/proxy?go=stream")
                || lower.contains("/proxy/play/")) {
            if (PlayerHelper.isSystemPlayerType(mVodPlayerCfg == null ? -1 : mVodPlayerCfg.optInt("pl", -1))) {
                return PLAY_TIMEOUT_SYSTEM_PROXY_MS;
            }
            return PLAY_TIMEOUT_PROXY_MS;
        }
        return PLAY_TIMEOUT_MS;
    }

    void stopParse() {
        activeParseGeneration = 0;
        activeParseSessionId++;
        mHandler.removeMessages(MSG_PARSE_TIMEOUT);
        stopLoadWebView(false);
        clearParseLoadFound();
        OkGo.getInstance().cancelTag("json_jx");
        if (parseThreadPool != null) {
            try {
                parseThreadPool.shutdown();
                parseThreadPool = null;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    ExecutorService parseThreadPool;

    private void doParse(ParseBean pb) {
        doParse(pb, currentPlayGeneration());
    }

    private void doParse(ParseBean pb, int generation) {
        if (!isCurrentPlayGeneration(generation)) {
            logStalePlayback("doParse", generation);
            return;
        }
        stopParse();
        activeParseGeneration = generation;
        initParseLoadFound();
        if (pb.getType() == 4) {
            parseMix(pb,true, generation);
        }
        else if (pb.getType() == 0) {
            setTip("正在嗅探播放地址", true, false);
            mHandler.removeMessages(MSG_PARSE_TIMEOUT);
            Message message = mHandler.obtainMessage(MSG_PARSE_TIMEOUT);
            message.arg1 = generation;
            mHandler.sendMessageDelayed(message, 20 * 1000);
            if(pb.getExt()!=null){
                // 解析ext
                try {
                    HashMap<String, String> reqHeaders = new HashMap<>();
                    JSONObject jsonObject = new JSONObject(pb.getExt());
                    if (jsonObject.has("header")) {
                        JSONObject headerJson = jsonObject.optJSONObject("header");
                        Iterator<String> keys = headerJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (key.equalsIgnoreCase("user-agent")) {
                                webUserAgent = headerJson.getString(key).trim();
                            }else {
                                reqHeaders.put(key, headerJson.optString(key, ""));
                            }
                        }
                        if(reqHeaders.size()>0)webHeaderMap = reqHeaders;
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            loadWebView(pb.getUrl() + webUrl, generation);

        } else if (pb.getType() == 1) { // json 解析
            setTip("正在解析播放地址", true, false);
            // 解析ext
            HttpHeaders reqHeaders = new HttpHeaders();
            try {
                JSONObject jsonObject = new JSONObject(pb.getExt());
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            OkGo.<String>get(pb.getUrl() + mController.encodeUrl(webUrl))
                    .tag("json_jx")
                    .headers(reqHeaders)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (!isCurrentParseGeneration(generation)) {
                                logStalePlayback("json-parse-success", generation);
                                return;
                            }
                            String json = response.body();
                            try {
                                JSONObject rs = jsonParse(webUrl, json);
                                if (rs == null) {
                                    errorWithRetry("解析错误", false);
                                    return;
                                }
                                HashMap<String, String> headers = null;
                                if (rs.has("header")) {
                                    try {
                                        JSONObject hds = rs.getJSONObject("header");
                                        Iterator<String> keys = hds.keys();
                                        while (keys.hasNext()) {
                                            String key = keys.next();
                                            if (headers == null) {
                                                headers = new HashMap<>();
                                            }
                                            headers.put(key, hds.getString(key));
                                        }
                                    } catch (Throwable th) {

                                    }
                                }
                                String resolvedUrl = rs.getString("url");
                                if (shouldIgnoreLateParsePlaybackResult(generation, resolvedUrl)) {
                                    return;
                                }
                                playUrl(resolvedUrl, headers, generation);
                            } catch (Throwable e) {
                                if (!isCurrentParseGeneration(generation)) {
                                    logStalePlayback("json-parse-catch", generation);
                                    return;
                                }
                                e.printStackTrace();
                                errorWithRetry("解析错误", false);
//                                setTip("解析错误", false, true);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            if (!isCurrentParseGeneration(generation)) {
                                logStalePlayback("json-parse-error", generation);
                                return;
                            }
                            errorWithRetry("解析错误", false);
//                            setTip("解析错误", false, true);
                        }
                    });
        } else if (pb.getType() == 2) { // json 扩展
            setTip("正在解析播放地址", true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                if (p.getType() == 1) {
                    jxs.put(p.getName(), p.mixUrl());
                }
            }
            parseThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentParseGeneration(generation)) {
                        logStalePlayback("json-ext-before", generation);
                        return;
                    }
                    JSONObject rs = ApiConfig.get().jsonExt(pb.getUrl(), jxs, webUrl);
                    if (!isCurrentParseGeneration(generation)) {
                        logStalePlayback("json-ext-after", generation);
                        return;
                    }
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误", false);
                        setTip("解析错误", false, true);
                    } else {
                        HashMap<String, String> headers = null;
                        if (rs.has("header")) {
                            try {
                                JSONObject hds = rs.getJSONObject("header");
                                Iterator<String> keys = hds.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (headers == null) {
                                        headers = new HashMap<>();
                                    }
                                    headers.put(key, hds.getString(key));
                                }
                            } catch (Throwable th) {

                            }
                        }
                        if (rs.has("jxFrom")) {
                            if(!isAdded())return;
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(mContext, "解析来自:" + rs.optString("jxFrom"), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        boolean parseWV = rs.optInt("parse", 0) == 1;
                        if (parseWV) {
                            String wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            loadUrl(wvUrl, generation);
                        } else {
                            String resolvedUrl = rs.optString("url", "");
                            if (shouldIgnoreLateParsePlaybackResult(generation, resolvedUrl)) {
                                return;
                            }
                            playUrl(resolvedUrl, headers, generation);
                        }
                    }
                }
            });
        } else if (pb.getType() == 3) { // json 聚合
             parseMix(pb,false, generation);
        }
    }

    private void parseMix(ParseBean pb,boolean isSuper)
    {
        parseMix(pb, isSuper, currentPlayGeneration());
    }

    private void parseMix(ParseBean pb,boolean isSuper,int generation)
    {
        if (!isCurrentParseGeneration(generation)) {
            logStalePlayback("parseMix", generation);
            return;
        }
        setTip("正在解析播放地址", true, false);
        parseThreadPool = Executors.newSingleThreadExecutor();
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        LinkedHashMap<String, String> json_jxs = new LinkedHashMap<>();
        String extendName = "";
        for (ParseBean p : ApiConfig.get().getParseBeanList()) {
            HashMap<String, String> data = new HashMap<String, String>();
            data.put("url", p.getUrl());
            if (p.getUrl().equals(pb.getUrl())) {
                extendName = p.getName();
            }
            data.put("type", p.getType() + "");
            data.put("ext", p.getExt());
            jxs.put(p.getName(), data);

            if (p.getType() == 1) {
                json_jxs.put(p.getName(), p.mixUrl());
            }
        }
        String finalExtendName = extendName;
        parseThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentParseGeneration(generation)) {
                    logStalePlayback("parseMix-before", generation);
                    return;
                }
                if(isSuper){
                    //并发执行 嗅探和json
                    JSONObject rs = SuperParse.parse(jxs, parseFlag+"123", webUrl);
                    if (!isCurrentParseGeneration(generation)) {
                        logStalePlayback("parseMix-super-after", generation);
                        return;
                    }
                    if (!rs.has("url") || rs.optString("url").isEmpty()) {
                        setTip("解析错误", false, true);
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                webUserAgent = rs.optString("ua").trim();
                            }
                            setTip("超级解析中", true, false);

                            if(!isAdded())return;
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isCurrentParseGeneration(generation)) {
                                        logStalePlayback("parseMix-super-ui", generation);
                                        return;
                                    }
                                    String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                                    stopParse();
                                    activeParseGeneration = generation;
                                    mHandler.removeMessages(MSG_PARSE_TIMEOUT);
                                    Message message = mHandler.obtainMessage(MSG_PARSE_TIMEOUT);
                                    message.arg1 = generation;
                                    mHandler.sendMessageDelayed(message, 20 * 1000);
                                    loadWebView(mixParseUrl, generation);
                                }
                            });
                            parseThreadPool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isCurrentParseGeneration(generation)) {
                                        logStalePlayback("parseMix-super-json-before", generation);
                                        return;
                                    }
                                    JSONObject res = SuperParse.doJsonJx(webUrl);
                                    rsJsonJX(res, true, generation);
                                }
                            });
                        } else {
                            rsJsonJX(rs,false, generation);
                        }
                    }
                }else {
                    JSONObject rs = ApiConfig.get().jsonExtMix(parseFlag + "111", pb.getUrl(), finalExtendName, jxs, webUrl);
                    if (!isCurrentParseGeneration(generation)) {
                        logStalePlayback("parseMix-after", generation);
                        return;
                    }
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误", false);
                        setTip("解析错误", false, true);
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                webUserAgent = rs.optString("ua").trim();
                            }
                            if(!isAdded())return;
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isCurrentParseGeneration(generation)) {
                                        logStalePlayback("parseMix-ui", generation);
                                        return;
                                    }
                                    String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                                    stopParse();
                                    activeParseGeneration = generation;
                                    setTip("正在嗅探播放地址", true, false);
                                    mHandler.removeMessages(MSG_PARSE_TIMEOUT);
                                    Message message = mHandler.obtainMessage(MSG_PARSE_TIMEOUT);
                                    message.arg1 = generation;
                                    mHandler.sendMessageDelayed(message, 20 * 1000);
                                    loadWebView(mixParseUrl, generation);
                                }
                            });
                        } else {
                            rsJsonJX(rs,false, generation);
                        }
                    }
                }
            }
        });
    }

    private void rsJsonJX(JSONObject rs,boolean isSuper){
        rsJsonJX(rs, isSuper, currentPlayGeneration());
    }

    private void rsJsonJX(JSONObject rs,boolean isSuper,int generation){
        if (!isCurrentParseGeneration(generation)) {
            logStalePlayback("rsJsonJX", generation);
            return;
        }
        if(isSuper){
            if(rs==null || !rs.has("url"))return;
            stopLoadWebView(false);
        }
        HashMap<String, String> headers = null;
        if (rs.has("header")) {
            try {
                JSONObject hds = rs.getJSONObject("header");
                Iterator<String> keys = hds.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (headers == null) {
                        headers = new HashMap<>();
                    }
                    headers.put(key, hds.getString(key));
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        if (rs.has("jxFrom")) {
            if(!isAdded())return;
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mContext, "解析来自:" + rs.optString("jxFrom"), Toast.LENGTH_SHORT).show();
                }
            });
        }
        String resolvedUrl = rs.optString("url", "");
        if (shouldIgnoreLateParsePlaybackResult(generation, resolvedUrl)) {
            return;
        }
        playUrl(resolvedUrl, headers, generation);
    }
    public MyVideoView getPlayer() {
        return mVideoView;
    }

    // webview
    private XWalkView mXwalkWebView;
    private WebView mSysWebView;
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private LinkedList<String> loadFoundVideoUrls = new LinkedList<>();
    private HashMap<String, HashMap<String, String>> loadFoundVideoUrlsHeader = new HashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);

    void loadWebView(String url) {
        loadWebView(url, currentPlayGeneration());
    }

    private void loadWebView(String url, int generation) {
        if (!isCurrentParseGeneration(generation)) {
            logStalePlayback("loadWebView", generation);
            return;
        }
        if (mSysWebView == null && mXwalkWebView == null) {
            boolean useSystemWebView = Hawk.get(HawkConfig.PARSE_WEBVIEW, true);
            if (!useSystemWebView) {
                XWalkUtils.tryUseXWalk(mContext, new XWalkUtils.XWalkState() {
                    @Override
                    public void success() {
                        initWebView(false);
                        loadUrl(url, generation);
                    }

                    @Override
                    public void fail() {
                        Toast.makeText(mContext, "XWalkView不兼容，已替换为系统自带WebView", Toast.LENGTH_SHORT).show();
                        initWebView(true);
                        loadUrl(url, generation);
                    }

                    @Override
                    public void ignore() {
                        Toast.makeText(mContext, "XWalkView运行组件未下载，已替换为系统自带WebView", Toast.LENGTH_SHORT).show();
                        initWebView(true);
                        loadUrl(url, generation);
                    }
                });
            } else {
                initWebView(true);
                loadUrl(url, generation);
            }
        } else {
            loadUrl(url, generation);
        }
    }

    void initWebView(boolean useSystemWebView) {
        if (useSystemWebView) {
            mSysWebView = new MyWebView(mContext);
            configWebViewSys(mSysWebView);
        } else {
            mXwalkWebView = new MyXWalkView(mContext);
            configWebViewX5(mXwalkWebView);
        }
    }

    void loadUrl(String url) {
        loadUrl(url, currentPlayGeneration());
    }

    private void loadUrl(String url, int generation) {
        if (!isCurrentParseGeneration(generation)) {
            logStalePlayback("loadUrl", generation);
            return;
        }
        final int parseSessionId = activeParseSessionId;
        if(!isAdded())return;
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentParseSession(generation, parseSessionId)) {
                    logStalePlayback("loadUrl-ui", generation);
                    return;
                }
                if (mXwalkWebView != null) {
                    mXwalkWebView.stopLoading();
                    mXwalkWebView.setTag(Long.valueOf(buildWebViewParseTag(generation, parseSessionId)));
                    if(webUserAgent != null) {
                        mXwalkWebView.getSettings().setUserAgentString(webUserAgent);
                    }
                    //mXwalkWebView.clearCache(true);
                    if(webHeaderMap != null){
                        mXwalkWebView.loadUrl(url,webHeaderMap);
                    }else {
                        mXwalkWebView.loadUrl(url);
                    }
                }
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    mSysWebView.setTag(Long.valueOf(buildWebViewParseTag(generation, parseSessionId)));
                    if(webUserAgent != null) {
                        mSysWebView.getSettings().setUserAgentString(webUserAgent);
                    }
                    //mSysWebView.clearCache(true);
                    if(webHeaderMap != null){
                        mSysWebView.loadUrl(url,webHeaderMap);
                    }else {
                        mSysWebView.loadUrl(url);
                    }
                }
            }
        });
    }

    void stopLoadWebView(boolean destroy) {
        if (mActivity == null) return;
        if(!isAdded())return;
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if (mXwalkWebView != null) {
                    mXwalkWebView.stopLoading();
                    mXwalkWebView.loadUrl("about:blank");
                    if (destroy) {
                        mXwalkWebView.clearCache(true);
                        mXwalkWebView.removeAllViews();
                        mXwalkWebView.onDestroy();
                        mXwalkWebView = null;
                    }
                }
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    mSysWebView.loadUrl("about:blank");
                    if (destroy) {
                        mSysWebView.clearCache(true);
                        mSysWebView.removeAllViews();
                        mSysWebView.destroy();
                        mSysWebView = null;
                    }
                }
            }
        });
    }

    private boolean dismissParseOverlayIfNeeded() {
        boolean hasParseOverlay = mSysWebView != null || mXwalkWebView != null;
        if (!hasParseOverlay) {
            return false;
        }
        stopParse();
        hideTip();
        return true;
    }

    boolean checkVideoFormat(String url) {
        try{
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean.getType() == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp != null && sp.manualVideoCheck()){
                    return sp.isVideoFormat(url);
                }
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        }catch (Exception e){
            return false;
        }
    }

    class MyWebView extends WebView {
        public MyWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (isTvHost() && mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayFragment.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }

    class MyXWalkView extends XWalkView {
        public MyXWalkView(Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (isTvHost() && mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayFragment.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebViewSys(WebView webView) {
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = Hawk.get(HawkConfig.DEBUG_OPEN, false)
                ? new ViewGroup.LayoutParams(800, 400) :
                new ViewGroup.LayoutParams(1, 1);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        if(!isAdded())return;
        requireActivity().addContentView(webView, layoutParams);
        /* 添加webView配置 */
        final WebSettings settings = webView.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            settings.setBlockNetworkImage(false);
        } else {
            settings.setBlockNetworkImage(true);
        }
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        /* 添加webView配置 */
        //设置编码
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUserAgentString(webView.getSettings().getUserAgentString());
//         settings.setUserAgentString(ANDROID_UA);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        SysWebClient mSysWebClient = new SysWebClient();
        webView.setWebViewClient(mSysWebClient);
        webView.setBackgroundColor(Color.BLACK);
    }

    private boolean isTvHost() {
        Activity activity = getActivity();
        return activity instanceof BaseActivity && ((BaseActivity) activity).isTvDevice();
    }

    private class SysWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
            sslErrorHandler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted( view,  url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            LOG.i("echo-onPageFinished url:" + url);
            if(!url.equals("about:blank")){
                mController.evaluateScript(sourceBean,url,view,null);
            }
        }

        WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers, int generation, int parseSessionId) {
            if (!isCurrentParseSession(generation, parseSessionId)) {
                logStalePlayback("web-check-video", generation);
                return null;
            }
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return new WebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i( "shouldInterceptLoadRequest filter:" + url);
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, headers);
                    LOG.i("echo-loadFoundVideoUrl:" + url );
                    if (loadFoundCount.incrementAndGet() == 1) {
                        if (!isCurrentParseSession(generation, parseSessionId)) {
                            logStalePlayback("web-intercept-late-hit", generation);
                            return null;
                        }
                        stopLoadWebView(false);
                        SuperParse.stopJsonJx();
                        url = loadFoundVideoUrls.poll();
                        mHandler.removeMessages(MSG_PARSE_TIMEOUT);
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if(!TextUtils.isEmpty(cookie))headers.put("Cookie", " " + cookie);//携带cookie
                        if (shouldIgnoreLateParsePlaybackResult(generation, url)) {
                            return AdBlocker.createEmptyResource();
                        }
                        playUrl(url, headers, generation);
                    }
                }
            }

            return ad || loadFoundCount.get() > 0 ?
                    AdBlocker.createEmptyResource() :
                    null;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
//            WebResourceResponse response = checkIsVideo(url, new HashMap<>());
            return null;
        }

        @Nullable
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            int generation = getWebViewGeneration(view);
            int parseSessionId = getWebViewParseSessionId(view);
            if (!isCurrentParseSession(generation, parseSessionId)) {
                logStalePlayback("web-intercept", generation);
                return null;
            }
            String url = request.getUrl().toString();
            LOG.i("echo-shouldInterceptRequest url:" + url);
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && hds.keySet().size() > 0) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k," " + hds.get(k));
                    }
                }
            }
            return checkIsVideo(url, webHeaders, generation, parseSessionId);
        }

        @Override
        public void onLoadResource(WebView webView, String url) {
            super.onLoadResource(webView, url);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebViewX5(XWalkView webView) {
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = Hawk.get(HawkConfig.DEBUG_OPEN, false)
                ? new ViewGroup.LayoutParams(800, 400) :
                new ViewGroup.LayoutParams(1, 1);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        if(!isAdded())return;
        requireActivity().addContentView(webView, layoutParams);
        /* 添加webView配置 */
        final XWalkSettings settings = webView.getSettings();
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            settings.setBlockNetworkImage(false);
        } else {
            settings.setBlockNetworkImage(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // settings.setUserAgentString(ANDROID_UA);

        webView.setBackgroundColor(Color.BLACK);
        webView.setUIClient(new XWalkUIClient(webView) {
            @Override
            public boolean onConsoleMessage(XWalkView view, String message, int lineNumber, String sourceId, ConsoleMessageType messageType) {
                return false;
            }

            @Override
            public boolean onJsAlert(XWalkView view, String url, String message, XWalkJavascriptResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(XWalkView view, String url, String message, XWalkJavascriptResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(XWalkView view, String url, String message, String defaultValue, XWalkJavascriptResult result) {
                return true;
            }
        });
        XWalkWebClient mX5WebClient = new XWalkWebClient(webView);
        webView.setResourceClient(mX5WebClient);
    }

    private class XWalkWebClient extends XWalkResourceClient {
        public XWalkWebClient(XWalkView view) {
            super(view);
        }

        @Override
        public void onDocumentLoadedInFrame(XWalkView view, long frameId) {
            super.onDocumentLoadedInFrame(view, frameId);
        }

        @Override
        public void onLoadStarted(XWalkView view, String url) {
            super.onLoadStarted(view, url);
        }

        @Override
        public void onLoadFinished(XWalkView view, String url) {
            super.onLoadFinished(view, url);
            LOG.i("echo-onLoadFinished url:" + url);
            if(!url.equals("about:blank")){
                mController.evaluateScript(sourceBean,url,null,view);
            }
        }

        @Override
        public void onProgressChanged(XWalkView view, int progressInPercent) {
            super.onProgressChanged(view, progressInPercent);
        }

        @Override
        public XWalkWebResourceResponse shouldInterceptLoadRequest(XWalkView view, XWalkWebResourceRequest request) {
            int generation = getWebViewGeneration(view);
            int parseSessionId = getWebViewParseSessionId(view);
            if (!isCurrentParseSession(generation, parseSessionId)) {
                logStalePlayback("xwalk-intercept", generation);
                return null;
            }
            String url = request.getUrl().toString();
            LOG.i("echo-shouldInterceptLoadRequest url:" + url);
            // suppress favicon requests as we don't display them anywhere
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return createXWalkWebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i( "shouldInterceptLoadRequest filter:" + url);
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }
            if (!ad ) {
                if (checkVideoFormat(url)) {
                    HashMap<String, String> webHeaders = new HashMap<>();
                    Map<String, String> hds = request.getRequestHeaders();
                    if (hds != null && hds.keySet().size() > 0) {
                        for (String k : hds.keySet()) {
                            if (k.equalsIgnoreCase("user-agent")
                                    || k.equalsIgnoreCase("referer")
                                    || k.equalsIgnoreCase("origin")) {
                                webHeaders.put(k," " + hds.get(k));
                            }
                        }
                    }
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, webHeaders);
                    LOG.i("echo-loadFoundVideoUrl:" + url );
                    if (loadFoundCount.incrementAndGet() == 1) {
                        if (!isCurrentParseSession(generation, parseSessionId)) {
                            logStalePlayback("xwalk-intercept-late-hit", generation);
                            return null;
                        }
                        stopLoadWebView(false);
                        SuperParse.stopJsonJx();
                        mHandler.removeMessages(MSG_PARSE_TIMEOUT);
                        url = loadFoundVideoUrls.poll();
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if(!TextUtils.isEmpty(cookie))webHeaders.put("Cookie", " " + cookie);//携带cookie
                        if (shouldIgnoreLateParsePlaybackResult(generation, url)) {
                            return createXWalkWebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                        }
                        playUrl(url, webHeaders, generation);
                    }
                }
            }
            return ad || loadFoundCount.get() > 0 ?
                    createXWalkWebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes())) :
                    null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(XWalkView view, String s) {
            return false;
        }

        @Override
        public void onReceivedSslError(XWalkView view, ValueCallback<Boolean> callback, SslError error) {
            callback.onReceiveValue(true);
        }
    }

}
