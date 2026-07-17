package xyz.doikki.videoplayer.player;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;

/**
 * 音频焦点改变监听
 */
final class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private WeakReference<VideoView> mWeakVideoView;

    private AudioManager mAudioManager;

    private boolean mStartRequested = false;
    private boolean mPausedForLoss = false;
    private volatile int mCurrentFocus = 0;
    AudioFocusHelper(@NonNull VideoView videoView) {
        mWeakVideoView = new WeakReference<>(videoView);
        mAudioManager = (AudioManager) videoView.getContext().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onAudioFocusChange(final int focusChange) {
        if (mCurrentFocus == focusChange) {
            return;
        }

        //由于onAudioFocusChange有可能在子线程调用，
        //故通过此方式切换到主线程去执行
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                handleAudioFocusChange(focusChange);
            }
        });

        mCurrentFocus = focusChange;
    }

    private void handleAudioFocusChange(int focusChange) {
        final VideoView videoView = mWeakVideoView.get();
        if (videoView == null) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN://获得焦点
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT://暂时获得焦点
                if (mStartRequested || mPausedForLoss) {
                    videoView.start();
                    mStartRequested = false;
                    mPausedForLoss = false;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS://焦点丢失
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT://焦点暂时丢失
                if (videoView.isPlaying()) {
                    mPausedForLoss = true;
                    videoView.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK://此时需降低音量
                // 软件层永远不参与调音量。这里保持原播放状态，把音量控制交给系统/音响。
                break;
        }
    }

    /**
     * Requests to obtain the audio focus
     */
    void requestFocus() {
        if (mCurrentFocus == AudioManager.AUDIOFOCUS_GAIN) {
            return;
        }

        if (mAudioManager == null) {
            return;
        }

        int status = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == status) {
            mCurrentFocus = AudioManager.AUDIOFOCUS_GAIN;
            mStartRequested = false;
            mPausedForLoss = false;
            return;
        }

        mStartRequested = true;
    }

    /**
     * Requests the system to drop the audio focus
     */
    void abandonFocus() {
        mStartRequested = false;
        if (mAudioManager != null) {
            mAudioManager.abandonAudioFocus(this);
        }
        // abandonAudioFocus() does not necessarily deliver a callback. Clear the cached
        // grant so an explicit resume always requests focus again instead of playing silently.
        mCurrentFocus = 0;
    }
}
