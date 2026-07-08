package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;

import java.util.Map;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.controller.IGestureComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

public abstract class BaseController extends BaseVideoController implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, View.OnTouchListener {
    private GestureDetector mGestureDetector;
    private AudioManager mAudioManager;
    private boolean mIsGestureEnabled = true;
    private int mStreamVolume;
    private float mBrightness;
    private int mSeekPosition;
    private boolean mFirstTouch;
    private boolean mChangePosition;
    private boolean mChangeBrightness;
    private boolean mChangeVolume;
    private boolean mCanChangePosition = true;
    private boolean mEnableInNormal;
    private boolean mCanSlide;
    private int mCurPlayState;
    private boolean mHasRenderedFirstFrame;

    protected Handler mHandler;

    protected HandlerCallback mHandlerCallback;

    protected interface HandlerCallback {
        void callback(Message msg);
    }

    private boolean mIsDoubleTapTogglePlayEnabled = false;


    public BaseController(@NonNull Context context) {
        super(context);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                int what = msg.what;
                switch (what) {
                    case 100: { // 亮度+音量调整
                        if (mSlideInfo != null) {
                            mSlideInfo.setVisibility(VISIBLE);
                            mSlideInfo.setText(msg.obj == null ? "" : msg.obj.toString());
                        }
                        break;
                    }

                    case 101: { // 亮度+音量调整 关闭
                        if (mSlideInfo != null) {
                            mSlideInfo.setVisibility(GONE);
                        }
                        break;
                    }
                    default: {
                        if (mHandlerCallback != null)
                            mHandlerCallback.callback(msg);
                        break;
                    }
                }
                return false;
            }
        });
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private TextView mSlideInfo;
    private ProgressBar mLoading;
    private ViewGroup mPauseRoot;
    private TextView mPauseTime;

    @Override
    protected void initView() {
        super.initView();
        mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mGestureDetector = new GestureDetector(getContext(), this);
        mGestureDetector.setOnDoubleTapListener(this);
        setOnTouchListener(this);
        mSlideInfo = findViewWithTag("vod_control_slide_info");
        if (mSlideInfo == null) {
            mSlideInfo = findViewById(R.id.tv_slide_progress_text);
        }
        mLoading = findViewWithTag("vod_control_loading");
        mPauseRoot = findViewWithTag("vod_control_pause");
        mPauseTime = findViewWithTag("vod_control_pause_t");
    }

    @Override
    protected void setProgress(int duration, int position) {
        super.setProgress(duration, position);
        if (mPauseTime != null) {
            mPauseTime.setText(PlayerUtils.stringForTime(position) + " / " + PlayerUtils.stringForTime(duration));
        }
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        switch (playState) {
            case VideoView.STATE_IDLE:
                mHasRenderedFirstFrame = false;
                if (mLoading != null) {
                    mLoading.setVisibility(GONE);
                }
                break;
            case VideoView.STATE_PLAYING:
                mHasRenderedFirstFrame = true;
                if (mPauseRoot != null) {
                    mPauseRoot.setVisibility(GONE);
                }
                if (mLoading != null) {
                    mLoading.setVisibility(GONE);
                }
                break;
            case VideoView.STATE_PAUSED:
                if (mPauseRoot != null) {
                    mPauseRoot.setVisibility(VISIBLE);
                }
                if (mLoading != null) {
                    mLoading.setVisibility(GONE);
                }
                break;
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_ERROR:
            case VideoView.STATE_BUFFERED:
                if (mLoading != null) {
                    mLoading.setVisibility(GONE);
                }
                break;
            case VideoView.STATE_PREPARING:
                if (mLoading != null) {
                    mLoading.setVisibility(VISIBLE);
                }
                break;
            case VideoView.STATE_BUFFERING:
                if (mLoading != null) {
                    mLoading.setVisibility(mHasRenderedFirstFrame ? GONE : VISIBLE);
                }
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                if (mLoading != null) {
                    mLoading.setVisibility(GONE);
                }
                if (mPauseRoot != null) {
                    mPauseRoot.setVisibility(GONE);
                }
                break;
        }
    }

    /**
     * 设置是否可以滑动调节进度，默认可以
     */
    public void setCanChangePosition(boolean canChangePosition) {
        mCanChangePosition = canChangePosition;
    }

    /**
     * 是否在竖屏模式下开始手势控制，默认关闭
     */
    public void setEnableInNormal(boolean enableInNormal) {
        mEnableInNormal = enableInNormal;
    }

    /**
     * 是否开启手势控制，默认开启，关闭之后，手势调节进度，音量，亮度功能将关闭
     */
    public void setGestureEnabled(boolean gestureEnabled) {
        mIsGestureEnabled = gestureEnabled;
    }

    /**
     * 是否开启双击播放/暂停，默认开启
     */
    public void setDoubleTapTogglePlayEnabled(boolean enabled) {
        mIsDoubleTapTogglePlayEnabled = enabled;
    }

    @Override
    public void setPlayerState(int playerState) {
        super.setPlayerState(playerState);
        if (playerState == VideoView.PLAYER_NORMAL) {
            mCanSlide = mEnableInNormal;
        } else if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            mCanSlide = true;
        }
    }

    @Override
    public void setPlayState(int playState) {
        super.setPlayState(playState);
        mCurPlayState = playState;
    }

    protected boolean isInPlaybackState() {
        return mControlWrapper != null
                && mCurPlayState != VideoView.STATE_ERROR
                && mCurPlayState != VideoView.STATE_IDLE
                && mCurPlayState != VideoView.STATE_PREPARING
                && mCurPlayState != VideoView.STATE_START_ABORT
                && mCurPlayState != VideoView.STATE_PLAYBACK_COMPLETED;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return mGestureDetector.onTouchEvent(event);
    }

    /**
     * 手指按下的瞬间
     */
    @Override
    public boolean onDown(MotionEvent e) {
        if (!isInPlaybackState() //不处于播放状态
                || !mIsGestureEnabled //关闭了手势
                || (!shouldIgnoreEdgeRestrictionForTouch(e) && PlayerUtils.isEdge(getContext(), e))) //处于屏幕边沿
            return true;
        if (mAudioManager == null) {
            mChangePosition = false;
            mChangeBrightness = false;
            mChangeVolume = false;
            return true;
        }
        mStreamVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) {
            mBrightness = 0;
        } else {
            mBrightness = activity.getWindow().getAttributes().screenBrightness;
        }
        mFirstTouch = true;
        mChangePosition = false;
        mChangeBrightness = false;
        mChangeVolume = false;
        return true;
    }

    /**
     * 单击
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        if (isInPlaybackState()) {
            mControlWrapper.toggleShowState();
        }
        return true;
    }

    /**
     * 双击
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (mIsDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()) togglePlay();
        return true;
    }

    /**
     * 在屏幕上滑动
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (!isInPlaybackState() //不处于播放状态
                || !mIsGestureEnabled //关闭了手势
                || !mCanSlide //关闭了滑动手势
                || isLocked() //锁住了屏幕
                || (!shouldIgnoreEdgeRestrictionForTouch(e1) && PlayerUtils.isEdge(getContext(), e1))) //处于屏幕边沿
            return true;
        float deltaX = e1.getX() - e2.getX();
        float deltaY = e1.getY() - e2.getY();
        if (mFirstTouch) {
            mChangePosition = Math.abs(distanceX) >= Math.abs(distanceY);
            if (!mChangePosition) {
                //半屏宽度
                int halfScreen = PlayerUtils.getScreenWidth(getContext(), true) / 2;
                if (e2.getX() > halfScreen) {
                    mChangeVolume = true;
                } else {
                    mChangeBrightness = true;
                }
            }

            if (mChangePosition) {
                //根据用户设置是否可以滑动调节进度来决定最终是否可以滑动调节进度
                mChangePosition = mCanChangePosition;
            }

            if (mChangePosition || mChangeBrightness || mChangeVolume) {
                for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
                    IControlComponent component = next.getKey();
                    if (component instanceof IGestureComponent) {
                        ((IGestureComponent) component).onStartSlide();
                    }
                }
            }
            mFirstTouch = false;
        }
        if (mChangePosition) {
            slideToChangePosition(deltaX);
        } else if (mChangeBrightness) {
            slideToChangeBrightness(deltaY);
        } else if (mChangeVolume) {
            slideToChangeVolume(deltaY);
        }
        return true;
    }

    protected void slideToChangePosition(float deltaX) {
        deltaX = -deltaX;
        int width = getMeasuredWidth();
        int duration = PlayerUtils.safeTimeMs(mControlWrapper.getDuration());
        int currentPosition = PlayerUtils.safeTimeMs(mControlWrapper.getCurrentPosition());
        int position = (int) (deltaX / width * 120000 + currentPosition);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onPositionChange(position, currentPosition, duration);
            }
        }
        updateSeekUI(currentPosition, position, duration);
        mSeekPosition = position;
    }

    protected void updateSeekUI(int curr, int seekTo, int duration) {

    }

    protected void slideToChangeBrightness(float deltaY) {
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) return;
        if (shouldUseDeviceBrightnessGesture()) {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            WindowManager.LayoutParams attributes = window.getAttributes();
            int height = Math.max(1, getMeasuredHeight());
            float currentBrightness = attributes.screenBrightness;
            if (currentBrightness <= 0f) {
                currentBrightness = mBrightness > 0f ? mBrightness : 0.5f;
            }
            float brightness = deltaY * 2f / height + currentBrightness;
            if (brightness < 0f) {
                brightness = 0f;
            }
            if (brightness > 1.0f) {
                brightness = 1.0f;
            }
            int percent = (int) (brightness * 100);
            try {
                attributes.screenBrightness = brightness;
                window.setAttributes(attributes);
            } catch (Throwable ignored) {
                return;
            }
            for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
                IControlComponent component = next.getKey();
                if (component instanceof IGestureComponent) {
                    ((IGestureComponent) component).onBrightnessChange(percent);
                }
            }
            Message msg = Message.obtain();
            msg.what = 100;
            msg.obj = "亮度" + percent + "%";
            mHandler.sendMessage(msg);
            mHandler.removeMessages(101);
            mHandler.sendEmptyMessageDelayed(101, 1000);
            return;
        }
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        int height = getMeasuredHeight();
        if (mBrightness == -1.0f) mBrightness = 0.5f;
        float brightness = deltaY * 2 / height * 1.0f + mBrightness;
        if (brightness < 0) {
            brightness = 0f;
        }
        if (brightness > 1.0f) brightness = 1.0f;
        int percent = (int) (brightness * 100);
        attributes.screenBrightness = brightness;
        window.setAttributes(attributes);
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onBrightnessChange(percent);
            }
        }
        Message msg = Message.obtain();
        msg.what = 100;
        msg.obj = "亮度" + percent + "%";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(101);
        mHandler.sendEmptyMessageDelayed(101, 1000);
    }

    protected void slideToChangeVolume(float deltaY) {
        int streamMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int percent = 100;
        if (shouldUseDeviceVolumeGesture()) {
            if (streamMaxVolume > 0) {
                int height = Math.max(1, getMeasuredHeight());
                int deltaV = (int) ((deltaY * 2f / height) * streamMaxVolume);
                int index = mStreamVolume + deltaV;
                if (index < 0) {
                    index = 0;
                }
                if (index > streamMaxVolume) {
                    index = streamMaxVolume;
                }
                try {
                    mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
                } catch (Throwable ignored) {
                }
                percent = Math.round(index * 100f / streamMaxVolume);
            }
        } else if (streamMaxVolume > 0) {
            try {
                int current = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (current < streamMaxVolume) {
                    mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamMaxVolume, 0);
                }
            } catch (Throwable ignored) {
            }
        }
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onVolumeChange(percent);
            }
        }
        Message msg = Message.obtain();
        msg.what = 100;
        msg.obj = shouldUseDeviceVolumeGesture() ? ("音量" + percent + "%") : "音频直通/系统音量";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(101);
        mHandler.sendEmptyMessageDelayed(101, 1000);
    }

    private boolean shouldUseDeviceVolumeGesture() {
        if (!App.isJava64Build()) {
            return false;
        }
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity instanceof BaseActivity) {
            return !((BaseActivity) activity).isTvDevice();
        }
        return false;
    }

    private boolean shouldIgnoreEdgeRestrictionForTouch(@Nullable MotionEvent event) {
        if (!App.isJava64Build() || event == null) {
            return false;
        }
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (!(activity instanceof BaseActivity) || ((BaseActivity) activity).isTvDevice()) {
            return false;
        }
        return mControlWrapper != null
                && mControlWrapper.isFullScreen()
                && event.getPointerCount() > 0
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
    }

    private boolean shouldUseDeviceBrightnessGesture() {
        if (!App.isJava64Build()) {
            return false;
        }
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity instanceof BaseActivity) {
            return !((BaseActivity) activity).isTvDevice();
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //滑动结束时事件处理
        if (!mGestureDetector.onTouchEvent(event)) {
            int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_UP:
                    stopSlide();
                    if (mSeekPosition > 0) {
                        int seekPosition = mSeekPosition;
                        mControlWrapper.seekTo(seekPosition);
                        mSeekPosition = 0;
                        onGestureSeekCompleted(seekPosition);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    stopSlide();
                    mSeekPosition = 0;
                    break;
            }
        }
        return super.onTouchEvent(event);
    }

    protected void onGestureSeekCompleted(int seekPosition) {
    }

    private void stopSlide() {
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onStopSlide();
            }
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }


    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    public boolean onKeyEvent(KeyEvent event) {
        return false;
    }
}
