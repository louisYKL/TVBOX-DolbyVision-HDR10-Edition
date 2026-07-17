package xyz.doikki.videoplayer.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.MediaDataSource;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.TimedText;
import android.net.Uri;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.content.res.Configuration;
import android.app.UiModeManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.telephony.TelephonyManager;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    protected volatile MediaPlayer mMediaPlayer;
    private volatile int mBufferedPercent;
    private Context mAppContext;
    private boolean mIsPreparing;
    private int mState = STATE_IDLE;
    private OnTimedTextListener mOnTimedTextListener;
    private int mResolvedDataSourceMode = DATA_SOURCE_NONE;
    private boolean mLastDataSourceSucceeded;
    private float mRequestedLeftVolume = 1f;
    private float mRequestedRightVolume = 1f;
    private float mRequestedPlaybackSpeed = 1f;
    private String mCurrentDataSourceUrl;
    private Map<String, String> mCurrentDataSourceHeaders;
    private boolean mCurrentDataSourceMatroskaLike;
    private Surface mLastSurface;
    private SurfaceHolder mLastDisplayHolder;
    private boolean mHasVideoOutputTarget;
    private boolean mPendingStartAfterDisplayReady;
    private boolean mPlaybackHasStarted;
    private volatile boolean mIsBuffering;
    private volatile boolean mNativeBuffering;
    private boolean mBufferingInfoVisible;
    private volatile long mAvoidPositionQueryUntilMs;
    private final SeekCoordinator mSeekCoordinator = new SeekCoordinator();
    private MediaPlayer mNativeSeekTimeoutOwner;
    private int mNativeSeekTimeoutTarget = SeekCoordinator.NO_TARGET;
    private long mNativeSeekTimeoutDispatchId = SeekCoordinator.NO_DISPATCH_ID;
    private final Runnable mNativeSeekTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            MediaPlayer owner = mNativeSeekTimeoutOwner;
            int target = mNativeSeekTimeoutTarget;
            long dispatchId = mNativeSeekTimeoutDispatchId;
            clearNativeSeekTimeoutState();
            if (owner != null
                    && target != SeekCoordinator.NO_TARGET
                    && dispatchId != SeekCoordinator.NO_DISPATCH_ID) {
                handleNativeSeekTimeout(owner, target, dispatchId);
            }
        }
    };
    private int mLastRequestedSeekTarget = SeekCoordinator.NO_TARGET;
    private long mLastCompletedSeekAtMs;
    private int mLastCompletedSeekTarget = SeekCoordinator.NO_TARGET;
    private boolean mDirectUriProgressActive;
    private long mDirectUriProgressStartRxBytes = TrafficStats.UNSUPPORTED;
    private int mDirectUriProgressPercent;
    private MediaDataSource mCurrentMediaDataSource;
    private ProxyFdHttpDataSource mCurrentProxyFdDataSource;
    private ParcelFileDescriptor mCurrentProxyFileDescriptor;
    private boolean mForceSafePcmAudio;
    private String mLastDispatchedSubtitleText;
    private int mSubtitleDispatchGeneration;
    private Object mSubtitleDataListenerProxy;
    private int mDeferredTrackSelection = -1;
    private int mDeferredTrackDeselection = -1;
    private long mLastVolumeStateLogAtMs;
    private boolean mWaitForRealVideoFrame;
    private boolean mVideoRenderStartSeen;
    private int mVideoRenderWatchdogGeneration;
    private int mVideoRenderWatchdogArmedPercent;
    private int mScheduledVideoRenderWatchdogGeneration = -1;
    private String mScheduledVideoRenderWatchdogReason;
    private final Runnable mVideoRenderWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            int generation = mScheduledVideoRenderWatchdogGeneration;
            String reason = mScheduledVideoRenderWatchdogReason;
            clearScheduledVideoRenderWatchdogState();
            if (generation >= 0 && reason != null) {
                handleVideoRenderWatchdog(generation, reason);
            }
        }
    };
    private static Method sRuntimeLogInfoMethod;
    private static boolean sRuntimeLogLookupDone;
    private static Handler sProxyFdHandler;
    private static HandlerThread sProxyFdThread;
    private static final int DATA_SOURCE_NONE = 0;
    private static final int DATA_SOURCE_URI = 1;
    private static final int DATA_SOURCE_MEDIA_DATA_SOURCE = 2;
    private static final int DATA_SOURCE_PROXY_FILE_DESCRIPTOR = 3;
    private static final int NETWORK_SOURCE_MODE_AUTO = 0;
    private static final int NETWORK_SOURCE_MODE_FORCE_URI = 1;
    private static final int NETWORK_SOURCE_MODE_FORCE_PROXY = 2;
    private static final int MEDIA_INFO_VIDEO_NOT_PLAYING = 805;
    private static final long VIDEO_RENDER_START_TIMEOUT_MS = 5500L;
    private static final long TV32_DIRECT_HDR_MATROSKA_RENDER_START_TIMEOUT_MS = 30_000L;
    private static final long DIRECT_URI_PROGRESS_TARGET_BYTES = 24L * 1024L * 1024L;
    private static final long NATIVE_SEEK_TIMEOUT_MS = 35_000L;
    private static final String HEADER_PROBE_CONTAINER = "X-TVBox-Probe-Container";
    private static final String HEADER_PROBE_DOLBY_VISION = "X-TVBox-Probe-DolbyVision";
    private static final String HEADER_PROBE_NATIVE_DV_DEVICE = "X-TVBox-Probe-NativeDvDevice";
    private static final String HEADER_PROBE_HDR10 = "X-TVBox-Probe-Hdr10";
    private static final String HEADER_PROBE_HDR10_PLUS = "X-TVBox-Probe-Hdr10Plus";
    private static final String HEADER_PROBE_AUDIO_PASSTHROUGH = "X-TVBox-Probe-AudioPassthrough";
    private static final String HEADER_PROBE_AUDIO_PASSTHROUGH_ALLOWED = "X-TVBox-Probe-AudioPassthroughAllowed";
    private static final String HEADER_PROBE_TV32_SAFE_PCM = "X-TVBox-Probe-Tv32SafePcm";
    private static final String HEADER_PROBE_TV32_STARTUP_DIRECT = "X-TVBox-Probe-Tv32StartupDirect";
    private static final String HEADER_PROBE_JAVA64_LOCAL_PROXY_FAST = "X-TVBox-Probe-Java64LocalProxyFast";
    private int mNetworkSourceMode = NETWORK_SOURCE_MODE_AUTO;
    private boolean mJava64MissingAudioRecoveryAttempted;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public AndroidMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void initPlayer() {
        resetPostSeekCompletionGuardState();
        mMediaPlayer = new MediaPlayer();
        mState = STATE_IDLE;
        mHasVideoOutputTarget = false;
        mIsBuffering = false;
        mNativeBuffering = false;
        mBufferingInfoVisible = false;
        mAvoidPositionQueryUntilMs = 0L;
        mPlaybackHasStarted = false;
        resetDirectUriProgress();
        mLastRequestedSeekTarget = SeekCoordinator.NO_TARGET;
        resetSystemTrackState();
        setOptions();
        applyAudioOutputConfiguration();
        restoreRequestedVolume();
        logInfo("echo-system-audio init focusStream=music");
        bindNativePlayerListeners(mMediaPlayer);
    }

    private void bindNativePlayerListeners(MediaPlayer player) {
        if (player == null || player != mMediaPlayer) {
            return;
        }
        player.setOnErrorListener(this);
        player.setOnCompletionListener(this);
        player.setOnInfoListener(this);
        player.setOnBufferingUpdateListener(this);
        player.setOnPreparedListener(this);
        player.setOnVideoSizeChangedListener(this);
        player.setOnTimedTextListener(this);
        player.setOnSeekCompleteListener(this);
        installSubtitleDataListener();
        player.setScreenOnWhilePlaying(true);
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            PlaybackUrlNormalizer.UrlWithHeaders parsed = PlaybackUrlNormalizer.splitUrlAndHeaders(path, headers);
            String resolvedUrl = parsed.url;
            String playbackUrl = resolvedUrl;
            resetSystemTrackState();
            if (shouldUnwrapAppStreamProxyForNativeJava64Dv(resolvedUrl, parsed.headers)) {
                String unwrappedNativeDvUrl = unwrapLocalProxyStream(resolvedUrl);
                if (!TextUtils.equals(resolvedUrl, unwrappedNativeDvUrl)) {
                    playbackUrl = unwrappedNativeDvUrl;
                    logInfo("echo-system-url native-dv unwrapAppStreamProxyToLocalPlay -> " + playbackUrl);
                }
            } else if (isAppStreamProxyUrl(resolvedUrl)) {
                playbackUrl = PlaybackUrlNormalizer.unwrapAppStreamProxyToLocalPlay(resolvedUrl);
                if (!TextUtils.equals(resolvedUrl, playbackUrl)) {
                    logInfo("echo-system-url unwrapAppStreamProxyToLocalPlay -> " + playbackUrl);
                }
            } else {
                logInfo("echo-system-url keep direct url " + playbackUrl);
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
            mNetworkSourceMode = NETWORK_SOURCE_MODE_AUTO;
            mJava64MissingAudioRecoveryAttempted = false;
            mIsBuffering = false;
            mNativeBuffering = false;
            mBufferingInfoVisible = false;
            mBufferedPercent = 0;
            mAvoidPositionQueryUntilMs = 0L;
            mPlaybackHasStarted = false;
            resetDirectUriProgress();
            mLastRequestedSeekTarget = SeekCoordinator.NO_TARGET;
            resetPostSeekCompletionGuardState();
            clearSeekState();
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
            mPlaybackHasStarted = false;
            resetDirectUriProgress();
            mLastRequestedSeekTarget = SeekCoordinator.NO_TARGET;
            resetPostSeekCompletionGuardState();
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
        if (!NativePlayerOperationPolicy.canStart(isNativeSeekTransitionActive())) {
            return;
        }
        mSeekCoordinator.requestResume();
        if (!mPlaybackHasStarted || isNativeSeekTransitionActive()) {
            requestPlaybackPrebuffer();
        }
        startNow();
    }

    private void startNow() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            restoreRequestedVolume();
            mMediaPlayer.start();
            restoreRequestedVolume();
            logRoutedAudioOutput("started");
            mState = STATE_STARTED;
            mPlaybackHasStarted = true;
            scheduleVideoRenderWatchdogIfNeeded("start");
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
        if (!NativePlayerOperationPolicy.canPause(isNativeSeekTransitionActive())) {
            mSeekCoordinator.cancelResume();
            mPendingStartAfterDisplayReady = false;
            mState = STATE_PAUSED;
            logInfo("echo-system-pause defer-native-until-seek-complete target="
                    + resolvePendingSeekTarget());
            return;
        }
        try {
            if (!canPause()) {
                Log.w(TAG, "pause ignored in state=" + mState);
                return;
            }
            mMediaPlayer.pause();
            mState = STATE_PAUSED;
            if (mSeekCoordinator.isInFlight()) {
                mSeekCoordinator.cancelResume();
            }
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
            finishPlaybackPrebuffer();
            resetPostSeekCompletionGuardState();
            clearSeekState();
            mLastRequestedSeekTarget = SeekCoordinator.NO_TARGET;
            mPendingStartAfterDisplayReady = false;
            mIsBuffering = false;
            mNativeBuffering = false;
            mBufferingInfoVisible = false;
            mAvoidPositionQueryUntilMs = 0L;
            mPlaybackHasStarted = false;
            resetDirectUriProgress();
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
            beginDirectUriProgress("prepare", false);
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
            finishPlaybackPrebuffer();
            resetPostSeekCompletionGuardState();
            clearSeekState();
            mLastRequestedSeekTarget = SeekCoordinator.NO_TARGET;
            // Cancel custom range reads first so vendor reset never waits for an obsolete
            // MediaDataSource/AppFuse callback on the activity thread.
            closeCustomDataSourceQuietly();
            // MediaPlayer is not thread-safe. Keep reset on the same main looper that owns it.
            mMediaPlayer.reset();
            // Some TV vendor MediaPlayer implementations drop subtitle callbacks on reset.
            mMediaPlayer.setOnTimedTextListener(this);
            mMediaPlayer.setOnSeekCompleteListener(this);
            installSubtitleDataListener();
            applyAudioOutputConfiguration();
            restoreRequestedVolume();
            mMediaPlayer.setSurface(null);
            mMediaPlayer.setDisplay(null);
            mHasVideoOutputTarget = false;
            mBufferedPercent = 0;
            mIsPreparing = false;
            mState = STATE_IDLE;
            mLastDataSourceSucceeded = false;
            mResolvedDataSourceMode = DATA_SOURCE_NONE;
            mCurrentDataSourceUrl = null;
            mCurrentDataSourceHeaders = null;
            mCurrentDataSourceMatroskaLike = false;
            mForceSafePcmAudio = false;
            mNetworkSourceMode = NETWORK_SOURCE_MODE_AUTO;
            mJava64MissingAudioRecoveryAttempted = false;
            mPendingStartAfterDisplayReady = false;
            mIsBuffering = false;
            mNativeBuffering = false;
            mBufferingInfoVisible = false;
            mAvoidPositionQueryUntilMs = 0L;
            mPlaybackHasStarted = false;
            resetDirectUriProgress();
            mLastDispatchedSubtitleText = null;
            mSubtitleDispatchGeneration++;
            mLastVolumeStateLogAtMs = 0L;
            clearVideoRenderGate("reset");
            resetSystemTrackState();
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
        boolean playWhenReady = mState == STATE_STARTED
                || mPendingStartAfterDisplayReady
                || mSeekCoordinator.shouldResumeAfterFinalSeek();
        if (isLikely32BitTvDevice()) {
            if (playWhenReady) {
                return true;
            }
            if (mState == STATE_PAUSED
                    || mState == STATE_STOPPED
                    || mState == STATE_COMPLETED
                    || mState == STATE_ERROR
                    || mState == STATE_RELEASED) {
                return false;
            }
        }
        if (shouldAvoidBlockingPositionQuery()) {
            return playWhenReady;
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
            int target = PlayerUtils.safeTimeMs(time);
            resetPostSeekCompletionGuardState();
            mLastRequestedSeekTarget = target;
            boolean shouldResume = mState == STATE_STARTED
                    || mState == STATE_PREPARED
                    || mPendingStartAfterDisplayReady
                    || mSeekCoordinator.shouldResumeAfterFinalSeek();
            SeekCoordinator.Request request = mSeekCoordinator.request(target, shouldResume);
            if (!request.shouldDispatch) {
                logInfo("echo-system-seek " + request.reason
                        + " target=" + target
                        + " active=" + mSeekCoordinator.getActiveTarget()
                        + " resume=" + mSeekCoordinator.shouldResumeAfterFinalSeek());
                return;
            }
            dispatchSeek(request.target, request.dispatchId, "request");
        } catch (IllegalStateException e) {
            Log.e(TAG, "seekTo failed in state=" + mState, e);
            clearSeekState();
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
        finishPlaybackPrebuffer();
        resetPostSeekCompletionGuardState();
        mLastRequestedSeekTarget = SeekCoordinator.NO_TARGET;
        clearSeekState();
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
        mHasVideoOutputTarget = false;
        mForceSafePcmAudio = false;
        mNetworkSourceMode = NETWORK_SOURCE_MODE_AUTO;
        mJava64MissingAudioRecoveryAttempted = false;
        mPendingStartAfterDisplayReady = false;
        mIsBuffering = false;
        mNativeBuffering = false;
        mBufferingInfoVisible = false;
        mAvoidPositionQueryUntilMs = 0L;
        mPlaybackHasStarted = false;
        resetDirectUriProgress();
        mLastDispatchedSubtitleText = null;
        mSubtitleDispatchGeneration++;
        mLastVolumeStateLogAtMs = 0L;
        clearVideoRenderGate("release");
        resetSystemTrackState();
        // Cancel range reads before native release. Otherwise some vendor MediaPlayer
        // implementations wait for a blocked AppFuse/MediaDataSource callback on this thread.
        closeCustomDataSourceQuietly();
        releaseNativePlayer(mediaPlayer, "release");
        mLastSurface = null;
        mLastDisplayHolder = null;
        mHasVideoOutputTarget = false;
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null) {
            return 0;
        }
        if (shouldAvoidBlockingPositionQuery()) {
            return 0;
        }
        try {
            long position = mMediaPlayer.getCurrentPosition();
            clearPostSeekCompletionGuardAfterStableAdvance(position);
            return position;
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
        if (shouldAvoidBlockingPositionQuery()) {
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
        mDirectUriProgressPercent = BufferingProgressPolicy.resolveDirectUriDisplayedPercent(
                mDirectUriProgressActive,
                mDirectUriProgressStartRxBytes,
                readUidRxBytes(),
                DIRECT_URI_PROGRESS_TARGET_BYTES,
                mBufferedPercent,
                mDirectUriProgressPercent);
        return mDirectUriProgressPercent;
    }

    private void beginDirectUriProgress(String reason, boolean resetExisting) {
        if (!isDirectNetworkUriSource()) {
            return;
        }
        if (mDirectUriProgressActive && !resetExisting) {
            return;
        }
        long received = readUidRxBytes();
        if (received == TrafficStats.UNSUPPORTED) {
            return;
        }
        if (resetExisting) {
            // MediaPlayer reports buffer percentage for the previous byte range. A seek starts
            // a new range, so carrying 100% forward makes an active seek appear stuck at 99%.
            mBufferedPercent = 0;
        }
        mDirectUriProgressActive = true;
        mDirectUriProgressStartRxBytes = received;
        mDirectUriProgressPercent = 0;
        logInfo("echo-system-buffer progress-window-begin reason=" + reason
                + " target=" + formatMegabytes(DIRECT_URI_PROGRESS_TARGET_BYTES));
    }

    private void finishDirectUriProgress() {
        mDirectUriProgressActive = false;
        mDirectUriProgressStartRxBytes = TrafficStats.UNSUPPORTED;
        mDirectUriProgressPercent = BufferingProgressPolicy.clampPercent(mBufferedPercent);
    }

    private void resetDirectUriProgress() {
        mDirectUriProgressActive = false;
        mDirectUriProgressStartRxBytes = TrafficStats.UNSUPPORTED;
        mDirectUriProgressPercent = 0;
    }

    private long readUidRxBytes() {
        try {
            return TrafficStats.getUidRxBytes(mAppContext.getApplicationInfo().uid);
        } catch (Throwable ignored) {
            return TrafficStats.UNSUPPORTED;
        }
    }

    private boolean isDirectNetworkUriSource() {
        if (mResolvedDataSourceMode != DATA_SOURCE_URI || TextUtils.isEmpty(mCurrentDataSourceUrl)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(mCurrentDataSourceUrl);
            return isNetworkScheme(uri == null ? null : uri.getScheme());
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void setSurface(Surface surface) {
        mLastSurface = surface;
        mHasVideoOutputTarget = mMediaPlayer != null && surface != null && surface.isValid();
        if (mMediaPlayer == null) {
            Log.w(TAG, "setSurface ignored after release");
            return;
        }
        try {
            mMediaPlayer.setSurface(surface);
            startIfDisplayReady();
        } catch (Exception e) {
            Log.e(TAG, "setSurface failed in state=" + mState, e);
            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "ignore non-fatal surface detach failure");
                return;
            }
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        mLastDisplayHolder = holder;
        mHasVideoOutputTarget = mMediaPlayer != null
                && holder != null
                && holder.getSurface() != null
                && holder.getSurface().isValid();
        if (mMediaPlayer == null) {
            Log.w(TAG, "setDisplay ignored after release");
            return;
        }
        try {
            mMediaPlayer.setDisplay(holder);
            startIfDisplayReady();
        } catch (Exception e) {
            Log.e(TAG, "setDisplay failed in state=" + mState, e);
            if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
                Log.w(TAG, "ignore non-fatal display detach failure");
                return;
            }
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setVolume(float v1, float v2) {
        mRequestedLeftVolume = 1f;
        mRequestedRightVolume = 1f;
        if (mMediaPlayer != null) {
            applyRequestedVolume();
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
        mRequestedPlaybackSpeed = speed;
        if (mMediaPlayer == null) {
            return;
        }
        applyRequestedPlaybackSpeed();
    }

    @Override
    public float getSpeed() {
        if (mMediaPlayer == null) {
            return mRequestedPlaybackSpeed;
        }
        // only support above Android M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && canAccessPlaybackParams()
                && NativePlayerOperationPolicy.canAccessPlaybackParams(
                isNativeSeekTransitionActive())) {
            try {
                mRequestedPlaybackSpeed = mMediaPlayer.getPlaybackParams().getSpeed();
                return mRequestedPlaybackSpeed;
            } catch (IllegalStateException e) {
                Log.w(TAG, "getSpeed ignored in state=" + mState, e);
            } catch (RuntimeException e) {
                Log.w(TAG, "getSpeed runtime failure", e);
            }
        }
        return mRequestedPlaybackSpeed;
    }

    @Override
    public long getTcpSpeed() {
        return PlayerUtils.getNetSpeed(mAppContext);        
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        if (ignoreStaleNativeCallback(mp, "error")) {
            return true;
        }
        Log.e(TAG, "onError what=" + what + " extra=" + extra);
        finishPlaybackPrebuffer();
        clearSeekState();
        mIsBuffering = false;
        mNativeBuffering = false;
        mBufferingInfoVisible = false;
        mAvoidPositionQueryUntilMs = 0L;
        mState = STATE_ERROR;
        mIsPreparing = false;
        mPlayerEventListener.onError();
        return true;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (ignoreStaleNativeCallback(mp, "completion")) {
            return;
        }
        boolean seekInFlight = isNativeSeekTransitionActive();
        if (!NativePlayerOperationPolicy.canQuery(seekInFlight)) {
            logInfo("echo-system-completion ignored-during-seek target="
                    + resolvePendingSeekTarget());
            return;
        }
        long position = -1L;
        long duration = -1L;
        boolean audioOnly = false;
        try {
            if (mp != null && mp == mMediaPlayer) {
                position = mp.getCurrentPosition();
                duration = mp.getDuration();
                audioOnly = isAudioOnlyTrackList();
            }
        } catch (Throwable th) {
            writeRuntimeLog("echo-system-completion inspect-failed err="
                    + th.getClass().getSimpleName() + ":" + th.getMessage());
        }
        boolean expectedCompletion = PlaybackCompletionPolicy.isExpectedCompletion(
                mVideoRenderStartSeen,
                audioOnly,
                seekInFlight,
                mIsBuffering,
                position,
                duration);
        if (!expectedCompletion) {
            boolean playbackRequested = mState == STATE_STARTED
                    || mPendingStartAfterDisplayReady
                    || mSeekCoordinator.shouldResumeAfterFinalSeek();
            int recoveryTarget = PlaybackCompletionPolicy.resolveRecoveryTarget(
                    mSeekCoordinator.getActiveTarget(),
                    mLastRequestedSeekTarget,
                    position,
                    duration);
            logInfo("echo-system-completion rejected position=" + position
                    + " duration=" + duration
                    + " rendered=" + mVideoRenderStartSeen
                    + " audioOnly=" + audioOnly
                    + " seek=" + seekInFlight
                    + " buffering=" + mIsBuffering
                    + " target=" + recoveryTarget);
            boolean likelyPostSeekFalseCompletion =
                    PlaybackCompletionPolicy.isLikelyPostSeekFalseCompletion(
                    playbackRequested,
                    mLastCompletedSeekTarget,
                    mLastCompletedSeekAtMs,
                    System.currentTimeMillis(),
                    position,
                    duration);
            if (likelyPostSeekFalseCompletion) {
                logInfo("echo-system-completion ignored-post-seek-vendor-callback target="
                        + recoveryTarget
                        + " position=" + position
                        + " duration=" + duration
                        + " action=no-reseek");
                return;
            }
            finishPlaybackPrebuffer();
            clearSeekState();
            mIsBuffering = false;
            mNativeBuffering = false;
            mBufferingInfoVisible = false;
            mAvoidPositionQueryUntilMs = 0L;
            mState = STATE_ERROR;
            mIsPreparing = false;
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
            return;
        }
        logInfo("echo-system-completion accepted position=" + position
                + " duration=" + duration);
        finishPlaybackPrebuffer();
        resetPostSeekCompletionGuardState();
        clearSeekState();
        mIsBuffering = false;
        mNativeBuffering = false;
        mBufferingInfoVisible = false;
        mAvoidPositionQueryUntilMs = 0L;
        mState = STATE_COMPLETED;
        mPlayerEventListener.onCompletion();
    }

    private void resetPostSeekCompletionGuardState() {
        mLastCompletedSeekAtMs = 0L;
        mLastCompletedSeekTarget = SeekCoordinator.NO_TARGET;
    }

    private void clearPostSeekCompletionGuardAfterStableAdvance(long position) {
        if (mLastCompletedSeekTarget == SeekCoordinator.NO_TARGET
                || position <= (long) mLastCompletedSeekTarget + 1_000L) {
            return;
        }
        logInfo("echo-system-completion post-seek-stable target="
                + mLastCompletedSeekTarget + " position=" + position);
        resetPostSeekCompletionGuardState();
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (ignoreStaleNativeCallback(mp, "info-" + what)) {
            return true;
        }
        Log.i(TAG, "onInfo what=" + what + " extra=" + extra);
        if (what == AbstractPlayer.MEDIA_INFO_BUFFERING_START) {
            mNativeBuffering = true;
            dispatchBufferingStart(extra, "native");
            return true;
        }
        if (what == AbstractPlayer.MEDIA_INFO_BUFFERING_END) {
            mNativeBuffering = false;
            mAvoidPositionQueryUntilMs = 0L;
            restartVideoRenderWatchdogAfterBuffering("native-buffering-end");
            dispatchBufferingEndIfIdle(extra, "native");
            return true;
        }
        //解决MEDIA_INFO_VIDEO_RENDERING_START多次回调问题
        if (what == AbstractPlayer.MEDIA_INFO_RENDERING_START) {
            mVideoRenderStartSeen = true;
            mWaitForRealVideoFrame = false;
            cancelVideoRenderWatchdog();
            mVideoRenderWatchdogGeneration++;
            // A rendered frame is definitive proof that startup buffering is no longer blocking
            // the decoder. Some TV firmwares omit BUFFERING_END after a long seek.
            mNativeBuffering = false;
            finishPlaybackPrebuffer();
            finishDirectUriProgress();
            dispatchBufferingEndIfIdle(0, "render-start");
            if (mIsPreparing) {
                mPlayerEventListener.onInfo(what, extra);
                mIsPreparing = false;
            }
            restoreRequestedVolume();
            if (!isLikely32BitTvDevice()) {
                ensurePreferredAudioTrackSelected("render-start");
                logTrackState("render-start");
            }
        } else if (what == MEDIA_INFO_VIDEO_NOT_PLAYING
                && shouldTreatAsVideoStartupFailure(extra)) {
            failBeforeFirstVideoFrame("media-info-805:" + extra);
        } else {
            mPlayerEventListener.onInfo(what, extra);
        }
        return true;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        if (ignoreStaleNativeCallback(mp, "buffering-update")) {
            return;
        }
        int normalized = BufferingProgressPolicy.clampPercent(percent);
        int previous = mBufferedPercent;
        mBufferedPercent = normalized;
        if (normalized != previous && mNativeBuffering) {
            logInfo("echo-system-buffer progress=" + normalized
                    + "% previous=" + previous
                    + "% native=" + mNativeBuffering);
        }
    }

    private void dispatchBufferingStart(int extra, String reason) {
        beginDirectUriProgress(reason, "native".equals(reason) && mPlaybackHasStarted);
        mIsBuffering = true;
        mBufferingInfoVisible = true;
        logInfo("echo-system-buffer start reason=" + reason
                + " percent=" + getBufferedPercentage() + "%");
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_BUFFERING_START, extra);
        }
    }

    private void dispatchBufferingEndIfIdle(int extra, String reason) {
        if (BufferingProgressPolicy.shouldHoldBufferingEnd(mNativeBuffering)) {
            mIsBuffering = true;
            logInfo("echo-system-buffer hold-end reason=" + reason
                    + " native=" + mNativeBuffering
                    + " percent=" + getBufferedPercentage() + "%");
            return;
        }
        mIsBuffering = false;
        if (!mBufferingInfoVisible) {
            return;
        }
        mBufferingInfoVisible = false;
        finishDirectUriProgress();
        logInfo("echo-system-buffer end reason=" + reason
                + " percent=" + getBufferedPercentage() + "%");
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_BUFFERING_END, extra);
        }
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {
        if (ignoreStaleNativeCallback(mp, "seek-complete")) {
            return;
        }
        SeekCoordinator.Completion completion = mSeekCoordinator.complete();
        if (!completion.accepted) {
            logInfo("echo-system-seek ignored stale complete");
            return;
        }
        cancelNativeSeekTimeout();
        logInfo("echo-system-seek complete target=" + completion.completedTarget
                + " id=" + completion.completedDispatchId
                + " resume=" + completion.shouldResume);
        mLastRequestedSeekTarget = completion.completedTarget;
        finishPlaybackSeek();
        mAvoidPositionQueryUntilMs = System.currentTimeMillis() + 1500L;
        mLastCompletedSeekAtMs = System.currentTimeMillis();
        mLastCompletedSeekTarget = completion.completedTarget;
        // The native seek is already complete even when a fullscreen transition has
        // temporarily detached the Surface. Publish the real position before gating start so
        // progress persistence never falls back to the pre-seek value.
        mPlayerEventListener.onSeekComplete(completion.completedTarget);
        finishAfterFinalSeek(completion.completedTarget, completion.shouldResume);
    }

    private void finishAfterFinalSeek(int completedTarget, boolean shouldResume) {
        applyDeferredTrackOperation();
        applyRequestedPlaybackSpeed();
        try {
            if (shouldResume) {
                boolean audioOnly = isAudioOnlyTrackList();
                if (!audioOnly && !hasVideoOutputTarget()) {
                    mPendingStartAfterDisplayReady = true;
                    logInfo("echo-system-start-gate seek-wait-display target=" + completedTarget);
                    return;
                }
                if (audioOnly) {
                    clearVideoRenderGate("audio-only-seek-complete");
                }
                requestPlaybackPrebuffer();
                restoreRequestedVolume();
                mMediaPlayer.start();
                restoreRequestedVolume();
                mState = STATE_STARTED;
                mPlaybackHasStarted = true;
                scheduleVideoRenderWatchdogIfNeeded("seek-complete-resume");
                dispatchBufferingEndIfIdle(0, "seek-complete-resume");
                if (audioOnly && mPlayerEventListener != null) {
                    mNativeBuffering = false;
                    finishPlaybackPrebuffer();
                    dispatchBufferingEndIfIdle(0, "audio-only-seek-complete");
                    mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
                }
            } else {
                finishPlaybackPrebuffer();
                if (mPlaybackHasStarted && mMediaPlayer != null) {
                    mMediaPlayer.pause();
                }
                mState = STATE_PAUSED;
                mNativeBuffering = false;
                dispatchBufferingEndIfIdle(0, "seek-complete-paused");
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "onSeekComplete failed in state=" + mState, e);
            finishPlaybackPrebuffer();
            clearSeekState();
            mIsBuffering = false;
            mNativeBuffering = false;
            mBufferingInfoVisible = false;
            mAvoidPositionQueryUntilMs = 0L;
            mState = STATE_ERROR;
            mPlayerEventListener.onError();
        }
    }

    private void dispatchSeek(int target, long dispatchId, String reason) {
        final MediaPlayer owner = mMediaPlayer;
        if (owner == null
                || !mSeekCoordinator.isActiveDispatch(dispatchId, target)) {
            return;
        }
        finishPlaybackPrebuffer();
        logInfo("echo-system-seek dispatch=" + reason
                + " target=" + target
                + " id=" + dispatchId
                + " resume=" + mSeekCoordinator.shouldResumeAfterFinalSeek()
                + " mode=player-thread"
                + " source=" + dataSourceModeName(mResolvedDataSourceMode));
        mAvoidPositionQueryUntilMs = System.currentTimeMillis() + 3000L;
        beginDirectUriProgress("seek-" + reason, mPlaybackHasStarted);
        beginPlaybackSeek();
        // Vendor players may block for several seconds before emitting BUFFERING_START.
        // Publish the seek state now so the UI stays responsive and reports real progress.
        dispatchBufferingStart(0, "seek-dispatch");
        // Do not pause an already-started decoder. The first resume seek remains in PREPARED;
        // later seeks keep the established audio and HTTP pipelines alive.
        dispatchNativeSeekOnPlayerThread(owner, target, dispatchId, reason);
        if (!mSeekCoordinator.shouldResumeAfterFinalSeek() && canPause()) {
            mState = STATE_PAUSED;
        }
    }

    private void clearSeekState() {
        cancelNativeSeekTimeout();
        mSeekCoordinator.reset();
        finishPlaybackSeek();
    }

    private void releaseNativePlayer(MediaPlayer player, String reason) {
        if (player == null) {
            return;
        }
        try {
            player.setOnErrorListener(null);
            player.setOnCompletionListener(null);
            player.setOnInfoListener(null);
            player.setOnBufferingUpdateListener(null);
            player.setOnPreparedListener(null);
            player.setOnVideoSizeChangedListener(null);
            player.setOnTimedTextListener(null);
            player.setOnSeekCompleteListener(null);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Method clearListener = MediaPlayer.class.getMethod("clearOnSubtitleDataListener");
                clearListener.invoke(player);
            } catch (Throwable ignored) {
            }
        }
        try {
            player.setSurface(null);
        } catch (Throwable ignored) {
        }
        try {
            player.setDisplay(null);
        } catch (Throwable ignored) {
        }
        try {
            player.release();
            writeRuntimeLog("echo-system-seek native-player-released reason=" + reason);
        } catch (Throwable th) {
            Log.w(TAG, "release native player failed reason=" + reason, th);
        }
    }

    private void dispatchNativeSeekOnPlayerThread(final MediaPlayer owner,
                                                  final int target,
                                                  final long dispatchId,
                                                  final String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(() -> dispatchNativeSeekOnPlayerThread(
                    owner, target, dispatchId, reason));
            return;
        }
        boolean invoked = false;
        Throwable failure = null;
        long startedAtMs = System.currentTimeMillis();
        try {
            boolean current = owner == mMediaPlayer
                    && mSeekCoordinator.isActiveDispatch(dispatchId, target);
            if (current) {
                invoked = true;
                owner.seekTo(target);
            }
        } catch (Throwable th) {
            failure = th;
        }
        onNativeSeekInvocationFinished(
                owner,
                target,
                dispatchId,
                reason,
                invoked,
                failure,
                Math.max(0L, System.currentTimeMillis() - startedAtMs));
    }

    private void onNativeSeekInvocationFinished(MediaPlayer owner,
                                                 int target,
                                                 long dispatchId,
                                                 String reason,
                                                 boolean invoked,
                                                 Throwable failure,
                                                 long elapsedMs) {
        if (!invoked) {
            logInfo("echo-system-seek skip-stale-call reason=" + reason
                    + " target=" + target + " id=" + dispatchId);
            return;
        }
        if (failure == null) {
            scheduleNativeSeekTimeout(owner, target, dispatchId);
            logInfo("echo-system-seek call-returned reason=" + reason
                    + " target=" + target
                    + " id=" + dispatchId
                    + " elapsedMs=" + elapsedMs);
            return;
        }
        if (owner != mMediaPlayer || !mSeekCoordinator.fail(dispatchId, target)) {
            logInfo("echo-system-seek ignore-stale-failure reason=" + reason
                    + " target=" + target + " id=" + dispatchId);
            return;
        }
        Log.e(TAG, "native seek failed target=" + target + " id=" + dispatchId, failure);
        handleNativeSeekFailure("seek-call-error");
    }

    private void scheduleNativeSeekTimeout(MediaPlayer owner, int target, long dispatchId) {
        cancelNativeSeekTimeout();
        if (owner != mMediaPlayer
                || !mSeekCoordinator.isActiveDispatch(dispatchId, target)) {
            return;
        }
        mNativeSeekTimeoutOwner = owner;
        mNativeSeekTimeoutTarget = target;
        mNativeSeekTimeoutDispatchId = dispatchId;
        mMainHandler.postDelayed(mNativeSeekTimeoutRunnable, NATIVE_SEEK_TIMEOUT_MS);
    }

    private void cancelNativeSeekTimeout() {
        mMainHandler.removeCallbacks(mNativeSeekTimeoutRunnable);
        clearNativeSeekTimeoutState();
    }

    private void clearNativeSeekTimeoutState() {
        mNativeSeekTimeoutOwner = null;
        mNativeSeekTimeoutTarget = SeekCoordinator.NO_TARGET;
        mNativeSeekTimeoutDispatchId = SeekCoordinator.NO_DISPATCH_ID;
    }

    private void handleNativeSeekTimeout(MediaPlayer owner, int target, long dispatchId) {
        if (owner != mMediaPlayer
                || !mSeekCoordinator.isActiveDispatch(dispatchId, target)
                || mState == STATE_ERROR
                || mState == STATE_RELEASED) {
            return;
        }
        boolean shouldResume = mSeekCoordinator.shouldResumeAfterFinalSeek();
        if (!mSeekCoordinator.fail(dispatchId, target)) {
            return;
        }
        logInfo("echo-system-seek timeout target=" + target
                + " id=" + dispatchId
                + " action=clear-stale-state-no-rebuild");
        finishPlaybackSeek();
        mNativeBuffering = false;
        mAvoidPositionQueryUntilMs = 0L;
        mLastCompletedSeekAtMs = System.currentTimeMillis();
        mLastCompletedSeekTarget = target;
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onSeekComplete(target);
        }
        try {
            if (shouldResume && mState != STATE_PAUSED) {
                restoreRequestedVolume();
                owner.start();
                restoreRequestedVolume();
                mState = STATE_STARTED;
                mPlaybackHasStarted = true;
                scheduleVideoRenderWatchdogIfNeeded("seek-timeout-resume");
            } else {
                if (mPlaybackHasStarted) {
                    owner.pause();
                }
                mState = STATE_PAUSED;
            }
            dispatchBufferingEndIfIdle(0, "seek-timeout-fallback");
        } catch (IllegalStateException error) {
            Log.e(TAG, "seek timeout fallback failed target=" + target, error);
            mState = STATE_ERROR;
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError();
            }
        }
    }

    private void handleNativeSeekFailure(String reason) {
        finishPlaybackPrebuffer();
        clearSeekState();
        mBufferingInfoVisible = false;
        mIsBuffering = false;
        mNativeBuffering = false;
        mAvoidPositionQueryUntilMs = 0L;
        mState = STATE_ERROR;
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    private void beginPlaybackSeek() {
        dispatchTimedText("", "seek-start");
        if (mCurrentProxyFdDataSource != null) {
            mCurrentProxyFdDataSource.onPlaybackSeekStarted();
        } else if (mCurrentMediaDataSource instanceof HttpRangeMediaDataSource) {
            ((HttpRangeMediaDataSource) mCurrentMediaDataSource).onPlaybackSeekStarted();
        }
    }

    private void finishPlaybackSeek() {
        if (mCurrentProxyFdDataSource != null) {
            mCurrentProxyFdDataSource.onPlaybackSeekFinished();
        } else if (mCurrentMediaDataSource instanceof HttpRangeMediaDataSource) {
            ((HttpRangeMediaDataSource) mCurrentMediaDataSource).onPlaybackSeekFinished();
        }
    }

    private void requestPlaybackPrebuffer() {
        if (mCurrentProxyFdDataSource != null) {
            mCurrentProxyFdDataSource.requestPlaybackPrebuffer();
        } else if (mCurrentMediaDataSource instanceof HttpRangeMediaDataSource) {
            ((HttpRangeMediaDataSource) mCurrentMediaDataSource).requestPlaybackPrebuffer();
        }
    }

    private void finishPlaybackPrebuffer() {
        if (mCurrentProxyFdDataSource != null) {
            mCurrentProxyFdDataSource.finishPlaybackPrebuffer();
        } else if (mCurrentMediaDataSource instanceof HttpRangeMediaDataSource) {
            ((HttpRangeMediaDataSource) mCurrentMediaDataSource).finishPlaybackPrebuffer();
        }
    }

    private String formatMegabytes(long bytes) {
        return String.format(Locale.US, "%.1fMB", Math.max(0L, bytes) / (1024d * 1024d));
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (ignoreStaleNativeCallback(mp, "prepared")) {
            return;
        }
        Log.i(TAG, "onPrepared");
        mState = STATE_PREPARED;
        mIsBuffering = false;
        mNativeBuffering = false;
        mBufferingInfoVisible = false;
        mAvoidPositionQueryUntilMs = 0L;
        applyVideoScalingMode(mp);
        restoreRequestedVolume();
        // This is the released v0.2.1 audio initialization order. In particular, the TV32
        // vendor player must settle its default audio track before playback is started.
        logTrackState("prepared");
        ensurePreferredAudioTrackSelected("prepared");
        logTrackState("prepared-after-default");
        if (retrySystemDataSourceForMissingAudioTrack("prepared")) {
            return;
        }
        mPlayerEventListener.onPrepared();
        if (mp != mMediaPlayer || mState != STATE_PREPARED) {
            return;
        }
        if (isAudioOnlyTrackList()) {
            clearVideoRenderGate("audio-only");
            start();
            finishPlaybackPrebuffer();
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
            return;
        }
        armVideoRenderGate("prepared");
        logInfo("echo-system-start-gate prepared hasTarget=" + hasVideoOutputTarget()
                + " matroska=" + mCurrentDataSourceMatroskaLike
                + " hdrLike=" + isCurrentHdrLikeDataSource());
        if (hasVideoOutputTarget()) {
            start();
        } else {
            mPendingStartAfterDisplayReady = true;
            Log.i(TAG, "delay start until display target ready");
        }
    }

    private boolean isAudioOnlyTrackList() {
        if (!canInspectTrackInfo()) {
            return false;
        }
        boolean hasAudio = false;
        try {
            MediaPlayer.TrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
            if (trackInfo == null || trackInfo.length == 0) {
                logInfo("echo-system-track unknown-empty assume-video");
                return false;
            }
            for (MediaPlayer.TrackInfo info :
                    trackInfo) {
                if (info == null) {
                    continue;
                }
                if (info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                    return false;
                }
                if (info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    hasAudio = true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return hasAudio;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        if (ignoreStaleNativeCallback(mp, "video-size")) {
            return;
        }
        if (width != 0 && height != 0) {
            mPlayerEventListener.onVideoSizeChanged(width, height);
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

    private void logRoutedAudioOutput(String reason) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || mMediaPlayer == null) {
            return;
        }
        try {
            AudioDeviceInfo routed = mMediaPlayer.getRoutedDevice();
            CharSequence rawName = routed == null ? null : routed.getProductName();
            String name = TextUtils.isEmpty(rawName) ? "unknown" : rawName.toString();
            name = name.replace(',', '_').replace(';', '_');
            logInfo("echo-system-audio-route reason=" + reason
                    + " mode=system-managed"
                    + " routed=" + (routed == null
                    ? "system-default"
                    : "id=" + routed.getId() + ",type=" + routed.getType() + ",name=" + name));
        } catch (Throwable th) {
            writeRuntimeLog("echo-system-audio-route routed-inspection-failed reason=" + reason
                    + " err=" + th.getClass().getSimpleName() + ":" + th.getMessage());
        }
    }

    private void applyAudioOutputConfiguration() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            boolean safePcmAudio = mForceSafePcmAudio && isLikelyTvOffloadRiskDevice();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                mMediaPlayer.setAudioAttributes(audioAttributes);
                logInfo("echo-system-audio attrs usage=media content=movie safePcmHint="
                        + safePcmAudio + " tv32=" + isLikely32BitTvDevice()
                        + " tvLike=" + isLikelyTvOffloadRiskDevice());
            } else {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                logInfo("echo-system-audio stream=music legacy-pre21");
            }
            mMediaPlayer.setScreenOnWhilePlaying(true);
            try {
                AudioManager audioManager = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    logCurrentStreamState(audioManager, "config");
                }
            } catch (Throwable volumeError) {
                writeRuntimeLog("echo-system-audio streamState failed err=" + volumeError.getMessage());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "applyAudioOutputConfiguration failed in state=" + mState, e);
            writeRuntimeLog("echo-system-audio config-failed state=" + mState + " err=" + e.getMessage());
        }
    }

    private void restoreRequestedVolume() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            applyRequestedVolume();
            long now = System.currentTimeMillis();
            if (now - mLastVolumeStateLogAtMs >= 5000L) {
                mLastVolumeStateLogAtMs = now;
                logInfo("echo-system-audio volume=100");
                AudioManager audioManager = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    logCurrentStreamState(audioManager, "restore");
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "restoreRequestedVolume failed in state=" + mState, e);
            writeRuntimeLog("echo-system-audio volume-failed state=" + mState + " err=" + e.getMessage());
        }
    }

    private void logCurrentStreamState(AudioManager audioManager, String reason) {
        if (audioManager == null) {
            return;
        }
        try {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            logInfo("echo-system-audio streamState reason=" + reason + " current=" + currentVolume + "/" + maxVolume);
        } catch (Throwable volumeError) {
            writeRuntimeLog("echo-system-audio streamState-failed reason=" + reason + " err=" + volumeError.getMessage());
        }
    }

    private void ensurePreferredAudioTrackSelected(String reason) {
        if (mMediaPlayer == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                || !canInspectTrackInfo()) {
            return;
        }
        if (!isJava64TouchPhone()) {
            return;
        }
        try {
            MediaPlayer.TrackInfo[] trackInfos = mMediaPlayer.getTrackInfo();
            if (trackInfos == null || trackInfos.length == 0) {
                logInfo("echo-system-audio-track none reason=" + reason);
                return;
            }
            int selectedAudio = mMediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
            if (selectedAudio >= 0 && selectedAudio < trackInfos.length) {
                MediaPlayer.TrackInfo selected = trackInfos[selectedAudio];
                if (selected != null && selected.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    logInfo("echo-system-audio-track keep reason=" + reason + " index=" + selectedAudio
                            + " lang=" + safeLanguage(selected.getLanguage()));
                    return;
                }
            }
            int fallbackIndex = -1;
            for (int i = 0; i < trackInfos.length; i++) {
                MediaPlayer.TrackInfo info = trackInfos[i];
                if (info != null && info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    fallbackIndex = i;
                    break;
                }
            }
            if (fallbackIndex >= 0) {
                mMediaPlayer.selectTrack(fallbackIndex);
                MediaPlayer.TrackInfo selected = trackInfos[fallbackIndex];
                logInfo("echo-system-audio-track select reason=" + reason + " index=" + fallbackIndex
                        + " lang=" + safeLanguage(selected == null ? null : selected.getLanguage()));
                restoreRequestedVolume();
            } else {
                logInfo("echo-system-audio-track missing reason=" + reason);
            }
        } catch (Throwable th) {
            writeRuntimeLog("echo-system-audio-track failed reason=" + reason + " err=" + th.getMessage());
        }
    }

    private boolean shouldForceSafePcmAudio(Map<String, String> headers) {
        return isLikely32BitTvDevice()
                && hasInternalHeaderValue(headers, HEADER_PROBE_TV32_SAFE_PCM, "1");
    }

    private void logTrackState(String reason) {
        if (mMediaPlayer == null || !canInspectTrackInfo() || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return;
        }
        try {
            MediaPlayer.TrackInfo[] trackInfos = mMediaPlayer.getTrackInfo();
            if (trackInfos == null) {
                logInfo("echo-system-track reason=" + reason + " none");
                return;
            }
            int selectedAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    ? mMediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) : -1;
            int selectedTimedText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    ? mMediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) : -1;
            int selectedSubtitle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    ? mMediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) : -1;
            List<String> summary = new ArrayList<>();
            for (int i = 0; i < trackInfos.length; i++) {
                MediaPlayer.TrackInfo info = trackInfos[i];
                if (info == null) {
                    continue;
                }
                int type = info.getTrackType();
                String tag;
                if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                    tag = "v";
                } else if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    tag = "a";
                } else if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    tag = "tt";
                } else if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                    tag = "sub";
                } else {
                    tag = String.valueOf(type);
                }
                boolean selected = i == selectedAudio || i == selectedTimedText || i == selectedSubtitle;
                summary.add(i + ":" + tag + ":" + safeLanguage(info.getLanguage()) + (selected ? "*" : ""));
            }
            logInfo("echo-system-track reason=" + reason + " " + TextUtils.join(",", summary));
        } catch (Throwable th) {
            writeRuntimeLog("echo-system-track failed reason=" + reason + " err=" + th.getMessage());
        }
    }

    private String safeLanguage(String language) {
        return TextUtils.isEmpty(language) ? "und" : language;
    }

    private void resetSystemTrackState() {
        mDeferredTrackSelection = -1;
        mDeferredTrackDeselection = -1;
        mLastDispatchedSubtitleText = null;
        mSubtitleDispatchGeneration++;
    }

    private boolean isJava64TouchPhone() {
        try {
            android.content.pm.PackageManager pm = mAppContext == null ? null : mAppContext.getPackageManager();
            if (pm == null) {
                return false;
            }
            if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)) {
                return false;
            }
            if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return android.os.Process.is64Bit();
            }
            // Build.SUPPORTED_64_BIT_ABIS is API 21+, while this library still supports API 19.
            // On older releases the process ABI is the only safe signal available here.
            String abi = Build.CPU_ABI;
            return abi != null && abi.contains("64");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isLikely32BitTvDevice() {
        try {
            if (mAppContext == null || isCurrentProcess64Bit()) {
                return false;
            }
            UiModeManager uiModeManager = (UiModeManager) mAppContext.getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null
                    && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
            android.content.pm.PackageManager pm = mAppContext.getPackageManager();
            if (pm != null && (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION))) {
                return true;
            }
            int screenLayout = mAppContext.getResources().getConfiguration().screenLayout
                    & Configuration.SCREENLAYOUT_SIZE_MASK;
            boolean largeScreen = screenLayout > Configuration.SCREENLAYOUT_SIZE_LARGE;
            boolean phoneLike = false;
            try {
                TelephonyManager tm = (TelephonyManager) mAppContext.getSystemService(Context.TELEPHONY_SERVICE);
                phoneLike = tm != null && tm.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
            } catch (Throwable ignored) {
            }
            return largeScreen && !phoneLike;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isLikelyTvOffloadRiskDevice() {
        try {
            if (mAppContext == null || isJava64TouchPhone()) {
                return false;
            }
            UiModeManager uiModeManager = (UiModeManager) mAppContext.getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null
                    && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
            android.content.pm.PackageManager pm = mAppContext.getPackageManager();
            if (pm != null) {
                if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                        || pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)) {
                    return true;
                }
                if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) {
                    return false;
                }
            }
            int screenLayout = mAppContext.getResources().getConfiguration().screenLayout
                    & Configuration.SCREENLAYOUT_SIZE_MASK;
            boolean largeScreen = screenLayout > Configuration.SCREENLAYOUT_SIZE_LARGE;
            boolean phoneLike = false;
            try {
                TelephonyManager tm = (TelephonyManager) mAppContext.getSystemService(Context.TELEPHONY_SERVICE);
                phoneLike = tm != null && tm.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
            } catch (Throwable ignored) {
            }
            return largeScreen && !phoneLike;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCurrentProcess64Bit() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return android.os.Process.is64Bit();
            }
            // Build.SUPPORTED_64_BIT_ABIS is unavailable below API 21. Use the
            // process ABI on the API 19-22 compatibility path instead.
            String abi = Build.CPU_ABI;
            return abi != null && abi.contains("64");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyRequestedVolume() {
        if (mMediaPlayer == null) {
            return;
        }
        mMediaPlayer.setVolume(mRequestedLeftVolume, mRequestedRightVolume);
    }

    private boolean canAccessPlaybackParams() {
        return mState == STATE_PREPARED
                || mState == STATE_STARTED
                || mState == STATE_PAUSED
                || mState == STATE_COMPLETED;
    }

    private void applyRequestedPlaybackSpeed() {
        if (mMediaPlayer == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || !canAccessPlaybackParams()
                || !NativePlayerOperationPolicy.canAccessPlaybackParams(
                isNativeSeekTransitionActive())) {
            return;
        }
        try {
            mMediaPlayer.setPlaybackParams(
                    mMediaPlayer.getPlaybackParams().setSpeed(mRequestedPlaybackSpeed));
        } catch (IllegalStateException e) {
            Log.w(TAG, "setSpeed ignored in state=" + mState, e);
        } catch (RuntimeException e) {
            Log.w(TAG, "setSpeed runtime failure", e);
        }
    }

    private boolean hasVideoOutputTarget() {
        if (!mHasVideoOutputTarget) {
            return false;
        }
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
        if (!mPendingStartAfterDisplayReady || mMediaPlayer == null) {
            return;
        }
        if (mState != STATE_PREPARED && mState != STATE_PAUSED && mState != STATE_STARTED) {
            return;
        }
        if (!hasVideoOutputTarget()) {
            return;
        }
        logInfo("echo-system-start-gate display-ready matroska=" + mCurrentDataSourceMatroskaLike
                + " hdrLike=" + isCurrentHdrLikeDataSource());
        mPendingStartAfterDisplayReady = false;
        start();
    }

    private void armVideoRenderGate(String reason) {
        cancelVideoRenderWatchdog();
        mWaitForRealVideoFrame = true;
        mVideoRenderStartSeen = false;
        mVideoRenderWatchdogGeneration++;
        mVideoRenderWatchdogArmedPercent = getBufferedPercentage();
        logInfo("echo-system-video render-watch-arm reason=" + reason
                + " timeoutMs=" + getVideoRenderStartTimeoutMs());
        restoreRequestedVolume();
    }

    private void clearVideoRenderGate(String reason) {
        cancelVideoRenderWatchdog();
        mWaitForRealVideoFrame = false;
        mVideoRenderStartSeen = false;
        mVideoRenderWatchdogGeneration++;
        logInfo("echo-system-video gate-clear reason=" + reason);
    }

    private void scheduleVideoRenderWatchdogIfNeeded(String reason) {
        if (!mWaitForRealVideoFrame || mVideoRenderStartSeen || mMediaPlayer == null) {
            return;
        }
        final int generation = mVideoRenderWatchdogGeneration;
        final String gateReason = reason;
        final long timeoutMs = getVideoRenderStartTimeoutMs();
        logInfo("echo-system-video watchdog-start reason=" + gateReason
                + " gen=" + generation
                + " timeoutMs=" + timeoutMs);
        cancelVideoRenderWatchdog();
        mScheduledVideoRenderWatchdogGeneration = generation;
        mScheduledVideoRenderWatchdogReason = gateReason;
        mMainHandler.postDelayed(mVideoRenderWatchdogRunnable, timeoutMs);
    }

    private void cancelVideoRenderWatchdog() {
        mMainHandler.removeCallbacks(mVideoRenderWatchdogRunnable);
        clearScheduledVideoRenderWatchdogState();
    }

    private void clearScheduledVideoRenderWatchdogState() {
        mScheduledVideoRenderWatchdogGeneration = -1;
        mScheduledVideoRenderWatchdogReason = null;
    }

    private void restartVideoRenderWatchdogAfterBuffering(String reason) {
        if (!mWaitForRealVideoFrame || mVideoRenderStartSeen || mMediaPlayer == null) {
            return;
        }
        mVideoRenderWatchdogGeneration++;
        mVideoRenderWatchdogArmedPercent = getBufferedPercentage();
        scheduleVideoRenderWatchdogIfNeeded(reason);
    }

    private void handleVideoRenderWatchdog(int generation, String reason) {
        if (generation != mVideoRenderWatchdogGeneration
                || !mWaitForRealVideoFrame
                || mVideoRenderStartSeen
                || mMediaPlayer == null
                || mState != STATE_STARTED) {
            return;
        }
        if (!NativePlayerOperationPolicy.canRunFirstFrameRecovery(
                isNativeSeekTransitionActive())) {
            logInfo("echo-system-video watchdog-wait-seek reason=" + reason
                    + " target=" + resolvePendingSeekTarget());
            return;
        }
        int currentPercent = getBufferedPercentage();
        if (BufferingProgressPolicy.shouldDeferFirstFrameFailure(
                mNativeBuffering,
                mVideoRenderWatchdogArmedPercent,
                currentPercent)) {
            logInfo("echo-system-video watchdog-defer reason=" + reason
                    + " nativeBuffering=" + mNativeBuffering
                    + " armedPercent=" + mVideoRenderWatchdogArmedPercent
                    + " currentPercent=" + currentPercent);
            mVideoRenderWatchdogArmedPercent = currentPercent;
            scheduleVideoRenderWatchdogIfNeeded(reason);
            return;
        }
        failBeforeFirstVideoFrame("watchdog:" + reason);
    }

    private boolean shouldTreatAsVideoStartupFailure(int extra) {
        if (mVideoRenderStartSeen || mMediaPlayer == null) {
            return false;
        }
        if (!NativePlayerOperationPolicy.canRunFirstFrameRecovery(
                isNativeSeekTransitionActive())) {
            return false;
        }
        if (mNativeBuffering) {
            return false;
        }
        if (usesExtendedTv32DirectHdrMatroskaFirstFrameWait()) {
            return false;
        }
        if (mState != STATE_STARTED && mState != STATE_PREPARED && mState != STATE_PREPARING) {
            return false;
        }
        if (!mWaitForRealVideoFrame && mState != STATE_STARTED) {
            return false;
        }
        return !isAudioOnlyTrackList();
    }

    private long getVideoRenderStartTimeoutMs() {
        return usesExtendedTv32DirectHdrMatroskaFirstFrameWait()
                ? TV32_DIRECT_HDR_MATROSKA_RENDER_START_TIMEOUT_MS
                : VIDEO_RENDER_START_TIMEOUT_MS;
    }

    private boolean usesExtendedTv32DirectHdrMatroskaFirstFrameWait() {
        if (!isLikely32BitTvDevice()
                || !mCurrentDataSourceMatroskaLike
                || mResolvedDataSourceMode != DATA_SOURCE_URI) {
            return false;
        }
        String activeUrl = firstNonEmpty(mCurrentDataSourceUrl);
        // Startup metadata is intentionally best-effort. A slow warm-up read must not
        // turn a valid local HDR Matroska into a short watchdog/error path just because
        // its HDR markers were not available before native preparation began.
        return isLocalProxyPlayUrl(activeUrl)
                || !TextUtils.isEmpty(getNestedLocalProxyPlayUrl(activeUrl));
    }

    private void failBeforeFirstVideoFrame(String reason) {
        if (mState == STATE_ERROR || mMediaPlayer == null) {
            return;
        }
        if (!NativePlayerOperationPolicy.canRunFirstFrameRecovery(
                isNativeSeekTransitionActive())) {
            logInfo("echo-system-video first-frame-recover deferred reason=" + reason
                    + " target=" + resolvePendingSeekTarget());
            return;
        }
        finishPlaybackPrebuffer();
        clearSeekState();
        logInfo("echo-system-video first-frame-recover reason=" + reason
                + " state=" + mState
                + " hasTarget=" + hasVideoOutputTarget()
                + " url=" + firstNonEmpty(mCurrentDataSourceUrl));
        mState = STATE_ERROR;
        mIsPreparing = false;
        mPendingStartAfterDisplayReady = false;
        mIsBuffering = false;
        mNativeBuffering = false;
        mBufferingInfoVisible = false;
        mAvoidPositionQueryUntilMs = 0L;
        mWaitForRealVideoFrame = false;
        cancelVideoRenderWatchdog();
        mVideoRenderWatchdogGeneration++;
        try {
            if (canPause()) {
                mMediaPlayer.pause();
            }
        } catch (Throwable ignored) {
        }
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    public boolean isPositionQueryUnstable() {
        return shouldAvoidBlockingPositionQuery();
    }

    public boolean isStartPending() {
        if (mMediaPlayer == null || mState == STATE_ERROR || mState == STATE_RELEASED) {
            return false;
        }
        return mState != STATE_STARTED
                && (mState == STATE_PREPARED
                || mPendingStartAfterDisplayReady
                || mSeekCoordinator.shouldResumeAfterFinalSeek());
    }

    public boolean isReadyForSubtitleSelection() {
        return mMediaPlayer != null
                && mState == STATE_STARTED
                && mVideoRenderStartSeen
                && !isSeekInFlight();
    }

    public boolean isSeekInFlight() {
        return mSeekCoordinator.isInFlight();
    }

    public int getPendingSeekPosition() {
        int activeTarget = mSeekCoordinator.getActiveTarget();
        if (activeTarget != SeekCoordinator.NO_TARGET) {
            return activeTarget;
        }
        return SeekCoordinator.NO_TARGET;
    }

    private boolean isCurrentHdrLikeDataSource() {
        return hasInternalHeaderValue(mCurrentDataSourceHeaders, HEADER_PROBE_DOLBY_VISION, "1")
                || hasInternalHeaderValue(mCurrentDataSourceHeaders, HEADER_PROBE_HDR10, "1")
                || hasInternalHeaderValue(mCurrentDataSourceHeaders, HEADER_PROBE_HDR10_PLUS, "1");
    }

    private boolean canInspectTrackInfo() {
        return NativePlayerOperationPolicy.canQuery(isNativeSeekTransitionActive())
                && (mState == STATE_PREPARED
                || mState == STATE_STARTED
                || mState == STATE_PAUSED
                || mState == STATE_COMPLETED);
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
        if (mMediaPlayer == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                || !canInspectTrackInfo()) {
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
        if (!canInspectTrackInfo()) {
            mDeferredTrackSelection = index;
            mDeferredTrackDeselection = -1;
            logInfo("echo-system-track defer-select index=" + index);
            return;
        }
        try {
            dispatchTimedText("", "track-select");
            mMediaPlayer.selectTrack(index);
        } catch (Throwable e) {
            Log.w(TAG, "selectTrack failed index=" + index, e);
        }
    }

    public void deselectTrack(int index) {
        if (mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        if (!canInspectTrackInfo()) {
            mDeferredTrackDeselection = index;
            mDeferredTrackSelection = -1;
            logInfo("echo-system-track defer-deselect index=" + index);
            return;
        }
        try {
            dispatchTimedText("", "track-deselect");
            mMediaPlayer.deselectTrack(index);
        } catch (Throwable e) {
            Log.w(TAG, "deselectTrack failed index=" + index, e);
        }
    }

    private void applyDeferredTrackOperation() {
        int selection = mDeferredTrackSelection;
        int deselection = mDeferredTrackDeselection;
        mDeferredTrackSelection = -1;
        mDeferredTrackDeselection = -1;
        if (selection >= 0) {
            selectTrack(selection);
        } else if (deselection >= 0) {
            deselectTrack(deselection);
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

    public synchronized void setOnTimedTextListener(OnTimedTextListener listener) {
        mOnTimedTextListener = listener;
        mLastDispatchedSubtitleText = null;
        mSubtitleDispatchGeneration++;
    }

    private boolean shouldAvoidBlockingPositionQuery() {
        if (!NativePlayerOperationPolicy.canQuery(isNativeSeekTransitionActive())) {
            return true;
        }
        if (!isLikely32BitTvDevice()) {
            return false;
        }
        return mState == STATE_PREPARING
                || mState == STATE_PREPARED
                || mIsBuffering
                || System.currentTimeMillis() < mAvoidPositionQueryUntilMs
                || isSeekInFlight()
                || mPendingStartAfterDisplayReady;
    }

    private boolean isNativeSeekTransitionActive() {
        return mSeekCoordinator.isInFlight();
    }

    private int resolvePendingSeekTarget() {
        int active = mSeekCoordinator.getActiveTarget();
        if (active != SeekCoordinator.NO_TARGET) {
            return active;
        }
        return mLastRequestedSeekTarget;
    }

    private void installSubtitleDataListener() {
        if (mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        try {
            final MediaPlayer owner = mMediaPlayer;
            Class<?> listenerClass = Class.forName("android.media.MediaPlayer$OnSubtitleDataListener");
            mSubtitleDataListenerProxy = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onSubtitleData".equals(method.getName()) && args != null && args.length >= 2) {
                            if (NativePlayerCallbackPolicy.isCurrent(owner, mMediaPlayer)) {
                                handleSubtitleData(args[1]);
                            } else {
                                logInfo("echo-system-callback ignore-stale event=subtitle-data");
                            }
                        }
                        return null;
                    });
            Method setListener = MediaPlayer.class.getMethod("setOnSubtitleDataListener", listenerClass);
            setListener.invoke(mMediaPlayer, mSubtitleDataListenerProxy);
            logInfo("echo-system-subtitle-data-listener installed");
        } catch (Throwable th) {
            mSubtitleDataListenerProxy = null;
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
        } finally {
            mSubtitleDataListenerProxy = null;
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
            logInfo("echo-system-subtitle-data len=" + (text == null ? 0 : text.length()));
            dispatchTimedText(text, "subtitle-data");
        } catch (Throwable th) {
            Log.w(TAG, "handleSubtitleData failed", th);
            writeRuntimeLog("echo-system-subtitle-data failed " + th.getMessage());
        }
    }

    private synchronized void dispatchTimedText(String value, String source) {
        if (mOnTimedTextListener == null) {
            return;
        }
        String cleaned = sanitizeSubtitlePayload(value);
        if (TextUtils.equals(cleaned, mLastDispatchedSubtitleText)) {
            Log.i(TAG, "echo-system-subtitle-dup source=" + source + " len=" + cleaned.length());
            return;
        }
        mLastDispatchedSubtitleText = cleaned;
        final int generation = ++mSubtitleDispatchGeneration;
        final OnTimedTextListener listener = mOnTimedTextListener;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onTimedText(cleaned);
        } else {
            mMainHandler.post(() -> {
                if (mOnTimedTextListener == listener
                        && mSubtitleDispatchGeneration == generation) {
                    listener.onTimedText(cleaned);
                }
            });
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
        String effectiveUrl = resolveEffectiveSystemDataSourceUrl(normalizedUrl, headers);
        boolean probedMatroska = hasInternalHeaderValue(headers, HEADER_PROBE_CONTAINER, "matroska");
        boolean probedDolbyVision = hasInternalHeaderValue(headers, HEADER_PROBE_DOLBY_VISION, "1");
        boolean tvSafeRemoteNetworkPath = shouldForceTvSafeRemoteNetworkPath(effectiveUrl, headers);
        mForceSafePcmAudio = shouldForceSafePcmAudio(headers) || tvSafeRemoteNetworkPath;
        applyAudioOutputConfiguration();
        boolean audioPassthrough = hasInternalHeaderValue(headers, HEADER_PROBE_AUDIO_PASSTHROUGH, "1");
        boolean audioPassthroughAllowed = hasInternalHeaderValue(headers, HEADER_PROBE_AUDIO_PASSTHROUGH_ALLOWED, "1");
        boolean audioPassthroughEnabled = audioPassthrough && audioPassthroughAllowed;
        logInfo("echo-system-audio route safePcm=" + mForceSafePcmAudio
                + " passthroughRequested=" + audioPassthrough
                + " passthroughAllowed=" + audioPassthroughAllowed
                + " passthroughEffective=" + audioPassthroughEnabled
                + " decodeRequired=" + !audioPassthroughEnabled
                + " tv32=" + isLikely32BitTvDevice()
                + " tvLike=" + isLikelyTvOffloadRiskDevice()
                + " tvSafe=" + tvSafeRemoteNetworkPath
                + " tv32SafePcm=" + hasInternalHeaderValue(headers, HEADER_PROBE_TV32_SAFE_PCM, "1")
                + " hdr10=" + hasInternalHeaderValue(headers, HEADER_PROBE_HDR10, "1")
                + " hdr10Plus=" + hasInternalHeaderValue(headers, HEADER_PROBE_HDR10_PLUS, "1")
                + " dv=" + probedDolbyVision);
        boolean matroskaLike = probedMatroska
                || isMatroskaLike(effectiveUrl)
                || isMatroskaLike(getNestedLocalProxyPlayUrl(effectiveUrl))
                || isMatroskaLike(getAppStreamNestedRemoteUrl(effectiveUrl));
        mCurrentDataSourceUrl = effectiveUrl;
        mCurrentDataSourceHeaders = headers;
        mCurrentDataSourceMatroskaLike = matroskaLike;
        if (matroskaLike) {
            logInfo("echo-system-data-source nativeMatroskaUri url=" + effectiveUrl
                    + " matroska=" + matroskaLike
                    + " probedMatroska=" + probedMatroska
                    + " probedDv=" + probedDolbyVision
                    + " mode=" + networkSourceModeName(mNetworkSourceMode));
        }
        Map<String, String> externalHeaders = cleanExternalHeaders(headers);
        if (shouldUseProxyBackedDataSource(effectiveUrl, headers)) {
            try {
                if (trySetProxyBackedDataSource(effectiveUrl, headers, externalHeaders, matroskaLike)) {
                    return;
                }
            } catch (Throwable proxyBackedError) {
                closeCustomDataSourceQuietly();
                logInfo("echo-system-data-source proxy-backed failed fallback uri url="
                        + effectiveUrl + " err=" + proxyBackedError.getClass().getSimpleName()
                        + ":" + proxyBackedError.getMessage());
            }
        }
        try {
            Uri uri = Uri.parse(effectiveUrl);
            boolean networkUri = isNetworkScheme(uri == null ? null : uri.getScheme());
            logInfo("echo-system-data-source uri url=" + uri + " matroska=" + matroskaLike + " network=" + networkUri);
            closeCustomDataSourceQuietly();
            if (networkUri) {
                if (shouldUseContextUriNetworkDataSource(effectiveUrl, headers) || externalHeaders != null) {
                    mMediaPlayer.setDataSource(mAppContext, uri, externalHeaders == null ? Collections.emptyMap() : externalHeaders);
                    logInfo("echo-system-data-source uri-context url=" + uri
                            + " matroska=" + matroskaLike
                            + " network=true headers=" + (externalHeaders == null ? 0 : externalHeaders.size()));
                } else {
                    mMediaPlayer.setDataSource(effectiveUrl);
                }
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
        boolean localProxyPlay = isLocalProxyPlayUrl(normalizedUrl);
        boolean tv32WrappedLocalProxyVod = isTv32WrappedLocalProxyVodUrl(normalizedUrl);
        if (shouldBypassProxyBackedSourceForTv32LocalProxyVod(normalizedUrl)) {
            logInfo("echo-system-data-source tv32-localplay-direct-uri url=" + normalizedUrl);
            return false;
        }
        if (mNetworkSourceMode == NETWORK_SOURCE_MODE_FORCE_URI) {
            return false;
        }
        if (mNetworkSourceMode == NETWORK_SOURCE_MODE_FORCE_PROXY) {
            if (tv32WrappedLocalProxyVod) {
                logInfo("echo-system-data-source tv32-app-stream-force-proxy url=" + normalizedUrl);
            }
            return localProxyPlay || tv32WrappedLocalProxyVod;
        }
        if (shouldBypassProxyBackedSourceForJava64HdrMatroska(headers, normalizedUrl)) {
            logInfo("echo-system-data-source java64-hdr-direct-localplay url=" + normalizedUrl);
            return false;
        }
        if (shouldBypassProxyBackedSourceForNativeDv(headers, normalizedUrl)) {
            logInfo("echo-system-data-source native-dv-direct-localplay url=" + normalizedUrl);
            return false;
        }
        if (shouldBypassProxyBackedSourceForTv32Hdr(headers, normalizedUrl)) {
            logInfo("echo-system-data-source tv32-matroska-direct-localplay url=" + normalizedUrl);
            return false;
        }
        if (shouldForceTvSafeRemoteNetworkPath(normalizedUrl, headers)) {
            logInfo("echo-system-data-source tv-safe-remote-network proxy-backed url=" + normalizedUrl);
            return true;
        }
        if (tv32WrappedLocalProxyVod) {
            logInfo("echo-system-data-source tv32-app-stream-proxy-backed url=" + normalizedUrl);
        }
        return localProxyPlay || tv32WrappedLocalProxyVod;
    }

    private boolean shouldBypassProxyBackedSourceForTv32LocalProxyVod(String normalizedUrl) {
        // 32-bit TV local /proxy/play VOD used to be forced onto a direct URI path,
        // but that bypassed the larger proxy-backed buffering window and caused
        // repeated mid-playback BUFFERING_START/END stalls on MP4 VOD.
        // Keep non-HLS local proxy VOD on proxy-fd / MediaDataSource unless one of
        // the narrower HDR/DV-specific bypass rules below explicitly applies.
        return false;
    }

    private boolean isTv32WrappedLocalProxyVodUrl(String normalizedUrl) {
        if (!isLikely32BitTvDevice() || !isAppStreamProxyUrl(normalizedUrl)) {
            return false;
        }
        String nestedLocalPlay = getNestedLocalProxyPlayUrl(normalizedUrl);
        return !TextUtils.isEmpty(nestedLocalPlay)
                && !isHlsLike(nestedLocalPlay)
                && isLocalProxyPlayUrl(nestedLocalPlay);
    }

    private boolean shouldBypassProxyBackedSourceForNativeDv(Map<String, String> headers, String normalizedUrl) {
        if (!isLocalProxyPlayUrl(normalizedUrl)) {
            return false;
        }
        boolean probedDolbyVision = hasInternalHeaderValue(headers, HEADER_PROBE_DOLBY_VISION, "1");
        boolean nativeDvDevice = hasInternalHeaderValue(headers, HEADER_PROBE_NATIVE_DV_DEVICE, "1");
        boolean java64LocalProxyFast = hasInternalHeaderValue(headers, HEADER_PROBE_JAVA64_LOCAL_PROXY_FAST, "1");
        if ((!probedDolbyVision && !java64LocalProxyFast) || !nativeDvDevice) {
            return false;
        }
        // 64-bit 触屏设备具备原生 DV 解码时，必须直接交给系统播放器自己处理 URI，
        // 不能再走 proxy-fd / MediaDataSource 喂流桥，否则会重新落回兼容链式行为，
        // 导致偏色、掉帧、黑屏或全屏状态异常。
        return isJava64TouchPhone();
    }

    private boolean shouldBypassProxyBackedSourceForJava64HdrMatroska(Map<String, String> headers, String normalizedUrl) {
        if (!isJava64TouchPhone() || !isJava64HdrMatroska(headers, normalizedUrl)) {
            return false;
        }
        return isLocalProxyPlayUrl(normalizedUrl)
                || !TextUtils.isEmpty(getNestedLocalProxyPlayUrl(normalizedUrl));
    }

    private boolean shouldBypassProxyBackedSourceForTv32Hdr(Map<String, String> headers, String normalizedUrl) {
        if (!isLocalProxyPlayUrl(normalizedUrl) || !isLikely32BitTvDevice()) {
            return false;
        }
        boolean matroskaLike = hasInternalHeaderValue(headers, HEADER_PROBE_CONTAINER, "matroska")
                || isMatroskaLike(normalizedUrl)
                || isMatroskaLike(getNestedLocalProxyPlayUrl(normalizedUrl));
        if (!matroskaLike) {
            return false;
        }
        boolean hdrLike = hasInternalHeaderValue(headers, HEADER_PROBE_DOLBY_VISION, "1")
                || hasInternalHeaderValue(headers, HEADER_PROBE_HDR10, "1")
                || hasInternalHeaderValue(headers, HEADER_PROBE_HDR10_PLUS, "1");
        boolean startupDirect = hasInternalHeaderValue(headers, HEADER_PROBE_TV32_STARTUP_DIRECT, "1");
        boolean directUri = Tv32MatroskaRoutePolicy.shouldUseDirectUri(hdrLike, startupDirect);
        logInfo("echo-system-data-source tv32-localplay-bypass matroska=true hdrLike=" + hdrLike
                + " startupDirect=" + startupDirect
                + " route=" + (directUri ? "direct-uri" : "proxy-fd")
                + " url=" + normalizedUrl);
        return directUri;
    }

    private boolean shouldForceTvSafeRemoteNetworkPath(String normalizedUrl, Map<String, String> headers) {
        if (!isLikelyTvOffloadRiskDevice()
                || TextUtils.isEmpty(normalizedUrl)
                || isHlsLike(normalizedUrl)) {
            return false;
        }
        Uri uri = Uri.parse(normalizedUrl);
        if (!isNetworkScheme(uri == null ? null : uri.getScheme())
                || isLocalHost(uri == null ? null : uri.getHost())) {
            return false;
        }
        if (isLocalProxyPlayUrl(normalizedUrl)
                || isAppStreamProxyUrl(normalizedUrl)
                || !TextUtils.isEmpty(getNestedLocalProxyPlayUrl(normalizedUrl))) {
            return false;
        }
        if (hasInternalHeaderValue(headers, HEADER_PROBE_DOLBY_VISION, "1")
                || hasInternalHeaderValue(headers, HEADER_PROBE_HDR10, "1")
                || hasInternalHeaderValue(headers, HEADER_PROBE_HDR10_PLUS, "1")) {
            return false;
        }
        return !hasInternalHeaderValue(headers, HEADER_PROBE_AUDIO_PASSTHROUGH_ALLOWED, "1");
    }

    private boolean shouldUseContextUriNetworkDataSource(String normalizedUrl, Map<String, String> headers) {
        if (TextUtils.isEmpty(normalizedUrl)) {
            return false;
        }
        if (mNetworkSourceMode == NETWORK_SOURCE_MODE_FORCE_PROXY) {
            return false;
        }
        Uri uri = Uri.parse(normalizedUrl);
        if (!isNetworkScheme(uri == null ? null : uri.getScheme())) {
            return false;
        }
        if (shouldForceTvSafeRemoteNetworkPath(normalizedUrl, headers)
                && shouldBypassTvSafeMediaDataSourceForSignedUrl(normalizedUrl)) {
            return true;
        }
        if (shouldBypassProxyBackedSourceForNativeDv(headers, normalizedUrl)) {
            return false;
        }
        if (isJava64HdrMatroska(headers, normalizedUrl)) {
            return isAppStreamProxyUrl(normalizedUrl)
                    && !TextUtils.isEmpty(getNestedLocalProxyPlayUrl(normalizedUrl));
        }
        return false;
    }

    private boolean trySetProxyBackedDataSource(String normalizedUrl,
                                                Map<String, String> internalHeaders,
                                                Map<String, String> headers,
                                                boolean matroskaLike) throws Exception {
        closeCustomDataSourceQuietly();
        boolean preferTvSafeMediaDataSource = shouldForceTvSafeRemoteNetworkPath(normalizedUrl, internalHeaders);
        if (preferTvSafeMediaDataSource) {
            logInfo("echo-system-data-source tv-safe-remote-network prefer=media-data-source url=" + normalizedUrl);
            if (shouldBypassTvSafeMediaDataSourceForSignedUrl(normalizedUrl)) {
                logInfo("echo-system-data-source tv-safe-remote-network skip=media-data-source reason=expiring-signed-url url=" + normalizedUrl);
                return false;
            }
        }
        if (!preferTvSafeMediaDataSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            HttpRangeMediaDataSource mediaDataSource = HttpRangeMediaDataSource.createForStreamingPlayback(normalizedUrl, headers);
            try {
                mMediaPlayer.setDataSource(mediaDataSource);
                mCurrentMediaDataSource = mediaDataSource;
                mResolvedDataSourceMode = DATA_SOURCE_MEDIA_DATA_SOURCE;
                logInfo("echo-system-data-source media-data-source url=" + normalizedUrl
                        + " matroska=" + matroskaLike
                        + " tvSafe=" + preferTvSafeMediaDataSource
                        + " cacheWindow=8-32MB");
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

    private boolean shouldUnwrapAppStreamProxyForNativeJava64Dv(String url, Map<String, String> headers) {
        return isAppStreamProxyUrl(url)
                && isJava64HdrMatroska(headers, url)
                && isJava64TouchPhone();
    }

    private boolean isJava64HdrMatroska(Map<String, String> headers, String url) {
        if (!isJava64TouchPhone()) {
            return false;
        }
        boolean hdrLike = hasInternalHeaderValue(headers, HEADER_PROBE_DOLBY_VISION, "1")
                || hasInternalHeaderValue(headers, HEADER_PROBE_HDR10, "1")
                || hasInternalHeaderValue(headers, HEADER_PROBE_HDR10_PLUS, "1")
                || hasInternalHeaderValue(headers, HEADER_PROBE_JAVA64_LOCAL_PROXY_FAST, "1");
        if (!hdrLike) {
            return false;
        }
        return hasInternalHeaderValue(headers, HEADER_PROBE_CONTAINER, "matroska")
                || isMatroskaLike(url)
                || isMatroskaLike(getNestedLocalProxyPlayUrl(url))
                || isMatroskaLike(getAppStreamNestedRemoteUrl(url));
    }

    private String resolveEffectiveSystemDataSourceUrl(String normalizedUrl, Map<String, String> headers) {
        if (TextUtils.isEmpty(normalizedUrl)) {
            return normalizedUrl;
        }
        String nestedLocalPlay = getNestedLocalProxyPlayUrl(normalizedUrl);
        if (shouldForceTv32MatroskaNestedLocalPlay(normalizedUrl, nestedLocalPlay, headers)) {
            logInfo("echo-system-data-source tv32-force-nested-local-play mode="
                    + networkSourceModeName(mNetworkSourceMode) + " url=" + nestedLocalPlay);
            return nestedLocalPlay;
        }
        if (mNetworkSourceMode == NETWORK_SOURCE_MODE_AUTO) {
            return normalizedUrl;
        }
        if (!TextUtils.isEmpty(nestedLocalPlay)) {
            logInfo("echo-system-data-source force-nested-local-play mode="
                    + networkSourceModeName(mNetworkSourceMode) + " url=" + nestedLocalPlay);
            return nestedLocalPlay;
        }
        return normalizedUrl;
    }

    private boolean shouldForceTv32MatroskaNestedLocalPlay(String normalizedUrl,
                                                            String nestedLocalPlay,
                                                            Map<String, String> headers) {
        if (!isLikely32BitTvDevice()
                || !isAppStreamProxyUrl(normalizedUrl)
                || TextUtils.isEmpty(nestedLocalPlay)
                || !isLocalProxyPlayUrl(nestedLocalPlay)
                || isHlsLike(nestedLocalPlay)) {
            return false;
        }
        return hasInternalHeaderValue(headers, HEADER_PROBE_CONTAINER, "matroska")
                || isMatroskaLike(normalizedUrl)
                || isMatroskaLike(nestedLocalPlay);
    }

    private boolean retrySystemDataSourceForMissingAudioTrack(String reason) {
        if (!shouldAttemptJava64MissingAudioRecovery() || hasAudioTrackInfo() || !hasVideoTrackInfo()) {
            return false;
        }
        if (mResolvedDataSourceMode == DATA_SOURCE_URI && shouldKeepDirectUriForJava64Hdr()) {
            logInfo("echo-system-audio-recover skip reason=" + reason
                    + " mode=" + dataSourceModeName(mResolvedDataSourceMode)
                    + " keepDirectUri=true");
            return false;
        }
        int nextMode = shouldKeepDirectUriForJava64Hdr()
                ? NETWORK_SOURCE_MODE_FORCE_URI
                : (mResolvedDataSourceMode == DATA_SOURCE_URI
                ? NETWORK_SOURCE_MODE_FORCE_PROXY
                : NETWORK_SOURCE_MODE_FORCE_URI);
        String retryUrl = mCurrentDataSourceUrl;
        Map<String, String> retryHeaders = mCurrentDataSourceHeaders == null
                ? null : new java.util.HashMap<>(mCurrentDataSourceHeaders);
        mJava64MissingAudioRecoveryAttempted = true;
        mNetworkSourceMode = nextMode;
        logInfo("echo-system-audio-recover retry reason=" + reason
                + " from=" + dataSourceModeName(mResolvedDataSourceMode)
                + " to=" + networkSourceModeName(nextMode)
                + " url=" + retryUrl);
        try {
            reset();
            mNetworkSourceMode = nextMode;
            mJava64MissingAudioRecoveryAttempted = true;
            rebindLastVideoOutputTarget();
            setDataSourceInternal(retryUrl, retryHeaders);
            mState = STATE_INITIALIZED;
            mLastDataSourceSucceeded = true;
            mIsPreparing = true;
            mMediaPlayer.prepareAsync();
            mState = STATE_PREPARING;
            return true;
        } catch (Throwable th) {
            writeRuntimeLog("echo-system-audio-recover failed reason=" + reason
                    + " mode=" + networkSourceModeName(nextMode)
                    + " err=" + th.getClass().getSimpleName() + ":" + th.getMessage());
            Log.e(TAG, "retrySystemDataSourceForMissingAudioTrack failed", th);
            return false;
        }
    }

    private boolean shouldAttemptJava64MissingAudioRecovery() {
        if (mJava64MissingAudioRecoveryAttempted || !isJava64TouchPhone()) {
            return false;
        }
        if (!mCurrentDataSourceMatroskaLike || !isCurrentHdrLikeDataSource()) {
            return false;
        }
        String activeUrl = firstNonEmpty(mCurrentDataSourceUrl);
        return isLocalProxyPlayUrl(activeUrl) || !TextUtils.isEmpty(getNestedLocalProxyPlayUrl(activeUrl));
    }

    private boolean shouldKeepDirectUriForJava64Hdr() {
        if (!isJava64TouchPhone() || !mCurrentDataSourceMatroskaLike || !isCurrentHdrLikeDataSource()) {
            return false;
        }
        String activeUrl = firstNonEmpty(mCurrentDataSourceUrl);
        return isLocalProxyPlayUrl(activeUrl)
                || !TextUtils.isEmpty(getNestedLocalProxyPlayUrl(activeUrl));
    }

    private boolean hasAudioTrackInfo() {
        if (!canInspectTrackInfo() || mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return false;
        }
        try {
            MediaPlayer.TrackInfo[] trackInfos = mMediaPlayer.getTrackInfo();
            if (trackInfos == null) {
                return false;
            }
            for (MediaPlayer.TrackInfo info : trackInfos) {
                if (info != null && info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean hasVideoTrackInfo() {
        if (!canInspectTrackInfo() || mMediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return false;
        }
        try {
            MediaPlayer.TrackInfo[] trackInfos = mMediaPlayer.getTrackInfo();
            if (trackInfos == null) {
                return false;
            }
            for (MediaPlayer.TrackInfo info : trackInfos) {
                if (info != null && info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void rebindLastVideoOutputTarget() {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            if (mLastSurface != null && mLastSurface.isValid()) {
                mMediaPlayer.setSurface(mLastSurface);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mLastDisplayHolder != null
                    && mLastDisplayHolder.getSurface() != null
                    && mLastDisplayHolder.getSurface().isValid()) {
                mMediaPlayer.setDisplay(mLastDisplayHolder);
            }
        } catch (Throwable ignored) {
        }
    }

    private String dataSourceModeName(int mode) {
        if (mode == DATA_SOURCE_URI) {
            return "uri";
        }
        if (mode == DATA_SOURCE_MEDIA_DATA_SOURCE) {
            return "media-data-source";
        }
        if (mode == DATA_SOURCE_PROXY_FILE_DESCRIPTOR) {
            return "proxy-fd";
        }
        return "none";
    }

    private String networkSourceModeName(int mode) {
        if (mode == NETWORK_SOURCE_MODE_FORCE_URI) {
            return "force-uri";
        }
        if (mode == NETWORK_SOURCE_MODE_FORCE_PROXY) {
            return "force-proxy";
        }
        return "auto";
    }

    private String firstNonEmpty(String value) {
        return TextUtils.isEmpty(value) ? "" : value;
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
        if (ignoreStaleNativeCallback(mp, "timed-text")) {
            return;
        }
        if (mOnTimedTextListener != null) {
            String value = text == null ? null : text.getText();
            logInfo("echo-system-timed-text len=" + (value == null ? 0 : value.length()));
            dispatchTimedText(value, "timedtext");
        }
    }

    private boolean isLocalHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private boolean ignoreStaleNativeCallback(MediaPlayer callbackOwner, String event) {
        if (NativePlayerCallbackPolicy.isCurrent(callbackOwner, mMediaPlayer)) {
            return false;
        }
        logInfo("echo-system-callback ignore-stale event=" + event);
        return true;
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

    private boolean shouldBypassTvSafeMediaDataSourceForSignedUrl(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(path);
            if (!isNetworkScheme(uri == null ? null : uri.getScheme())
                    || isLocalHost(uri == null ? null : uri.getHost())) {
                return false;
            }
            String encodedQuery = uri.getEncodedQuery();
            if (TextUtils.isEmpty(encodedQuery)) {
                return false;
            }
            String lowerQuery = encodedQuery.toLowerCase(Locale.US);
            return lowerQuery.contains("x-amz-signature=")
                    && lowerQuery.contains("x-amz-expires=");
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
