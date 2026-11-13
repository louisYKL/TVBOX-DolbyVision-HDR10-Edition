/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.StorageBudgetManager;
import com.github.tvbox.osc.util.SubtitleHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * @author AveryZhong.
 */

public class DefaultSubtitleEngine implements SubtitleEngine {
    private static final String TAG = DefaultSubtitleEngine.class.getSimpleName();
    private static final int MSG_REFRESH = 0x888;
    private static final int REFRESH_INTERVAL = 100;
    private static final int MAX_REFRESH_INTERVAL = 250;

    @Nullable
    private HandlerThread mHandlerThread;
    @Nullable
    private Handler mWorkHandler;
    @Nullable
    private volatile List<Subtitle> mSubtitles;
    private volatile UIRenderTask mUIRenderTask;
    private volatile AbstractPlayer mMediaPlayer;
    private OnSubtitlePreparedListener mOnSubtitlePreparedListener;
    private OnSubtitleChangeListener mOnSubtitleChangeListener;
    private volatile boolean mStarted = false;
    /** Invalidates callbacks from a previous manual subtitle choice or player reset. */
    private volatile int mLoadGeneration = 0;
    private volatile boolean mExternalPath;
    private volatile boolean mLoggedExternalRefresh;
    private volatile long mLastExternalNoCueLogMs;
    private volatile long mLastExternalTickWallMs;
    private volatile long mLastRefreshPosition = -1L;
    private volatile long mLastRefreshErrorWallMs;
    private volatile int mLastSubmittedGeneration = -1;
    private volatile Subtitle mLastSubmittedSubtitle;
    private volatile boolean mHasSubmittedSubtitle;

    public DefaultSubtitleEngine() {

    }

    @Override
    public void bindToMediaPlayer(AbstractPlayer mediaPlayer) {
        mMediaPlayer = mediaPlayer;
        Log.d(TAG, "bindToMediaPlayer: " + (mediaPlayer == null ? "null" : mediaPlayer.getClass().getSimpleName()));
        LOG.i("echo-subtitle bind-player player="
                + (mediaPlayer == null ? "null" : mediaPlayer.getClass().getSimpleName())
                + " external=" + mExternalPath + " started=" + mStarted);
        if (mediaPlayer != null && mSubtitles != null && !mSubtitles.isEmpty() && !mStarted) {
            start();
        }
    }

    @Override
    public void setSubtitlePath(final String path) {
        reset();
        ensureWorkThread();
        final int generation = ++mLoadGeneration;
        mExternalPath = true;
        mLoggedExternalRefresh = false;
        mLastExternalNoCueLogMs = 0L;
        mLastExternalTickWallMs = 0L;
        mLastRefreshPosition = -1L;
        mLastSubmittedGeneration = -1;
        mLastSubmittedSubtitle = null;
        mHasSubmittedSubtitle = false;
        LOG.i("echo-subtitle external-engine-load generation=" + generation
                + " path=" + safeLogPath(path));
        if (TextUtils.isEmpty(path)) {
            Log.w(TAG, "loadSubtitleFromRemote: path is null.");
            return;
        }

        SubtitleLoader.loadSubtitle(path, new SubtitleLoader.Callback() {
            @Override
            public void onSuccess(final SubtitleLoadSuccessResult subtitleLoadSuccessResult) {
                if (generation != mLoadGeneration) {
                    Log.d(TAG, "ignore stale subtitle success generation=" + generation);
                    return;
                }
                if (subtitleLoadSuccessResult == null) {
                    Log.d(TAG, "onSuccess: subtitleLoadSuccessResult is null.");
                    return;
                }
                if (subtitleLoadSuccessResult.timedTextObject == null) {
                    Log.d(TAG, "onSuccess: timedTextObject is null.");
                    return;
                }
                final TreeMap<Integer, Subtitle> captions = subtitleLoadSuccessResult.timedTextObject.captions;
                if (captions == null) {
                    Log.d(TAG, "onSuccess: captions is null.");
                    return;
                }
                mSubtitles = new ArrayList<>(captions.values());
                if (mSubtitles.isEmpty()) {
                    Log.w(TAG, "subtitle parsed successfully but contains no cues path=" + path);
                    LOG.e("echo-subtitle external-parse-empty");
                    return;
                }
                Log.d(TAG, "onSuccess: captions=" + mSubtitles.size() + " path=" + path);
                LOG.i("echo-subtitle external-parse-ready cues=" + mSubtitles.size());
                Subtitle first = mSubtitles.get(0);
                Subtitle last = mSubtitles.get(mSubtitles.size() - 1);
                LOG.i("echo-subtitle external-cue-range firstMs=" + safeStart(first)
                        + " lastMs=" + safeStart(last)
                        + " endMs=" + safeEnd(last)
                        + " generation=" + generation);
                setSubtitleDelay(SubtitleHelper.getTimeDelay());
                notifyPrepared();
                // The external picker can replace a subtitle while playback is already active.
                // Start the refresh loop here as well as through the prepared callback so a
                // cached WebDAV/local file is rendered immediately even if that callback raced
                // the view reset/bind sequence.
                if (mMediaPlayer != null && !mStarted) {
                    start();
                }
                // The player may already be far into the file when a WebDAV subtitle is
                // selected. Perform an immediate lookup so the first cue is not lost while
                // the handler thread is being restarted by the subtitle-view reset.
                refreshNow("parse-ready");

                String subtitlePath = subtitleLoadSuccessResult.subtitlePath;
                String cacheKey = getPlaySubtitleCacheKey();
                if (subtitlePath != null && (subtitlePath.startsWith("http://")
                        || subtitlePath.startsWith("https://"))) {
                    cacheRemoteSubtitleAsync(generation, cacheKey, subtitlePath,
                            subtitleLoadSuccessResult.fileName, subtitleLoadSuccessResult.content);
                } else if (!TextUtils.isEmpty(cacheKey)) {
                    saveSubtitleSelection(cacheKey, path);
                }
            }

            @Override
            public void onError(final Exception exception) {
                if (generation == mLoadGeneration) {
                    Log.e(TAG, "onError: path=" + path + " error=" + exception.getMessage(), exception);
                    LOG.e("echo-subtitle external-parse-failed type="
                            + exception.getClass().getSimpleName());
                }
            }
        });
    }

