package xyz.doikki.videoplayer.player;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.player.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.controller.MediaPlayerControl;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.util.L;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 播放器
 * Created by Doikki on 2017/4/7.
 */

public class VideoView<P extends AbstractPlayer> extends FrameLayout
        implements MediaPlayerControl, AbstractPlayer.PlayerEventListener {
    private static final String TAG = "VideoView";
    private static final long COMPLETION_END_TOLERANCE_MS = 10_000L;
    private static final long PERSIST_END_TOLERANCE_MS = COMPLETION_END_TOLERANCE_MS;
    protected static final long PERSIST_PROGRESS_SKIP = -1L;

    protected P mMediaPlayer;//播放器
    protected PlayerFactory<P> mPlayerFactory;//工厂类，用于实例化播放核心
    @Nullable
    protected BaseVideoController mVideoController;//控制器

    /**
     * 真正承载播放器视图的容器
     */
    protected FrameLayout mPlayerContainer;

    protected IRenderView mRenderView;
    protected RenderViewFactory mRenderViewFactory;

    public static final int SCREEN_SCALE_DEFAULT = 0;
    public static final int SCREEN_SCALE_16_9 = 1;
    public static final int SCREEN_SCALE_4_3 = 2;
    public static final int SCREEN_SCALE_MATCH_PARENT = 3;
    public static final int SCREEN_SCALE_ORIGINAL = 4;
    public static final int SCREEN_SCALE_CENTER_CROP = 5;
    protected int mCurrentScreenScaleType;

    protected int[] mVideoSize = {0, 0};

    protected boolean mIsMute;//是否静音

    //--------- data sources ---------//
    protected String mUrl;//当前播放视频的地址
    protected String mProgressKey = null;
    protected Map<String, String> mHeaders;//当前视频地址的请求头
    protected AssetFileDescriptor mAssetFileDescriptor;//assets文件

    protected long mCurrentPosition;//最后一次确认的真实播放位置
    protected long mResumePosition;//启动/重试时待恢复的目标位置
    protected long mLastKnownDuration;
    protected boolean mPendingResumeSeekAfterRender;
    protected boolean mResumeSeekAppliedAfterRender;

    //播放器的各种状态
    public static final int STATE_ERROR = -1;
    public static final int STATE_IDLE = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_PREPARED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_PAUSED = 4;
    public static final int STATE_PLAYBACK_COMPLETED = 5;
    public static final int STATE_BUFFERING = 6;
    public static final int STATE_BUFFERED = 7;
    public static final int STATE_START_ABORT = 8;//开始播放中止
    protected int mCurrentPlayState = STATE_IDLE;//当前播放器的状态

    public static final int PLAYER_NORMAL = 10;        // 普通播放器
    public static final int PLAYER_FULL_SCREEN = 11;   // 全屏播放器
    public static final int PLAYER_TINY_SCREEN = 12;   // 小屏播放器
    private static final long FULLSCREEN_SURFACE_MOVE_GUARD_MS = 450L;
    protected int mCurrentPlayerState = PLAYER_NORMAL;

    protected boolean mIsFullScreen;//是否处于全屏状态

    protected boolean mIsTinyScreen;//是否处于小屏状态
    protected int[] mTinyScreenSize = {0, 0};
    private ViewGroup.LayoutParams mNormalLayoutParamsBeforeFullScreen;
    private ViewGroup mNormalParentBeforeFullScreen;
    private int mNormalIndexBeforeFullScreen = -1;
    private boolean mInPlaceFullScreen;
    private boolean mIsFullScreenViewMoving;
    private final List<FullScreenLayoutState> mFullScreenLayoutStates = new ArrayList<>();

    private static final class FullScreenLayoutState {
        final View view;
        final ViewGroup.LayoutParams layoutParams;
        final int paddingLeft;
        final int paddingTop;
        final int paddingRight;
        final int paddingBottom;
        final boolean clipChildren;
        final boolean clipToPadding;
        final boolean fitsSystemWindows;
        final float elevation;
        final float translationZ;

        FullScreenLayoutState(View view,
                              ViewGroup.LayoutParams layoutParams,
                              boolean clipChildren,
                              boolean clipToPadding) {
            this.view = view;
            this.layoutParams = layoutParams;
            this.paddingLeft = view.getPaddingLeft();
            this.paddingTop = view.getPaddingTop();
            this.paddingRight = view.getPaddingRight();
            this.paddingBottom = view.getPaddingBottom();
            this.clipChildren = clipChildren;
            this.clipToPadding = clipToPadding;
            this.fitsSystemWindows = view.getFitsSystemWindows();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                this.elevation = view.getElevation();
                this.translationZ = view.getTranslationZ();
            } else {
                this.elevation = 0f;
                this.translationZ = 0f;
            }
        }
    }

    /**
     * 监听系统中音频焦点改变，见{@link #setEnableAudioFocus(boolean)}
     */
    protected boolean mEnableAudioFocus;
    @Nullable
    protected AudioFocusHelper mAudioFocusHelper;

    /**
     * OnStateChangeListener集合，保存了所有开发者设置的监听器
     */
    protected List<OnStateChangeListener> mOnStateChangeListeners;

    /**
     * 进度管理器，设置之后播放器会记录播放进度，以便下次播放恢复进度
     */
    @Nullable
    protected ProgressManager mProgressManager;

    /**
     * 循环播放
     */
    protected boolean mIsLooping;

    /**
     * {@link #mPlayerContainer}背景色，默认黑色
     */
    private int mPlayerBackgroundColor;

    public VideoView(@NonNull Context context) {
        this(context, null);
    }

    public VideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        //读取全局配置
        VideoViewConfig config = VideoViewManager.getConfig();
        mEnableAudioFocus = config.mEnableAudioFocus;
        mProgressManager = config.mProgressManager;
        mPlayerFactory = config.mPlayerFactory;
        mCurrentScreenScaleType = config.mScreenScaleType;
        mRenderViewFactory = config.mRenderViewFactory;

        //读取xml中的配置，并综合全局配置
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.VideoView);
        mEnableAudioFocus = a.getBoolean(R.styleable.VideoView_enableAudioFocus, mEnableAudioFocus);
        mIsLooping = a.getBoolean(R.styleable.VideoView_looping, false);
        mCurrentScreenScaleType = a.getInt(R.styleable.VideoView_screenScaleType, mCurrentScreenScaleType);
        mPlayerBackgroundColor = a.getColor(R.styleable.VideoView_playerBackgroundColor, Color.BLACK);
        a.recycle();

        initView();
    }

    /**
     * 初始化播放器视图
     */
    protected void initView() {
        mPlayerContainer = new FrameLayout(getContext());
        mPlayerContainer.setBackgroundColor(mPlayerBackgroundColor);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        this.addView(mPlayerContainer, params);
    }

    /**
     * 设置{@link #mPlayerContainer}的背景色
     */
    public void setPlayerBackgroundColor(int color) {
        mPlayerContainer.setBackgroundColor(color);
    }

    /**
     * 开始播放，注意：调用此方法后必须调用{@link #release()}释放播放器，否则会导致内存泄漏
     */
    @Override
    public void start() {
        if (isInIdleState()
                || isInStartAbortState()) {
            startPlay();
        } else if (isInPlaybackState()) {
            startInPlaybackState();
        }
    }

    /**
     * 第一次播放
     *
     * @return 是否成功开始播放
     */
    protected boolean startPlay() {
        //如果要显示移动网络提示则不继续播放
        if (showNetWarning()) {
            //中止播放
            setPlayState(STATE_START_ABORT);
            return false;
        }
        //监听音频焦点改变
        if (mEnableAudioFocus) {
            mAudioFocusHelper = new AudioFocusHelper(this);
        }
        //读取播放进度
        mCurrentPosition = 0L;
        if (mProgressManager != null && mResumePosition <= 0L) {
            mResumePosition = Math.max(0L,
                    mProgressManager.getSavedProgress(mProgressKey == null ? mUrl : mProgressKey));
        }
        mPendingResumeSeekAfterRender = false;
        mResumeSeekAppliedAfterRender = false;
        initPlayer();
        addDisplay();
        startPrepare(false);
        return true;
    }

    /**
     * 是否显示移动网络提示，可在Controller中配置
     */
    protected boolean showNetWarning() {
        //播放本地数据源时不检测网络
        if (isLocalDataSource()) return false;
        return mVideoController != null && mVideoController.showNetWarning();
    }

    /**
     * 判断是否为本地数据源，包括 本地文件、Asset、raw
     */
    protected boolean isLocalDataSource() {
        if (mAssetFileDescriptor != null) {
            return true;
        } else if (!TextUtils.isEmpty(mUrl)) {
            Uri uri = Uri.parse(mUrl);
            return ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())
                    || ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                    || "rawresource".equals(uri.getScheme());
        }
        return false;
    }

    /**
     * 初始化播放器
     */
    protected void initPlayer() {
        mMediaPlayer = mPlayerFactory.createPlayer(getContext());
        mMediaPlayer.setPlayerEventListener(this);
        setInitOptions();
        mMediaPlayer.initPlayer();
        setOptions();
    }

    /**
     * 初始化之前的配置项
     */
    protected void setInitOptions() {
    }

    /**
     * 初始化之后的配置项
     */
    protected void setOptions() {
        mMediaPlayer.setLooping(mIsLooping);
        mMediaPlayer.setVolume(1.0f, 1.0f);
    }

    /**
     * 初始化视频渲染View
     */
    protected void addDisplay() {
        if (mRenderView != null) {
            mPlayerContainer.removeView(mRenderView.getView());
            mRenderView.release();
        }
        mRenderView = mRenderViewFactory.createRenderView(getContext());
        mRenderView.attachToPlayer(mMediaPlayer);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        mPlayerContainer.addView(mRenderView.getView(), 0, params);
    }

    /**
     * 开始准备播放（直接播放）
     */
    protected void startPrepare(boolean reset) {
        if (reset) {
            mMediaPlayer.reset();
            //重新设置option，media player reset之后，option会失效
            setOptions();
        }
        if (prepareDataSource()) {
            mMediaPlayer.prepareAsync();
            setPlayState(STATE_PREPARING);
            setPlayerState(isFullScreen() ? PLAYER_FULL_SCREEN : isTinyScreen() ? PLAYER_TINY_SCREEN : PLAYER_NORMAL);
        }
    }

    /**
     * 设置播放数据
     *
     * @return 播放数据是否设置成功
     */
    protected boolean prepareDataSource() {
        if (mAssetFileDescriptor != null) {
            mMediaPlayer.setDataSource(mAssetFileDescriptor);
            return mMediaPlayer.hasValidDataSource();
        } else if (!TextUtils.isEmpty(mUrl)) {
            mMediaPlayer.setDataSource(mUrl, mHeaders);
            return mMediaPlayer.hasValidDataSource();
        }
        return false;
    }

    /**
     * 播放状态下开始播放
     */
    protected void startInPlaybackState() {
        mMediaPlayer.start();
        setPlayState(STATE_PLAYING);
        if (mAudioFocusHelper != null) {
            mAudioFocusHelper.requestFocus();
        }
        mPlayerContainer.setKeepScreenOn(true);
    }

    /**
     * 暂停播放
     */
    @Override
    public void pause() {
        if (isInPlaybackState()
                && mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            setPlayState(STATE_PAUSED);
            if (mAudioFocusHelper != null) {
                mAudioFocusHelper.abandonFocus();
            }
            mPlayerContainer.setKeepScreenOn(false);
        }
    }

    /**
     * 继续播放
     */
    public void resume() {
        if (isInPlaybackState() && !mMediaPlayer.isPlaying()) {
            assert mRenderView != null;
            View renderView = mRenderView.getView();
            if (renderView instanceof SurfaceView) {
                final SurfaceView surfaceView = (SurfaceView) renderView;
                final SurfaceHolder holder = surfaceView.getHolder();
                if (holder.getSurface() != null && holder.getSurface().isValid()) {
                    mMediaPlayer.setDisplay(holder);
                    resumePlay();
                } else {
                    holder.addCallback(new SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(SurfaceHolder holder) {
                            addDisplay();
                            if (mRenderView != null) {
                                mRenderView.setScaleType(mCurrentScreenScaleType);
                                mRenderView.setVideoSize(mVideoSize[0], mVideoSize[1]);
                            }
                            mMediaPlayer.setDisplay(holder);
                            resumePlay();
                            // 移除回调，避免重复调用
                            holder.removeCallback(this);
                        }

                        @Override
                        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                        }

                        @Override
                        public void surfaceDestroyed(SurfaceHolder holder) {
                        }
                    });
                }
            } else {
                resumePlay();
            }
            if (mRenderView != null) {
                // 强制请求布局（解决部分设备渲染问题）
                mRenderView.getView().requestLayout();
                mRenderView.getView().invalidate();
            }
            if (mRenderView != null && mRenderView.getView() != null) {
                // 统一设置视图可见性
                mRenderView.getView().setVisibility(View.VISIBLE);
            }
        }
    }

    private void resumePlay(){
        mMediaPlayer.start();
        setPlayState(STATE_PLAYING);
        if (mAudioFocusHelper != null) {
            mAudioFocusHelper.requestFocus();
        }
        mPlayerContainer.setKeepScreenOn(true);
    }
    /**
     * 释放播放器
     */
    public void release() {
        if (mIsFullScreen) {
            stopFullScreen();
        }
        if (!isInIdleState()) {
            //释放播放器
            if (mMediaPlayer != null) {
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            //释放renderView
            if (mRenderView != null) {
                mPlayerContainer.removeView(mRenderView.getView());
                mRenderView.release();
                mRenderView = null;
            }
            //释放Assets资源
            if (mAssetFileDescriptor != null) {
                try {
                    mAssetFileDescriptor.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            //关闭AudioFocus监听
            if (mAudioFocusHelper != null) {
                mAudioFocusHelper.abandonFocus();
                mAudioFocusHelper = null;
            }
            //关闭屏幕常亮
            mPlayerContainer.setKeepScreenOn(false);
            //保存播放进度
            saveProgress();
            //重置播放进度
            mCurrentPosition = 0;
            //切换转态
            setPlayState(STATE_IDLE);
        }
    }

    /**
     * 保存播放进度
     */
    protected void saveProgress() {
        long persistablePosition = resolvePersistableProgressPosition();
        if (persistablePosition >= 0L) {
            saveProgress(persistablePosition);
        }
    }

    protected void saveProgress(long persistablePosition) {
        if (mProgressManager != null && persistablePosition >= 0L) {
            L.d("saveProgress: " + persistablePosition);
            mProgressManager.saveProgress(mProgressKey == null ? mUrl : mProgressKey, persistablePosition);
        }
    }

    /**
     * Resolve a progress value that is safe to persist as a resume point.
     * Positions near the end are treated as completed playback and cleared.
     */
    protected long resolvePersistableProgressPosition() {
        if (mCurrentPlayState == STATE_PLAYBACK_COMPLETED) {
            return 0L;
        }
        if (mCurrentPlayState == STATE_START_ABORT
                || mCurrentPlayState == STATE_IDLE
                || mCurrentPlayState == STATE_ERROR
                || mCurrentPlayState == STATE_PREPARING
                || mCurrentPlayState == STATE_BUFFERING) {
            return PERSIST_PROGRESS_SKIP;
        }
        long position = Math.max(0L, mCurrentPosition);
        if (position <= 0L) {
            return PERSIST_PROGRESS_SKIP;
        }
        if (mMediaPlayer instanceof AndroidMediaPlayer) {
            AndroidMediaPlayer player = (AndroidMediaPlayer) mMediaPlayer;
            if (player.isPositionQueryUnstable() || player.isSeekInFlight()) {
                return PERSIST_PROGRESS_SKIP;
            }
        }
        long duration = resolvePersistableDuration();
        if (duration > 0L) {
            long finishThreshold = Math.max(0L, duration - PERSIST_END_TOLERANCE_MS);
            if (position >= finishThreshold) {
                return 0L;
            }
        }
        return position;
    }

    protected long resolvePersistableDuration() {
        if (mMediaPlayer == null) {
            return mLastKnownDuration;
        }
        try {
            long duration = mMediaPlayer.getDuration();
            if (duration > 0L) {
                mLastKnownDuration = duration;
                return duration;
            }
        } catch (Throwable ignored) {
        }
        return mLastKnownDuration;
    }

    /**
     * 是否处于播放状态
     */
    protected boolean isInPlaybackState() {
        return mMediaPlayer != null
                && mCurrentPlayState != STATE_ERROR
                && mCurrentPlayState != STATE_IDLE
                && mCurrentPlayState != STATE_PREPARING
                && mCurrentPlayState != STATE_START_ABORT
                && mCurrentPlayState != STATE_PLAYBACK_COMPLETED;
    }

    /**
     * 是否处于未播放状态
     */
    protected boolean isInIdleState() {
        return mCurrentPlayState == STATE_IDLE;
    }

    /**
     * 播放中止状态
     */
    private boolean isInStartAbortState() {
        return mCurrentPlayState == STATE_START_ABORT;
    }

    /**
     * 重新播放
     *
     * @param resetPosition 是否从头开始播放
     */
    @Override
    public void replay(boolean resetPosition) {
        long resumePosition = resetPosition ? 0L
                : Math.max(0L, mCurrentPosition > 0L ? mCurrentPosition : mResumePosition);
        mCurrentPosition = 0L;
        mResumePosition = resumePosition;
        mPendingResumeSeekAfterRender = false;
        mResumeSeekAppliedAfterRender = false;
        addDisplay();
        startPrepare(true);
    }

    /**
     * 获取视频总时长
     */
    @Override
    public long getDuration() {
        if (isInPlaybackState()) {
            try {
                long duration = mMediaPlayer.getDuration();
                if (duration > 0L) {
                    mLastKnownDuration = duration;
                    return duration;
                }
            } catch (Throwable ignored) {
                return mLastKnownDuration;
            }
        }
        return mLastKnownDuration;
    }

    /**
     * 获取当前播放的位置
     */
    @Override
    public long getCurrentPosition() {
        if (isInPlaybackState()) {
            try {
                if (mMediaPlayer instanceof AndroidMediaPlayer
                        && ((AndroidMediaPlayer) mMediaPlayer).isPositionQueryUnstable()) {
                    return Math.max(0L, mCurrentPosition);
                }
                long position = mMediaPlayer.getCurrentPosition();
                if (position > 0L) {
                    mCurrentPosition = position;
                }
                return mCurrentPosition;
            } catch (Throwable ignored) {
                return Math.max(0L, mCurrentPosition);
            }
        }
        return 0;
    }

    /**
     * 调整播放进度
     */
    @Override
    public void seekTo(long pos) {
        if (isInPlaybackState()) {
            mMediaPlayer.seekTo(pos);
        }
    }

    /**
     * 是否处于播放状态
     */
    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mMediaPlayer.isPlaying();
    }

    /**
     * 获取当前缓冲百分比
     */
    @Override
    public int getBufferedPercentage() {
        return mMediaPlayer != null ? mMediaPlayer.getBufferedPercentage() : 0;
    }

    /**
     * 设置静音
     */
    @Override
    public void setMute(boolean isMute) {
        this.mIsMute = false;
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(1.0f, 1.0f);
        }
    }

    /**
     * 是否处于静音状态
     */
    @Override
    public boolean isMute() {
        return false;
    }

    /**
     * 视频缓冲完毕，准备开始播放时回调
     */
    @Override
    public void onPrepared() {
        setPlayState(STATE_PREPARED);
        resolvePersistableDuration();
        long resumePosition = sanitizeResumePosition(mResumePosition);
        if (resumePosition != mResumePosition && resumePosition == 0L) {
            saveProgress(0L);
        }
        mResumePosition = resumePosition;
        if (mAudioFocusHelper != null) {
            mAudioFocusHelper.requestFocus();
        }
        if (resumePosition > 0L) {
            if (shouldDelayInitialSeekUntilRenderingStart()) {
                mPendingResumeSeekAfterRender = true;
                mResumeSeekAppliedAfterRender = false;
            } else {
                seekTo(resumePosition);
            }
        }
    }

    /**
     * 播放信息回调，播放中的缓冲开始与结束，开始渲染视频第一帧，视频旋转信息
     */
    @Override
    public void onInfo(int what, int extra) {
        switch (what) {
            case AbstractPlayer.MEDIA_INFO_BUFFERING_START:
                setPlayState(STATE_BUFFERING);
                break;
            case AbstractPlayer.MEDIA_INFO_BUFFERING_END:
                setPlayState(STATE_BUFFERED);
                break;
            case AbstractPlayer.MEDIA_INFO_RENDERING_START: // 视频/音频开始渲染
                setPlayState(STATE_PLAYING);
                mPlayerContainer.setKeepScreenOn(true);
                applyDeferredResumeSeekAfterRender();
                break;
            case AbstractPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED:
                if (mRenderView != null) mRenderView.setVideoRotation(extra);
                break;
        }
    }

    /**
     * 视频播放出错回调
     */
    @Override
    public void onError() {
        mPlayerContainer.setKeepScreenOn(false);
        setPlayState(STATE_ERROR);
    }

    /**
     * 视频播放完成回调
     */
    @Override
    public void onCompletion() {
        if (mCurrentPlayState == STATE_IDLE
                || mCurrentPlayState == STATE_START_ABORT
                || mMediaPlayer == null) {
            L.d("ignore late completion state=" + mCurrentPlayState);
            return;
        }
        mPlayerContainer.setKeepScreenOn(false);
        if (!shouldTreatCompletionAsPlaybackFinished()) {
            L.d("ignore abnormal completion position=" + mCurrentPosition);
            return;
        }
        mCurrentPosition = 0;
        mResumePosition = 0L;
        saveProgress(0L);
        setPlayState(STATE_PLAYBACK_COMPLETED);
    }

    private boolean shouldTreatCompletionAsPlaybackFinished() {
        long duration = resolveCompletionDurationForGuard();
        if (duration <= 0L) {
            return false;
        }
        long position = resolveCompletionPositionForGuard();
        long finishThreshold = Math.max(0L, duration - COMPLETION_END_TOLERANCE_MS);
        return position >= finishThreshold;
    }

    private long resolveCompletionPositionForGuard() {
        if (mCurrentPosition > 0L) {
            return mCurrentPosition;
        }
        if (mMediaPlayer == null) {
            return 0L;
        }
        try {
            long position = mMediaPlayer.getCurrentPosition();
            if (position > 0L) {
                mCurrentPosition = position;
            }
            return position;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private long resolveCompletionDurationForGuard() {
        if (mMediaPlayer == null) {
            return mLastKnownDuration;
        }
        try {
            long duration = mMediaPlayer.getDuration();
            if (duration > 0L) {
                mLastKnownDuration = duration;
                return duration;
            }
        } catch (Throwable ignored) {
        }
        return mLastKnownDuration;
    }

    /**
     * 获取当前播放器的状态
     */
    public int getCurrentPlayerState() {
        return mCurrentPlayerState;
    }

    /**
     * 获取当前的播放状态
     */
    public int getCurrentPlayState() {
        return mCurrentPlayState;
    }

    /**
     * 获取缓冲速度
     */
    @Override
    public long getTcpSpeed() {
        return mMediaPlayer != null ? mMediaPlayer.getTcpSpeed() : 0;
    }

    /**
     * 设置播放速度
     */
    @Override
    public void setSpeed(float speed) {
        if (isInPlaybackState()) {
            mMediaPlayer.setSpeed(speed);
        }
    }

    @Override
    public float getSpeed() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getSpeed();
        }
        return 1f;
    }

    /**
     * 设置视频地址
     */
    public void setUrl(String url) {
        setUrl(url, null);
    }

    /**
     * 设置包含请求头信息的视频地址
     *
     * @param url     视频地址
     * @param headers 请求头
     */
    public void setUrl(String url, Map<String, String> headers) {
        mAssetFileDescriptor = null;
        mUrl = url;
        mHeaders = headers;
        mCurrentPosition = 0L;
        mResumePosition = 0L;
        mLastKnownDuration = 0L;
    }

    /**
     * 用于播放assets里面的视频文件
     */
    public void setAssetFileDescriptor(AssetFileDescriptor fd) {
        mUrl = null;
        this.mAssetFileDescriptor = fd;
        mCurrentPosition = 0L;
        mResumePosition = 0L;
        mLastKnownDuration = 0L;
    }

    public void setProgressKey(String key) {
        mProgressKey = key;
    }

    /**
     * 一开始播放就seek到预先设置好的位置
     */
    public void skipPositionWhenPlay(int position) {
        mCurrentPosition = 0L;
        mResumePosition = Math.max(0L, position);
    }

    /**
     * 设置音量 0.0f-1.0f 之间
     *
     * @param v1 左声道音量
     * @param v2 右声道音量
     */
    public void setVolume(float v1, float v2) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(1.0f, 1.0f);
        }
    }

    /**
     * 设置进度管理器，用于保存播放进度
     */
    public void setProgressManager(@Nullable ProgressManager progressManager) {
        this.mProgressManager = progressManager;
    }

    private boolean shouldDelayInitialSeekUntilRenderingStart() {
        return mMediaPlayer instanceof AndroidMediaPlayer
                && ((AndroidMediaPlayer) mMediaPlayer).shouldDelayResumeSeekUntilRenderingStart();
    }

    private void applyDeferredResumeSeekAfterRender() {
        if (!mPendingResumeSeekAfterRender
                || mResumeSeekAppliedAfterRender
                || mResumePosition <= 0L
                || mMediaPlayer == null) {
            return;
        }
        final long target = mResumePosition;
        mPendingResumeSeekAfterRender = false;
        mResumeSeekAppliedAfterRender = true;
        post(() -> {
            if (mMediaPlayer == null || mCurrentPlayState == STATE_ERROR) {
                return;
            }
            seekTo(target);
        });
    }

    private long sanitizeResumePosition(long position) {
        long sanitized = Math.max(0L, position);
        if (sanitized <= 0L) {
            return 0L;
        }
        long duration = resolvePersistableDuration();
        if (duration <= 0L) {
            return sanitized;
        }
        long finishThreshold = Math.max(0L, duration - PERSIST_END_TOLERANCE_MS);
        if (sanitized > duration || sanitized >= finishThreshold) {
            L.d("clear stale resume position=" + sanitized + " duration=" + duration);
            return 0L;
        }
        return sanitized;
    }

    /**
     * 循环播放， 默认不循环播放
     */
    public void setLooping(boolean looping) {
        mIsLooping = looping;
        if (mMediaPlayer != null) {
            mMediaPlayer.setLooping(looping);
        }
    }

    /**
     * 是否开启AudioFocus监听， 默认开启，用于监听其它地方是否获取音频焦点，如果有其它地方获取了
     * 音频焦点，此播放器将做出相应反应，具体实现见{@link AudioFocusHelper}
     */
    public void setEnableAudioFocus(boolean enableAudioFocus) {
        mEnableAudioFocus = enableAudioFocus;
    }

    /**
     * 自定义播放核心，继承{@link PlayerFactory}实现自己的播放核心
     */
    public void setPlayerFactory(PlayerFactory<P> playerFactory) {
        if (playerFactory == null) {
            throw new IllegalArgumentException("PlayerFactory can not be null!");
        }
        mPlayerFactory = playerFactory;
    }

    /**
     * 自定义RenderView，继承{@link RenderViewFactory}实现自己的RenderView
     */
    public void setRenderViewFactory(RenderViewFactory renderViewFactory) {
        if (renderViewFactory == null) {
            throw new IllegalArgumentException("RenderViewFactory can not be null!");
        }
        mRenderViewFactory = renderViewFactory;
    }

    /**
     * 进入全屏
     */
    @Override
    public void startFullScreen() {
        if (mIsFullScreen) {
            return;
        }

        ViewGroup contentView = getContentView();
        ViewGroup decorView = getDecorView();
        ViewParent currentParent = getParent();
        if (contentView == null || decorView == null || !(currentParent instanceof ViewGroup)) {
            return;
        }

        mNormalParentBeforeFullScreen = (ViewGroup) currentParent;
        mNormalLayoutParamsBeforeFullScreen = getLayoutParams();
        mNormalIndexBeforeFullScreen = mNormalParentBeforeFullScreen.indexOfChild(this);
        mIsFullScreen = true;
        mIsFullScreenViewMoving = true;

        //隐藏NavigationBar和StatusBar
        hideSysBar(decorView);

        applyInPlaceFullScreenLayout(contentView);
        mPlayerContainer.setFitsSystemWindows(false);
        setScreenScaleType(SCREEN_SCALE_DEFAULT);
        requestLayout();
        invalidate();
        mPlayerContainer.requestLayout();
        if (mRenderView != null && mRenderView.getView() != null) {
            mRenderView.setScaleType(mCurrentScreenScaleType);
            mRenderView.getView().requestLayout();
            mRenderView.getView().invalidate();
        }
        refreshRenderSurfaceAfterLayout("fullscreen-enter");
        postDelayed(new Runnable() {
            @Override
            public void run() {
                mIsFullScreenViewMoving = false;
                Log.i(TAG, "echo-video-fullscreen inplace-chain=true player=" + getPlayerDebugName()
                        + " size=" + getWidth() + "x" + getHeight()
                        + " surface=" + (mRenderView == null || mRenderView.getView() == null ? "null"
                        : mRenderView.getView().getWidth() + "x" + mRenderView.getView().getHeight())
                        + " screen=" + PlayerUtils.getScreenWidth(getContext(), false)
                        + "x" + PlayerUtils.getScreenHeight(getContext(), false));
            }
        }, FULLSCREEN_SURFACE_MOVE_GUARD_MS);

        mInPlaceFullScreen = true;
        setPlayerState(PLAYER_FULL_SCREEN);
    }

    protected boolean shouldUseInPlaceFullScreen() {
        return true;
    }

    private String getPlayerDebugName() {
        return mMediaPlayer == null ? "null" : mMediaPlayer.getClass().getSimpleName();
    }

    private void hideSysBar(ViewGroup decorView) {
        int uiOptions = decorView.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decorView.setSystemUiVisibility(uiOptions);
        Activity activity = getActivity();
        if (activity != null) {
            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
                activity.getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                activity.getWindow().setBackgroundDrawable(null);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.getWindow().setDecorFitsSystemWindows(false);
                if (activity.getWindow().getInsetsController() != null) {
                    activity.getWindow().getInsetsController().hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    activity.getWindow().getInsetsController().setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        }
    }

    private ViewGroup.LayoutParams createMatchParentLayoutParams(Object parent) {
        if (parent instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && mIsFullScreen) {
            //重新获得焦点时保持全屏状态
            hideSysBar(getDecorView());
        }
    }

    /**
     * 退出全屏
     */
    @Override
    public void stopFullScreen() {
        if (!mIsFullScreen) {
            return;
        }

        ViewGroup decorView = getDecorView();
        if (decorView == null) {
            return;
        }

        mIsFullScreen = false;
        mIsFullScreenViewMoving = true;

        // App 全局是沉浸式电视 UI，退出播放器全屏时不能恢复系统栏，否则会触发布局 inset 变化、
        // 顶部灰条残留和播放页错位。保持沉浸式，只恢复播放器自身布局。
        hideSysBar(decorView);

        if (mInPlaceFullScreen) {
            restoreInPlaceFullScreenLayout();
            setFitsSystemWindows(false);
            requestLayout();
            invalidate();
            if (mRenderView != null && mRenderView.getView() != null) {
                mRenderView.getView().requestLayout();
                mRenderView.getView().invalidate();
            }
            refreshRenderSurfaceAfterLayout("fullscreen-exit");
            mInPlaceFullScreen = false;
            mNormalLayoutParamsBeforeFullScreen = null;
            mNormalParentBeforeFullScreen = null;
            mNormalIndexBeforeFullScreen = -1;
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    mIsFullScreenViewMoving = false;
                    Log.i(TAG, "echo-video-fullscreen-exit inplace-chain=true player=" + getPlayerDebugName()
                            + " size=" + getWidth() + "x" + getHeight());
                }
            }, FULLSCREEN_SURFACE_MOVE_GUARD_MS);
            setPlayerState(PLAYER_NORMAL);
            return;
        }

        mIsFullScreenViewMoving = false;
        setPlayerState(PLAYER_NORMAL);
    }

    @Override
    public boolean isFullScreenViewMoving() {
        return mIsFullScreenViewMoving;
    }

    private void applyInPlaceFullScreenLayout(ViewGroup contentView) {
        mFullScreenLayoutStates.clear();
        View current = this;
        while (current != null) {
            ViewParent parent = current.getParent();
            boolean isGroup = current instanceof ViewGroup;
            mFullScreenLayoutStates.add(new FullScreenLayoutState(
                    current,
                    copyLayoutParams(current.getLayoutParams()),
                    isGroup && ((ViewGroup) current).getClipChildren(),
                    isGroup && ((ViewGroup) current).getClipToPadding()));

            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                group.setClipChildren(false);
                group.setClipToPadding(false);
            }
            current.setPadding(0, 0, 0, 0);
            current.setFitsSystemWindows(false);
            current.setLayoutParams(createFullScreenLayoutParams(current.getLayoutParams(), parent));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                current.setElevation(Math.max(current.getElevation(), 10000f));
                current.setTranslationZ(Math.max(current.getTranslationZ(), 10000f));
            } else {
                current.bringToFront();
            }
            current.requestLayout();
            current.invalidate();

            if (current == contentView || !(parent instanceof View)) {
                break;
            }
            current = (View) parent;
        }
    }

    private void restoreInPlaceFullScreenLayout() {
        for (int i = mFullScreenLayoutStates.size() - 1; i >= 0; i--) {
            FullScreenLayoutState state = mFullScreenLayoutStates.get(i);
            state.view.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom);
            state.view.setFitsSystemWindows(state.fitsSystemWindows);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                state.view.setElevation(state.elevation);
                state.view.setTranslationZ(state.translationZ);
            }
            if (state.view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) state.view;
                group.setClipChildren(state.clipChildren);
                group.setClipToPadding(state.clipToPadding);
            }
            if (state.layoutParams != null) {
                state.view.setLayoutParams(state.layoutParams);
            }
            state.view.requestLayout();
            state.view.invalidate();
        }
        mFullScreenLayoutStates.clear();
    }

    private ViewGroup.LayoutParams createFullScreenLayoutParams(ViewGroup.LayoutParams currentParams, ViewParent parent) {
        if (currentParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) currentParams);
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = 0;
            params.topMargin = 0;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            return params;
        }
        if (currentParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) currentParams);
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.weight = 0f;
            params.leftMargin = 0;
            params.topMargin = 0;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            return params;
        }
        if (currentParams instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) currentParams);
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.leftMargin = 0;
            params.topMargin = 0;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                params.setMarginStart(0);
                params.setMarginEnd(0);
            }
            return params;
        }
        if (currentParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) currentParams);
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.leftMargin = 0;
            params.topMargin = 0;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            return params;
        }
        if (parent instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP | Gravity.START);
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source == null) {
            return null;
        }
        if (source instanceof FrameLayout.LayoutParams) {
            return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) source);
        }
        if (source instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) source);
        }
        if (source instanceof RelativeLayout.LayoutParams) {
            return new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) source);
        }
        if (source instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
        }
        return new ViewGroup.LayoutParams(source);
    }

    protected void refreshRenderSurfaceAfterLayout(final String reason) {
        if (mRenderView == null || mRenderView.getView() == null) {
            return;
        }
        mRenderView.getView().requestLayout();
        mRenderView.getView().invalidate();
        mRenderView.getView().post(new Runnable() {
            @Override
            public void run() {
                if (mRenderView != null && mRenderView.getView() != null) {
                    Log.i(TAG, "echo-video-refresh-surface reason=" + reason
                            + " player=" + getPlayerDebugName()
                            + " size=" + mRenderView.getView().getWidth()
                            + "x" + mRenderView.getView().getHeight());
                    mRenderView.refreshSurface();
                }
            }
        });
    }

    private void showSysBar(ViewGroup decorView) {
        hideSysBar(decorView);
    }

    /**
     * 获取DecorView
     */
    protected ViewGroup getDecorView() {
        Activity activity = getActivity();
        if (activity == null) return null;
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    /**
     * 获取activity中的content view,其id为android.R.id.content
     */
    protected ViewGroup getContentView() {
        Activity activity = getActivity();
        if (activity == null) return null;
        return activity.findViewById(android.R.id.content);
    }

    /**
     * 获取Activity，优先通过Controller去获取Activity
     */
    protected Activity getActivity() {
        Activity activity;
        if (mVideoController != null) {
            activity = PlayerUtils.scanForActivity(mVideoController.getContext());
            if (activity == null) {
                activity = PlayerUtils.scanForActivity(getContext());
            }
        } else {
            activity = PlayerUtils.scanForActivity(getContext());
        }
        return activity;
    }

    /**
     * 判断是否处于全屏状态
     */
    @Override
    public boolean isFullScreen() {
        return mIsFullScreen;
    }

    /**
     * 开启小屏
     */
    public void startTinyScreen() {
        if (mIsTinyScreen) return;
        ViewGroup contentView = getContentView();
        if (contentView == null) return;
        this.removeView(mPlayerContainer);
        int width = mTinyScreenSize[0];
        if (width <= 0) {
            width = PlayerUtils.getScreenWidth(getContext(), false) / 2;
        }

        int height = mTinyScreenSize[1];
        if (height <= 0) {
            height = width * 9 / 16;
        }

        LayoutParams params = new LayoutParams(width, height);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        contentView.addView(mPlayerContainer, params);
        mIsTinyScreen = true;
        setPlayerState(PLAYER_TINY_SCREEN);
    }

    /**
     * 退出小屏
     */
    public void stopTinyScreen() {
        if (!mIsTinyScreen) return;

        ViewGroup contentView = getContentView();
        if (contentView == null) return;
        contentView.removeView(mPlayerContainer);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        this.addView(mPlayerContainer, params);

        mIsTinyScreen = false;
        setPlayerState(PLAYER_NORMAL);
    }

    public boolean isTinyScreen() {
        return mIsTinyScreen;
    }

    @Override
    public void onVideoSizeChanged(int videoWidth, int videoHeight) {
        mVideoSize[0] = videoWidth;
        mVideoSize[1] = videoHeight;

        if (mRenderView != null) {
            mRenderView.setScaleType(mCurrentScreenScaleType);
            mRenderView.setVideoSize(videoWidth, videoHeight);
        }
    }

    /**
     * 设置控制器，传null表示移除控制器
     */
    public void setVideoController(@Nullable BaseVideoController mediaController) {
        mPlayerContainer.removeView(mVideoController);
        mVideoController = mediaController;
        if (mediaController != null) {
            mediaController.setMediaPlayer(this);
            LayoutParams params = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            mPlayerContainer.addView(mVideoController, params);
        }
    }

    /**
     * 设置视频比例
     */
    @Override
    public void setScreenScaleType(int screenScaleType) {
        mCurrentScreenScaleType = screenScaleType;
        if (mRenderView != null) {
            mRenderView.setScaleType(screenScaleType);
        }
    }

    /**
     * 设置镜像旋转，暂不支持SurfaceView
     */
    @Override
    public void setMirrorRotation(boolean enable) {
        if (mRenderView != null) {
            mRenderView.getView().setScaleX(enable ? -1 : 1);
        }
    }

    /**
     * 截图，暂不支持SurfaceView
     */
    @Override
    public Bitmap doScreenShot() {
        if (mRenderView != null) {
            return mRenderView.doScreenShot();
        }
        return null;
    }

    /**
     * 获取视频宽高,其中width: mVideoSize[0], height: mVideoSize[1]
     */
    @Override
    public int[] getVideoSize() {
        return mVideoSize;
    }

    /**
     * 旋转视频画面
     *
     * @param rotation 角度
     */
    @Override
    public void setRotation(float rotation) {
        if (mRenderView != null) {
            mRenderView.setVideoRotation((int) rotation);
        }
    }

    /**
     * 设置小屏的宽高
     *
     * @param tinyScreenSize 其中tinyScreenSize[0]是宽，tinyScreenSize[1]是高
     */
    public void setTinyScreenSize(int[] tinyScreenSize) {
        this.mTinyScreenSize = tinyScreenSize;
    }

    /**
     * 向Controller设置播放状态，用于控制Controller的ui展示
     */
    protected void setPlayState(int playState) {
        mCurrentPlayState = playState;
        if (mVideoController != null) {
            mVideoController.setPlayState(playState);
        }
        if (mOnStateChangeListeners != null) {
            for (OnStateChangeListener l : PlayerUtils.getSnapshot(mOnStateChangeListeners)) {
                if (l != null) {
                    l.onPlayStateChanged(playState);
                }
            }
        }
    }

    /**
     * 向Controller设置播放器状态，包含全屏状态和非全屏状态
     */
    protected void setPlayerState(int playerState) {
        mCurrentPlayerState = playerState;
        if (mVideoController != null) {
            mVideoController.setPlayerState(playerState);
        }
        if (mOnStateChangeListeners != null) {
            for (OnStateChangeListener l : PlayerUtils.getSnapshot(mOnStateChangeListeners)) {
                if (l != null) {
                    l.onPlayerStateChanged(playerState);
                }
            }
        }
    }

    /**
     * 播放状态改变监听器
     */
    public interface OnStateChangeListener {
        void onPlayerStateChanged(int playerState);

        void onPlayStateChanged(int playState);
    }

    /**
     * OnStateChangeListener的空实现。用的时候只需要重写需要的方法
     */
    public static class SimpleOnStateChangeListener implements OnStateChangeListener {
        @Override
        public void onPlayerStateChanged(int playerState) {
        }

        @Override
        public void onPlayStateChanged(int playState) {
        }
    }

    /**
     * 添加一个播放状态监听器，播放状态发生变化时将会调用。
     */
    public void addOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners == null) {
            mOnStateChangeListeners = new ArrayList<>();
        }
        mOnStateChangeListeners.add(listener);
    }

    /**
     * 移除某个播放状态监听
     */
    public void removeOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners != null) {
            mOnStateChangeListeners.remove(listener);
        }
    }

    /**
     * 设置一个播放状态监听器，播放状态发生变化时将会调用，
     * 如果你想同时设置多个监听器，推荐 {@link #addOnStateChangeListener(OnStateChangeListener)}。
     */
    public void setOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners == null) {
            mOnStateChangeListeners = new ArrayList<>();
        } else {
            mOnStateChangeListeners.clear();
        }
        mOnStateChangeListeners.add(listener);
    }

    /**
     * 移除所有播放状态监听
     */
    public void clearOnStateChangeListeners() {
        if (mOnStateChangeListeners != null) {
            mOnStateChangeListeners.clear();
        }
    }

    /**
     * 改变返回键逻辑，用于activity
     */
    public boolean onBackPressed() {
        return mVideoController != null && mVideoController.onBackPressed();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        L.d("onSaveInstanceState: " + mCurrentPosition);
        //activity切到后台后可能被系统回收，故在此处进行进度保存
        saveProgress();
        return super.onSaveInstanceState();
    }
}
