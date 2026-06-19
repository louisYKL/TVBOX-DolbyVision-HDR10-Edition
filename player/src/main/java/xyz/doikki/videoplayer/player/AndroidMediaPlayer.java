package xyz.doikki.videoplayer.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaDataSource;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.TimedText;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Locale;

import xyz.doikki.videoplayer.util.PlayerUtils;
import xyz.doikki.videoplayer.util.PlaybackUrlNormalizer;

/**
 * 封装系统 MediaPlayer，优先用于系统硬解播放。
 */
public class AndroidMediaPlayer extends AbstractPlayer implements MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnInfoListener,
        MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnVideoSizeChangedListener, MediaPlayer.OnTimedTextListener,
        MediaPlayer.OnSeekCompleteListener {
    private static final String TAG = "AndroidMediaPlayer";
    private static final int STATE_IDLE = 0;
    private static final int STATE_INITIALIZED = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PREPARED = 3;
    private static final int STATE_STARTED = 4;
    private static final int STATE_PAUSED = 5;
    private static final int STATE_STOPPED = 6;
    private static final int STATE_COMPLETED = 7;
    private static final int STATE_ERROR = 8;
    private static final int STATE_RELEASED = 9;

    protected MediaPlayer mMediaPlayer;
    private int mBufferedPercent;
    private Context mAppContext;
    private boolean mIsPreparing;
    private int mState = STATE_IDLE;
    private OnTimedTextListener mOnTimedTextListener;
    private int mResolvedDataSourceMode = DATA_SOURCE_NONE;
    private boolean mLastDataSourceSucceeded;
    private float mRequestedLeftVolume = 1f;
    private float mRequestedRightVolume = 1f;
    private String mCurrentDataSourceUrl;
    private Map<String, String> mCurrentDataSourceHeaders;
    private boolean mCurrentDataSourceMatroskaLike;
    private Surface mLastSurface;
    private SurfaceHolder mLastDisplayHolder;
    private boolean mWasPlayingBeforeSeek;
    private boolean mPendingResumeAfterSeek;
    private boolean mPendingStartAfterDisplayReady;
    private MediaDataSource mCurrentMediaDataSource;
    private ProxyFdHttpDataSource mCurrentProxyFdDataSource;
    private ParcelFileDescriptor mCurrentProxyFileDescriptor;
    private static Method sRuntimeLogInfoMethod;
    private static boolean sRuntimeLogLookupDone;
    private static Handler sProxyFdHandler;
    private static HandlerThread sProxyFdThread;

    private static final int DATA_SOURCE_NONE = 0;
    private static final int DATA_SOURCE_URI = 1;
    private static final int DATA_SOURCE_MEDIA_DATA_SOURCE = 2;
    private static final int DATA_SOURCE_PROXY_FILE_DESCRIPTOR = 3;
    private static final String HEADER_PROBE_CONTAINER = "X-TVBox-Probe-Container";
    private static final String HEADER_PROBE_DOLBY_VISION = "X-TVBox-Probe-DolbyVision";
    private static final String HEADER_PROBE_NATIVE_DV_DEVICE = "X-TVBox-Probe-NativeDvDevice";

    public AndroidMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void initPlayer() {
        mMediaPlayer = new MediaPlayer();
        mState = STATE_IDLE;
        setOptions();
        applyAudioOutputConfiguration();
        restoreRequestedVolume();
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnInfoListener(this);
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnVideoSizeChangedListener(this);
        mMediaPlayer.setOnTimedTextListener(this);
        mMediaPlayer.setOnSeekCompleteListener(this);
        installSubtitleDataListener();
        mMediaPlayer.setScreenOnWhilePlaying(true);
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            PlaybackUrlNormalizer.UrlWithHeaders parsed = PlaybackUrlNormalizer.splitUrlAndHeaders(path, headers);
            String resolvedUrl = parsed.url;
            String playbackUrl = resolvedUrl;
            if (!isAppStreamProxyUrl(resolvedUrl)) {
                playbackUrl = PlaybackUrlNormalizer.unwrapAppStreamProxyToLocalPlay(resolvedUrl);
                if (!TextUtils.equals(resolvedUrl, playbackUrl)) {
                    logInfo("echo-system-url unwrapAppStreamProxyToLocalPlay -> " + playbackUrl);
                }
            } else {
                logInfo("echo-system-url keep app stream proxy url " + playbackUrl);
            }
            mCurrentDataSourceUrl = playbackUrl;
            if (!isAppStreamProxyUrl(playbackUrl)) {
                String unwrappedUrl = unwrapLocalProxyStream(playbackUrl);
                if (!TextUtils.equals(playbackUrl, unwrappedUrl)) {
                    playbackUrl = unwrappedUrl;
                    logInfo("echo-system-url unwrapLocalProxyStream -> " + playbackUrl);
                }
            }
            mCurrentDataSourceUrl = playbackUrl;
            mResolvedDataSourceMode = DATA_SOURCE_NONE;
            mLastDataSourceSucceeded = false;
            setDataSourceInternal(playbackUrl, parsed.headers);
            mState = STATE_INITIALIZED;
            mLastDataSourceSucceeded = true;
        } catch (Exception e) {
            Log.e(TAG, "setDataSource failed path=" + path, e);
            mState = STATE_ERROR;
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    private String unwrapLocalProxyStream(String path) {
        if (TextUtils.isEmpty(path)) {
            return path;
        }
        try {
            Uri uri = Uri.parse(path);
            if (!"127.0.0.1".equals(uri.getHost()) && !"localhost".equalsIgnoreCase(uri.getHost())) {
                return path;
            }
            if (!"/proxy".equals(uri.getPath()) || !"stream".equalsIgnoreCase(uri.getQueryParameter("go"))) {
                return path;
            }
            String nestedUrl = uri.getQueryParameter("url");
            if (TextUtils.isEmpty(nestedUrl)) {
                return path;
            }
            Uri nestedUri = Uri.parse(nestedUrl);
            String nestedHost = nestedUri.getHost();
            if (nestedHost == null) {
                return path;
            }
            if (("127.0.0.1".equals(nestedHost) || "localhost".equalsIgnoreCase(nestedHost))
                    && nestedUri.getPath() != null
                    && nestedUri.getPath().contains("/proxy/play/")) {
                Log.i(TAG, "unwrapLocalProxyStream -> nested local play url " + nestedUrl);
                return nestedUrl;
            }
        } catch (Throwable ignored) {
        }
        return path;
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            closeCustomDataSourceQuietly();
            mMediaPlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            mState = STATE_INITIALIZED;
            mLastDataSourceSucceeded = true;
            mResolvedDataSourceMode = DATA_SOURCE_URI;
        } catch (Exception e) {
            mState = STATE_ERROR;
            mLastDataSourceSucceeded = false;
            mResolvedDataSourceMode = DATA_SOURCE_NONE;
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void start() {
        if (mMediaPlayer == null) {
            Log.w(TAG, "start ignored after release");
            return;
        }
        try {
            restoreRequestedVolume();
            mMediaPlayer.start();
            restoreRequestedVolume();
            mState = STATE_STARTED;
        } catch (IllegalStateException e) {
            Log.e(TAG, "start failed in state=" + mState, e);
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void pause() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            if (!canPause()) {
                Log.w(TAG, "pause ignored in state=" + mState);
                return;
            }
            mMediaPlayer.pause();
            mState = STATE_PAUSED;
        } catch (IllegalStateException e) {
            Log.e(TAG, "pause failed in state=" + mState, e);
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void stop() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            if (!canStop()) {
                Log.w(TAG, "stop ignored in state=" + mState);
                return;
            }
            mMediaPlayer.stop();
            mState = STATE_STOPPED;
        } catch (IllegalStateException e) {
            Log.e(TAG, "stop failed in state=" + mState, e);
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void prepareAsync() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            if (!canPrepare()) {
                Log.w(TAG, "prepareAsync ignored in state=" + mState);
                return;
            }
            mIsPreparing = true;
            mMediaPlayer.prepareAsync();
            mState = STATE_PREPARING;
        } catch (IllegalStateException e) {
            Log.e(TAG, "prepareAsync failed in state=" + mState, e);
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void reset() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            mMediaPlayer.reset();
            closeCustomDataSourceQuietly();
            applyAudioOutputConfiguration();
            restoreRequestedVolume();
            mMediaPlayer.setSurface(null);
            mMediaPlayer.setDisplay(null);
            mBufferedPercent = 0;
            mIsPreparing = false;
            mState = STATE_IDLE;
            mLastDataSourceSucceeded = false;
            mResolvedDataSourceMode = DATA_SOURCE_NONE;
            mCurrentDataSourceUrl = null;
            mCurrentDataSourceHeaders = null;
            mCurrentDataSourceMatroskaLike = false;
            mPendingResumeAfterSeek = false;
            mPendingStartAfterDisplayReady = false;
            mWasPlayingBeforeSeek = false;
        } catch (Exception e) {
            Log.e(TAG, "reset failed in state=" + mState, e);
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        }
    }

    @Override
    public boolean isPlaying() {
        if (mMediaPlayer == null) {
            return false;
        }
        try {
            return mMediaPlayer.isPlaying();
        } catch (IllegalStateException e) {
            Log.w(TAG, "isPlaying failed in state=" + mState, e);
            return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            mWasPlayingBeforeSeek = isPlaying();
            mPendingResumeAfterSeek = mWasPlayingBeforeSeek || mState == STATE_PREPARED;
            mMediaPlayer.seekTo(PlayerUtils.safeTimeMs(time));
            if (!mPendingResumeAfterSeek && canPause()) {
                mState = STATE_PAUSED;
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "seekTo failed in state=" + mState, e);
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void release() {
        if (mMediaPlayer == null) {
            mState = STATE_RELEASED;
            return;
        }
        mMediaPlayer.setOnErrorListener(null);
        mMediaPlayer.setOnCompletionListener(null);
        mMediaPlayer.setOnInfoListener(null);
        mMediaPlayer.setOnBufferingUpdateListener(null);
        mMediaPlayer.setOnPreparedListener(null);
        mMediaPlayer.setOnVideoSizeChangedListener(null);
        mMediaPlayer.setOnTimedTextListener(null);
        mMediaPlayer.setOnSeekCompleteListener(null);
        clearSubtitleDataListener();
        final MediaPlayer mediaPlayer = mMediaPlayer;
        mMediaPlayer = null;
        mIsPreparing = false;
        mBufferedPercent = 0;
        mState = STATE_RELEASED;
        mLastDataSourceSucceeded = false;
        mResolvedDataSourceMode = DATA_SOURCE_NONE;
        mCurrentDataSourceUrl = null;
        mCurrentDataSourceHeaders = null;
        mCurrentDataSourceMatroskaLike = false;
        mPendingResumeAfterSeek = false;
        mPendingStartAfterDisplayReady = false;
        mWasPlayingBeforeSeek = false;
        try {
            mediaPlayer.setSurface(null);
        } catch (Exception e) {
            Log.w(TAG, "clear surface failed before release", e);
        }
        try {
            mediaPlayer.setDisplay(null);
        } catch (Exception e) {
            Log.w(TAG, "clear display failed before release", e);
        }
        try {
            mediaPlayer.release();
        } catch (Exception e) {
            Log.w(TAG, "release failed", e);
        }
        closeCustomDataSourceQuietly();
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null) {
            return 0;
        }
        try {
            return mMediaPlayer.getCurrentPosition();
        } catch (IllegalStateException e) {
            Log.w(TAG, "getCurrentPosition failed in state=" + mState, e);
            return 0;
        }
    }

    @Override
    public long getDuration() {
        if (mMediaPlayer == null) {
            return 0;
        }
        try {
            return mMediaPlayer.getDuration();
        } catch (IllegalStateException e) {
            Log.w(TAG, "getDuration failed in state=" + mState, e);
            return 0;
        }
    }

    @Override
    public int getBufferedPercentage() {
        return mBufferedPercent;
    }

    @Override
    public void setSurface(Surface surface) {
        mLastSurface = surface;
        if (mMediaPlayer == null) {
            Log.w(TAG, "setSurface ignored after release");
            return;
        }
        try {
            mMediaPlayer.setSurface(surface);
            startIfDisplayReady();
        } catch (Exception e) {
            Log.e(TAG, "setSurface failed in state=" + mState, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        mLastDisplayHolder = holder;
        if (mMediaPlayer == null) {
            Log.w(TAG, "setDisplay ignored after release");
            return;
        }
        try {
            mMediaPlayer.setDisplay(holder);
            startIfDisplayReady();
        } catch (Exception e) {
            Log.e(TAG, "setDisplay failed in state=" + mState, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setVolume(float v1, float v2) {
        mRequestedLeftVolume = 1f;
        mRequestedRightVolume = 1f;
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(1f, 1f);
        }
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setLooping(isLooping);
        }
    }

    @Override
    public void setOptions() {
    }

    @Override
    public void setSpeed(float speed) {
        if (mMediaPlayer == null) {
            return;
        }
        // MediaPlayer playback params are not safe before prepared/started on some TV firmwares.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canAccessPlaybackParams()) {
            try {
                mMediaPlayer.setPlaybackParams(mMediaPlayer.getPlaybackParams().setSpeed(speed));
            } catch (IllegalStateException e) {
                Log.w(TAG, "setSpeed ignored in state=" + mState, e);
            } catch (RuntimeException e) {
                Log.w(TAG, "setSpeed runtime failure", e);
            }
        }
    }

    @Override
    public float getSpeed() {
        if (mMediaPlayer == null) {
            return 1f;
        }
        // only support above Android M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canAccessPlaybackParams()) {
            try {
                return mMediaPlayer.getPlaybackParams().getSpeed();
            } catch (IllegalStateException e) {
                Log.w(TAG, "getSpeed ignored in state=" + mState, e);
            } catch (RuntimeException e) {
                Log.w(TAG, "getSpeed runtime failure", e);
            }
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        return PlayerUtils.getNetSpeed(mAppContext);        
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "onError what=" + what + " extra=" + extra);
        mState = STATE_ERROR;
        mIsPreparing = false;
        mPlayerEventListener.onError();
        return true;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        mState = STATE_COMPLETED;
        mPlayerEventListener.onCompletion();
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        Log.i(TAG, "onInfo what=" + what + " extra=" + extra);
        //解决MEDIA_INFO_VIDEO_RENDERING_START多次回调问题
        if (what == AbstractPlayer.MEDIA_INFO_RENDERING_START) {
            if (mIsPreparing) {
                mPlayerEventListener.onInfo(what, extra);
                mIsPreparing = false;
            }
            restoreRequestedVolume();
        } else {
            mPlayerEventListener.onInfo(what, extra);
        }
        return true;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        mBufferedPercent = percent;
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {
        boolean resume = mPendingResumeAfterSeek;
        mPendingResumeAfterSeek = false;
        if (mMediaPlayer == null || mp == null || mp != mMediaPlayer) {
            return;
        }
        try {
            if (resume) {
                restoreRequestedVolume();
                if (!mMediaPlayer.isPlaying()) {
                    mMediaPlayer.start();
                    restoreRequestedVolume();
                }
                mState = STATE_STARTED;
                mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_BUFFERING_END, 0);
            } else if (canPause()) {
                mState = STATE_PAUSED;
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "onSeekComplete failed in state=" + mState, e);
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        } finally {
            mWasPlayingBeforeSeek = false;
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.i(TAG, "onPrepared");
        mState = STATE_PREPARED;
        applyVideoScalingMode(mp);
        restoreRequestedVolume();
        mPlayerEventListener.onPrepared();
        if (!isVideo()) {
            start();
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
            return;
        }
        if (hasVideoOutputTarget()) {
            start();
        } else {
            mPendingStartAfterDisplayReady = true;
            Log.i(TAG, "delay start until display target ready");
        }
    }

    private boolean isVideo() {
        if (!canInspectTrackInfo()) {
            return true;
        }
        try {
            MediaPlayer.TrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
            if (trackInfo == null) {
                return true;
            }
            for (MediaPlayer.TrackInfo info :
                    trackInfo) {
                if (info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                    return true;
                }
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        int videoWidth = mp.getVideoWidth();
        int videoHeight = mp.getVideoHeight();
        if (videoWidth != 0 && videoHeight != 0) {
            mPlayerEventListener.onVideoSizeChanged(videoWidth, videoHeight);
        }
    }

    private boolean canPause() {
        return mState == STATE_STARTED || mState == STATE_PAUSED || mState == STATE_PREPARED;
    }

    private boolean canStop() {
        return mState == STATE_PREPARED
                || mState == STATE_STARTED
                || mState == STATE_PAUSED
                || mState == STATE_STOPPED
                || mState == STATE_COMPLETED;
    }

    private boolean canPrepare() {
        return mState == STATE_INITIALIZED || mState == STATE_STOPPED;
    }

    private void applyAudioOutputConfiguration() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                mMediaPlayer.setAudioAttributes(audioAttributes);
            } else {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mMediaPlayer.setScreenOnWhilePlaying(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "applyAudioOutputConfiguration failed in state=" + mState, e);
        }
    }

    private void restoreRequestedVolume() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            mMediaPlayer.setVolume(1f, 1f);
        } catch (RuntimeException e) {
            Log.w(TAG, "restoreRequestedVolume failed in state=" + mState, e);
        }
    }

    private boolean canAccessPlaybackParams() {
        return mState == STATE_PREPARED
                || mState == STATE_STARTED
                || mState == STATE_PAUSED
                || mState == STATE_COMPLETED;
    }

    private boolean hasVideoOutputTarget() {
        try {
            if (mLastSurface != null && mLastSurface.isValid()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return mLastDisplayHolder != null
                    && mLastDisplayHolder.getSurface() != null
                    && mLastDisplayHolder.getSurface().isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void startIfDisplayReady() {
        if (!mPendingStartAfterDisplayReady || mMediaPlayer == null || mState != STATE_PREPARED) {
            return;
        }
        if (!hasVideoOutputTarget()) {
            return;
        }
        mPendingStartAfterDisplayReady = false;
        start();
    }

    private boolean canInspectTrackInfo() {
        return mState == STATE_PREPARED
                || mState == STATE_STARTED
                || mState == STATE_PAUSED
                || mState == STATE_COMPLETED;
    }

    private void applyVideoScalingMode(MediaPlayer mp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || mp == null) {
            return;
        }
        try {
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        } catch (IllegalStateException e) {
            Log.w(TAG, "setVideoScalingMode failed in state=" + mState, e);
        } catch (RuntimeException e) {
            Log.w(TAG, "setVideoScalingMode runtime failure", e);
        }
    }

    @Override
    public boolean supportsTrackSelection() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
    }

    @Override
    public boolean hasValidDataSource() {
        return mLastDataSourceSucceeded && mResolvedDataSourceMode != DATA_SOURCE_NONE;
    }

    public MediaPlayer.TrackInfo[] getTrackInfo() {
        if (!canInspectTrackInfo() || mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return null;
        }
        try {
            return mMediaPlayer.getTrackInfo();
        } catch (Throwable e) {
            Log.w(TAG, "getTrackInfo failed", e);
            return null;
        }
    }

    public int getSelectedTrack(int mediaTrackType) {
        if (mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return -1;
        }
        try {
            return mMediaPlayer.getSelectedTrack(mediaTrackType);
        } catch (Throwable e) {
            Log.w(TAG, "getSelectedTrack failed type=" + mediaTrackType, e);
            return -1;
        }
    }

    public void selectTrack(int index) {
        if (mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return;
        }
        try {
            mMediaPlayer.selectTrack(index);
        } catch (Throwable e) {
            Log.w(TAG, "selectTrack failed index=" + index, e);
        }
    }

    public void deselectTrack(int index) {
        if (mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        try {
            mMediaPlayer.deselectTrack(index);
        } catch (Throwable e) {
            Log.w(TAG, "deselectTrack failed index=" + index, e);
        }
    }

    public void addTimedTextSource(String path) {
        if (mMediaPlayer == null || TextUtils.isEmpty(path) || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return;
        }
        try {
            String lower = path.toLowerCase();
            String mimeType = MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP;
            if (lower.endsWith(".vtt")) {
                mimeType = "text/vtt";
            } else if (lower.endsWith(".ttml") || lower.endsWith(".xml")) {
                mimeType = "application/ttml+xml";
            } else if (lower.endsWith(".ass") || lower.endsWith(".ssa")) {
                mimeType = "text/x-ssa";
            }
            mMediaPlayer.addTimedTextSource(path, mimeType);
        } catch (Throwable e) {
            Log.w(TAG, "addTimedTextSource failed path=" + path, e);
        }
    }

    public void setOnTimedTextListener(OnTimedTextListener listener) {
        mOnTimedTextListener = listener;
    }

    private void installSubtitleDataListener() {
        if (mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        try {
            Class<?> listenerClass = Class.forName("android.media.MediaPlayer$OnSubtitleDataListener");
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onSubtitleData".equals(method.getName()) && args != null && args.length >= 2) {
                            handleSubtitleData(args[1]);
                        }
                        return null;
                    });
            Method setListener = MediaPlayer.class.getMethod("setOnSubtitleDataListener", listenerClass);
            setListener.invoke(mMediaPlayer, listener);
            logInfo("echo-system-subtitle-data-listener installed");
        } catch (Throwable th) {
            Log.w(TAG, "installSubtitleDataListener failed", th);
            writeRuntimeLog("echo-system-subtitle-data-listener failed " + th.getMessage());
        }
    }

    private void clearSubtitleDataListener() {
        if (mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        try {
            Method clearListener = MediaPlayer.class.getMethod("clearOnSubtitleDataListener");
            clearListener.invoke(mMediaPlayer);
        } catch (Throwable ignored) {
        }
    }

    private void handleSubtitleData(Object subtitleData) {
        if (subtitleData == null || mOnTimedTextListener == null) {
            return;
        }
        try {
            Method getData = subtitleData.getClass().getMethod("getData");
            byte[] data = (byte[]) getData.invoke(subtitleData);
            String text = decodeSubtitleBytes(data);
            Log.i(TAG, "echo-system-subtitle-data len=" + (text == null ? 0 : text.length()));
            mOnTimedTextListener.onTimedText(text);
        } catch (Throwable th) {
            Log.w(TAG, "handleSubtitleData failed", th);
            writeRuntimeLog("echo-system-subtitle-data failed " + th.getMessage());
        }
    }

    private String decodeSubtitleBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (hasReplacementCharacter(text)) {
            try {
                text = new String(data, Charset.forName("GB18030"));
            } catch (Throwable ignored) {
            }
        }
        return sanitizeSubtitlePayload(text);
    }

    private boolean hasReplacementCharacter(String text) {
        return text != null && text.indexOf('\uFFFD') >= 0;
    }

    private String sanitizeSubtitlePayload(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace('\u0000', ' ').trim();
        if (cleaned.startsWith("WEBVTT")) {
            int index = cleaned.indexOf('\n');
            cleaned = index >= 0 ? cleaned.substring(index + 1).trim() : "";
        }
        cleaned = cleaned.replaceAll("(?m)^\\d+\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->.*$", "");
        return cleaned.trim();
    }

    private void setDataSourceInternal(String playbackUrl, Map<String, String> headers) throws Exception {
        String normalizedUrl = isAppStreamProxyUrl(playbackUrl)
                ? playbackUrl
                : PlaybackUrlNormalizer.normalizeHttpUrl(playbackUrl);
        boolean probedMatroska = hasInternalHeaderValue(headers, HEADER_PROBE_CONTAINER, "matroska");
        boolean probedDolbyVision = hasInternalHeaderValue(headers, HEADER_PROBE_DOLBY_VISION, "1");
        boolean matroskaLike = probedMatroska
                || isMatroskaLike(normalizedUrl)
                || isMatroskaLike(getNestedLocalProxyPlayUrl(normalizedUrl))
                || isMatroskaLike(getAppStreamNestedRemoteUrl(normalizedUrl));
        mCurrentDataSourceUrl = normalizedUrl;
        mCurrentDataSourceHeaders = headers;
        mCurrentDataSourceMatroskaLike = matroskaLike;
        if (matroskaLike) {
            logInfo("echo-system-data-source nativeMatroskaUri url=" + normalizedUrl
                    + " matroska=" + matroskaLike
                    + " probedMatroska=" + probedMatroska
                    + " probedDv=" + probedDolbyVision);
        }
        Map<String, String> externalHeaders = cleanExternalHeaders(headers);
        if (shouldUseProxyBackedDataSource(normalizedUrl, headers)) {
            try {
                if (trySetProxyBackedDataSource(normalizedUrl, externalHeaders, matroskaLike)) {
                    return;
                }
            } catch (Throwable proxyBackedError) {
                closeCustomDataSourceQuietly();
                logInfo("echo-system-data-source proxy-backed failed fallback uri url="
                        + normalizedUrl + " err=" + proxyBackedError.getClass().getSimpleName()
                        + ":" + proxyBackedError.getMessage());
            }
        }
        try {
            Uri uri = Uri.parse(normalizedUrl);
            boolean networkUri = isNetworkScheme(uri == null ? null : uri.getScheme());
            logInfo("echo-system-data-source uri url=" + uri + " matroska=" + matroskaLike + " network=" + networkUri);
            closeCustomDataSourceQuietly();
            if (networkUri) {
                mMediaPlayer.setDataSource(normalizedUrl);
            } else {
                mMediaPlayer.setDataSource(mAppContext, uri, externalHeaders);
            }
            mResolvedDataSourceMode = DATA_SOURCE_URI;
        } catch (Exception uriError) {
            throw uriError;
        }
    }

    private boolean shouldUseProxyBackedDataSource(String normalizedUrl, Map<String, String> headers) {
        if (TextUtils.isEmpty(normalizedUrl) || isHlsLike(normalizedUrl)) {
            return false;
        }
        if (shouldBypassProxyBackedSourceForNativeDv(headers, normalizedUrl)) {
            logInfo("echo-system-data-source native-dv-direct-localplay url=" + normalizedUrl);
            return false;
        }
        return isLocalProxyPlayUrl(normalizedUrl);
    }

    private boolean shouldBypassProxyBackedSourceForNativeDv(Map<String, String> headers, String normalizedUrl) {
        if (!isLocalProxyPlayUrl(normalizedUrl)) {
            return false;
        }
        boolean probedDolbyVision = hasInternalHeaderValue(headers, HEADER_PROBE_DOLBY_VISION, "1");
        boolean nativeDvDevice = hasInternalHeaderValue(headers, HEADER_PROBE_NATIVE_DV_DEVICE, "1");
        return probedDolbyVision && nativeDvDevice;
    }

    private boolean trySetProxyBackedDataSource(String normalizedUrl,
                                                Map<String, String> headers,
                                                boolean matroskaLike) throws Exception {
        closeCustomDataSourceQuietly();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            StorageManager storageManager = (StorageManager) mAppContext.getSystemService(Context.STORAGE_SERVICE);
            if (storageManager != null) {
                ProxyFdHttpDataSource proxyFdDataSource = new ProxyFdHttpDataSource(normalizedUrl, headers);
                ParcelFileDescriptor proxyFd = null;
                try {
                    proxyFd = storageManager.openProxyFileDescriptor(
                            ParcelFileDescriptor.MODE_READ_ONLY,
                            proxyFdDataSource,
                            getProxyFdHandler());
                    mMediaPlayer.setDataSource(proxyFd.getFileDescriptor());
                    mCurrentProxyFdDataSource = proxyFdDataSource;
                    mCurrentProxyFileDescriptor = proxyFd;
                    mResolvedDataSourceMode = DATA_SOURCE_PROXY_FILE_DESCRIPTOR;
                    logInfo("echo-system-data-source proxy-fd url=" + normalizedUrl + " matroska=" + matroskaLike);
                    return true;
                } catch (Throwable th) {
                    if (proxyFd != null) {
                        try {
                            proxyFd.close();
                        } catch (IOException ignored) {
                        }
                    }
                    try {
                        proxyFdDataSource.onRelease();
                    } catch (Throwable ignored) {
                    }
                    throw th;
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            HttpRangeMediaDataSource mediaDataSource = new HttpRangeMediaDataSource(normalizedUrl, headers);
            try {
                mMediaPlayer.setDataSource(mediaDataSource);
                mCurrentMediaDataSource = mediaDataSource;
                mResolvedDataSourceMode = DATA_SOURCE_MEDIA_DATA_SOURCE;
                logInfo("echo-system-data-source media-data-source url=" + normalizedUrl + " matroska=" + matroskaLike);
                return true;
            } catch (Throwable th) {
                try {
                    mediaDataSource.close();
                } catch (Throwable ignored) {
                }
                throw th;
            }
        }
        return false;
    }

    private void closeCustomDataSourceQuietly() {
        MediaDataSource mediaDataSource = mCurrentMediaDataSource;
        mCurrentMediaDataSource = null;
        if (mediaDataSource != null) {
            try {
                mediaDataSource.close();
            } catch (Throwable ignored) {
            }
        }
        ParcelFileDescriptor proxyFileDescriptor = mCurrentProxyFileDescriptor;
        mCurrentProxyFileDescriptor = null;
        if (proxyFileDescriptor != null) {
            try {
                proxyFileDescriptor.close();
            } catch (Throwable ignored) {
            }
        }
        ProxyFdHttpDataSource proxyFdDataSource = mCurrentProxyFdDataSource;
        mCurrentProxyFdDataSource = null;
        if (proxyFdDataSource != null) {
            try {
                proxyFdDataSource.onRelease();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Handler getProxyFdHandler() {
        synchronized (AndroidMediaPlayer.class) {
            if (sProxyFdHandler != null) {
                return sProxyFdHandler;
            }
            sProxyFdThread = new HandlerThread("TVBoxProxyFd");
            sProxyFdThread.start();
            sProxyFdHandler = new Handler(sProxyFdThread.getLooper());
            return sProxyFdHandler;
        }
    }

    @Override
    public void onTimedText(MediaPlayer mp, TimedText text) {
        if (mOnTimedTextListener != null) {
            String value = text == null ? null : text.getText();
            Log.i(TAG, "echo-system-timed-text len=" + (value == null ? 0 : value.length()));
            mOnTimedTextListener.onTimedText(value);
        }
    }

    private boolean isLocalHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private boolean isMatroskaLike(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.contains(".mkv") || lower.contains(".webm");
    }

    private boolean isLocalProxyPlayUrl(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(path);
            String host = uri.getHost();
            String pathPart = uri.getPath();
            return isLocalHost(host) && pathPart != null && pathPart.contains("/proxy/play/");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isHlsLike(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.contains(".m3u8")
                || lower.contains("format=hls")
                || lower.contains("type=hls")
                || lower.contains("application/vnd.apple.mpegurl");
    }

    private boolean isNetworkScheme(String scheme) {
        if (TextUtils.isEmpty(scheme)) {
            return false;
        }
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isAppStreamProxyUrl(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(path);
            String host = uri.getHost();
            String pathPart = uri.getPath();
            String go = uri.getQueryParameter("go");
            return isLocalHost(host)
                    && "/proxy".equals(pathPart)
                    && "stream".equalsIgnoreCase(go);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasInternalHeaderValue(Map<String, String> headers, String headerName, String expectedValue) {
        if (headers == null || TextUtils.isEmpty(headerName)) {
            return false;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase(headerName)
                    && entry.getValue() != null
                    && entry.getValue().trim().equalsIgnoreCase(expectedValue)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> cleanExternalHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        java.util.HashMap<String, String> clean = new java.util.HashMap<>();
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
        return clean.isEmpty() ? null : clean;
    }

    private String getNestedLocalProxyPlayUrl(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            return getNestedLocalProxyPlayUrl(Uri.parse(path));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String getNestedLocalProxyPlayUrl(Uri uri) {
        if (uri == null) {
            return null;
        }
        String host = uri.getHost();
        String pathPart = uri.getPath();
        String go = uri.getQueryParameter("go");
        String nestedUrl = uri.getQueryParameter("url");
        if (!isLocalHost(host)
                || !"/proxy".equals(pathPart)
                || (!"stream".equalsIgnoreCase(go) && !"play".equalsIgnoreCase(go))
                || TextUtils.isEmpty(nestedUrl)) {
            return null;
        }
        try {
            Uri nestedUri = Uri.parse(nestedUrl);
            String nestedHost = nestedUri.getHost();
            String nestedPath = nestedUri.getPath();
            if (nestedHost == null || nestedPath == null) {
                return null;
            }
            if (!isLocalHost(nestedHost) || !nestedPath.contains("/proxy/play/")) {
                return null;
            }
            return nestedUrl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String getAppStreamNestedRemoteUrl(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(path);
            String host = uri.getHost();
            String pathPart = uri.getPath();
            String go = uri.getQueryParameter("go");
            String nestedUrl = uri.getQueryParameter("url");
            if (!isLocalHost(host)
                    || !"/proxy".equals(pathPart)
                    || !"stream".equalsIgnoreCase(go)
                    || TextUtils.isEmpty(nestedUrl)) {
                return null;
            }
            Uri nestedUri = Uri.parse(nestedUrl);
            if (isLocalHost(nestedUri.getHost())) {
                return null;
            }
            return nestedUrl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public interface OnTimedTextListener {
        void onTimedText(String text);
    }

    private void logInfo(String message) {
        Log.i(TAG, message);
        writeRuntimeLog(message);
    }

    private void logWarn(String message, Throwable th) {
        Log.w(TAG, message, th);
        writeRuntimeLog(message);
    }

    private static void writeRuntimeLog(String message) {
        try {
            Method method = getRuntimeLogInfoMethod();
            if (method != null) {
                method.invoke(null, message);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method getRuntimeLogInfoMethod() {
        if (sRuntimeLogLookupDone) {
            return sRuntimeLogInfoMethod;
        }
        synchronized (AndroidMediaPlayer.class) {
            if (sRuntimeLogLookupDone) {
                return sRuntimeLogInfoMethod;
            }
            try {
                Class<?> logClass = Class.forName("com.github.tvbox.osc.util.LOG");
                sRuntimeLogInfoMethod = logClass.getMethod("i", String.class);
            } catch (Throwable ignored) {
                sRuntimeLogInfoMethod = null;
            }
            sRuntimeLogLookupDone = true;
            return sRuntimeLogInfoMethod;
        }
    }
}