    private void cacheRemoteSubtitleAsync(final int generation, final String cacheKey,
                                          final String subtitlePath, final String fileName,
                                          final String content) {
        AppTaskExecutor.deskIO().execute(() -> {
            if (generation != mLoadGeneration || content == null) {
                return;
            }
            try {
                File cacheDir = new File(App.getInstance().getCacheDir(), "zimu");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    return;
                }
                String baseName = TextUtils.isEmpty(fileName) ? "subtitle.srt" : new File(fileName).getName();
                // Different sources and queued old selections must not overwrite one another.
                File cacheSubtitleFile = new File(cacheDir,
                        MD5.string2MD5(subtitlePath) + "-" + generation + "-" + baseName);
                // Encoding through a small writer buffer avoids another full subtitle byte[].
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(cacheSubtitleFile), StandardCharsets.UTF_8))) {
                    writer.write(content);
                }
                StorageBudgetManager.trimCaches();
                AppTaskExecutor.mainThread().execute(() -> {
                    if (generation == mLoadGeneration && !TextUtils.isEmpty(cacheKey)) {
                        saveSubtitleSelection(cacheKey, cacheSubtitleFile.getAbsolutePath());
                    }
                });
            } catch (IOException | RuntimeException error) {
                LOG.e("echo-subtitle external-cache-write-failed type="
                        + error.getClass().getSimpleName());
            }
        });
    }

    private void saveSubtitleSelection(String cacheKey, String subtitlePath) {
        try {
            CacheManager.save(MD5.string2MD5(cacheKey), subtitlePath);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to remember subtitle selection", error);
        }
    }

    @Override
    public void setSubtitleDelay(Integer milliseconds) {
        if (milliseconds == 0) {
            return;
        }
        if (mSubtitles == null || mSubtitles.size() == 0) {
            return;
        }
        List<Subtitle> thisSubtitles = mSubtitles;
        mSubtitles = null;
        for (int i = 0; i < thisSubtitles.size(); i++) {
            Subtitle subtitle = thisSubtitles.get(i);
            Time start = subtitle.start;
            Time end = subtitle.end;
            start.mseconds += milliseconds;
            end.mseconds += milliseconds;
            if (start.mseconds <= 0) {
                start.mseconds = 0;
            }
            if (end.mseconds <= 0) {
                end.mseconds = 0;
            }
            subtitle.start = start;
            subtitle.end = end;
        }
        mSubtitles = thisSubtitles;
    }

    private String playSubtitleCacheKey;
    public void setPlaySubtitleCacheKey(String cacheKey) {
        playSubtitleCacheKey = cacheKey;
    }

    public String getPlaySubtitleCacheKey() {
        return playSubtitleCacheKey;
    }

    @Override
    public void reset() {
        stop();
        mLoadGeneration++;
        mStarted = false;
        mSubtitles = null;
        UIRenderTask renderTask = mUIRenderTask;
        mUIRenderTask = null;
        if (renderTask != null) {
            renderTask.cancel();
        }
        mExternalPath = false;
        mLoggedExternalRefresh = false;
        mLastExternalNoCueLogMs = 0L;
        mLastExternalTickWallMs = 0L;
        mLastRefreshPosition = -1L;
        mLastSubmittedGeneration = -1;
        mLastSubmittedSubtitle = null;
        mHasSubmittedSubtitle = false;
    }

    @Override
    public void start() {
        Log.d(TAG, "start: ");
        if (mMediaPlayer == null) {
            Log.w(TAG, "MediaPlayer is not bind, You must bind MediaPlayer to "
                    + SubtitleEngine.class.getSimpleName()
                    + " before start() method be called,"
                    + " you can do this by call " +
                    "bindToMediaPlayer(MediaPlayer mediaPlayer) method.");
            return;
        }
        ensureWorkThread();
        mStarted = true;
        stop();
        if (mWorkHandler != null) {
            LOG.i("echo-subtitle refresh-start external=" + mExternalPath
                    + " cues=" + (mSubtitles == null ? 0 : mSubtitles.size())
                    + " player=" + mMediaPlayer.getClass().getSimpleName()
                    + " positionMs=" + safePosition());
            mWorkHandler.sendEmptyMessage(MSG_REFRESH);
        }

    }

    @Override
    public void pause() {
        mStarted = false;
        stop();
    }

    @Override
    public void resume() {
        start();
    }

    @Override
    public void stop() {
        if (mWorkHandler != null) {
            mWorkHandler.removeMessages(MSG_REFRESH);
        }
    }

    @Override
    public void destroy() {
        Log.d(TAG, "destroy: ");
        stopWorkThread();
        reset();

    }

    private void initWorkThread() {
        stopWorkThread();
        mHandlerThread = new HandlerThread("SubtitleFindThread");
        mHandlerThread.start();
        mWorkHandler = new Handler(mHandlerThread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(final Message msg) {
                try {
                    long delay = REFRESH_INTERVAL;
                    int generation = mLoadGeneration;
                    AbstractPlayer mediaPlayer = mMediaPlayer;
                    List<Subtitle> subtitles = mSubtitles;
                    if (mStarted && mediaPlayer != null) {
                        long position = mediaPlayer.getCurrentPosition();
                        mLastRefreshPosition = position;
                        Subtitle subtitle = SubtitleFinder.find(position, subtitles);
                        if (generation != mLoadGeneration || mediaPlayer != mMediaPlayer
                                || subtitles != mSubtitles || !mStarted) {
                            scheduleRefresh(REFRESH_INTERVAL);
                            return true;
                        }
                        if (mExternalPath) {
                            if (subtitle != null && !mLoggedExternalRefresh) {
                                mLoggedExternalRefresh = true;
                                LOG.i("echo-subtitle external-refresh-first positionMs="
                                        + position + " cueStartMs=" + safeStart(subtitle)
                                        + " cueEndMs=" + safeEnd(subtitle));
                            } else if (subtitle == null
                                    && (mLastExternalNoCueLogMs == 0L
                                    || position - mLastExternalNoCueLogMs >= 10_000L)) {
                                mLastExternalNoCueLogMs = position;
                                LOG.i("echo-subtitle external-refresh-no-cue positionMs="
                                        + position + " cues="
                                        + (mSubtitles == null ? 0 : mSubtitles.size()));
                            }
                            long now = android.os.SystemClock.uptimeMillis();
                            if (mLastExternalTickWallMs == 0L || now - mLastExternalTickWallMs >= 5000L) {
                                mLastExternalTickWallMs = now;
                                LOG.i("echo-subtitle external-refresh-tick positionMs=" + position
                                        + " cue=" + (subtitle == null ? "none" : safeStart(subtitle) + "-" + safeEnd(subtitle)));
                            }
                        }
                        notifyRefreshUI(subtitle, generation);
                        if (subtitle != null) {
                            delay = Math.min(MAX_REFRESH_INTERVAL,
                                    Math.max(REFRESH_INTERVAL, subtitle.end.mseconds - position));
                        }

                    }
                    scheduleRefresh(delay);
                } catch (Throwable error) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - mLastRefreshErrorWallMs >= 5000L) {
                        mLastRefreshErrorWallMs = now;
                        LOG.e("echo-subtitle refresh-error type=" + error.getClass().getSimpleName()
                                + " positionMs=" + mLastRefreshPosition);
                    }
                    // A transient vendor-player/Looper exception must not permanently disable
                    // external subtitle rendering. Retry while the engine remains started.
                    scheduleRefresh(REFRESH_INTERVAL);
                }
                return true;
            }
        });
    }

    private void scheduleRefresh(long delayMs) {
        Handler handler = mWorkHandler;
        if (handler != null && mStarted) {
            handler.sendEmptyMessageDelayed(MSG_REFRESH, Math.max(REFRESH_INTERVAL, delayMs));
        }
    }

    private void ensureWorkThread() {
        if (mHandlerThread == null || !mHandlerThread.isAlive() || mWorkHandler == null) {
            initWorkThread();
        }
    }

    private long safePosition() {
        try {
            return mMediaPlayer == null ? -1L : Math.max(0L, mMediaPlayer.getCurrentPosition());
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private void refreshNow(String reason) {
        int generation = mLoadGeneration;
        AbstractPlayer mediaPlayer = mMediaPlayer;
        List<Subtitle> subtitles = mSubtitles;
        if (mediaPlayer == null || subtitles == null || subtitles.isEmpty()) {
            LOG.i("echo-subtitle refresh-now-skip reason=" + reason
                    + " player=" + (mMediaPlayer == null ? "null" : "present")
                    + " cues=" + (mSubtitles == null ? 0 : mSubtitles.size()));
            return;
        }
        try {
            long position = Math.max(0L, mediaPlayer.getCurrentPosition());
            Subtitle subtitle = SubtitleFinder.find(position, subtitles);
            if (generation != mLoadGeneration || mediaPlayer != mMediaPlayer
                    || subtitles != mSubtitles) {
                return;
            }
            LOG.i("echo-subtitle refresh-now reason=" + reason + " positionMs=" + position
                    + " cue=" + (subtitle == null ? "none" : safeStart(subtitle) + "-" + safeEnd(subtitle)));
            notifyRefreshUI(subtitle, generation);
        } catch (Throwable error) {
            LOG.e("echo-subtitle refresh-now-error reason=" + reason
                    + " type=" + error.getClass().getSimpleName());
        }
    }

    private void stopWorkThread() {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }
        if (mWorkHandler != null) {
            mWorkHandler.removeCallbacksAndMessages(null);
            mWorkHandler = null;
        }
    }

    private void notifyRefreshUI(final Subtitle subtitle, final int generation) {
        if (generation != mLoadGeneration || !mStarted) {
            return;
        }
        if (mHasSubmittedSubtitle
                && mLastSubmittedGeneration == generation
                && mLastSubmittedSubtitle == subtitle) {
            return;
        }
        mHasSubmittedSubtitle = true;
        mLastSubmittedGeneration = generation;
        mLastSubmittedSubtitle = subtitle;
        if (mUIRenderTask == null) {
            mUIRenderTask = new UIRenderTask(mOnSubtitleChangeListener);
        }
        mUIRenderTask.execute(subtitle, new UIRenderTask.GenerationGuard() {
            @Override
            public boolean isCurrent() {
                return generation == mLoadGeneration && mStarted;
            }
        });
    }

    private void notifyPrepared() {
        if (mOnSubtitlePreparedListener != null) {
            mOnSubtitlePreparedListener.onSubtitlePrepared(mSubtitles);
        }
    }

    private static int safeStart(Subtitle subtitle) {
        return subtitle == null || subtitle.start == null ? -1 : subtitle.start.mseconds;
    }

    private static int safeEnd(Subtitle subtitle) {
        return subtitle == null || subtitle.end == null ? -1 : subtitle.end.mseconds;
    }

    private static String safeLogPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return "<empty>";
        }
        // Keep logs useful without dumping query tokens or long WebDAV paths.
        return path.length() <= 180 ? path : path.substring(0, 177) + "...";
    }

    @Override
    public void setOnSubtitlePreparedListener(final OnSubtitlePreparedListener listener) {
        mOnSubtitlePreparedListener = listener;
    }

    @Override
    public void setOnSubtitleChangeListener(final OnSubtitleChangeListener listener) {
        mOnSubtitleChangeListener = listener;
    }

}
