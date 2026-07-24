package com.github.tvbox.osc.player.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.player.SystemCodecPlayer;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.MeasureHelper;

public class SurfaceRenderView extends SurfaceView implements IRenderView, SurfaceHolder.Callback {
    private static final String TAG = "SurfaceRenderView";
    private MeasureHelper mMeasureHelper;

    private AbstractPlayer mMediaPlayer;
    @Nullable
    private SurfaceListener mSurfaceListener;

    public SurfaceRenderView(Context context) {
        super(context);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SurfaceRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        mMeasureHelper = new MeasureHelper();
        SurfaceHolder surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);
        // Keep the video surface opaque so TV firmware can promote it to the hardware video plane.
        setZOrderOnTop(false);
        setZOrderMediaOverlay(false);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        this.mMediaPlayer = player;
        refreshSurface();
        notifySurfaceAvailableIfReady("attach");
    }

    @Override
    public void refreshSurface() {
        if (mMediaPlayer == null) {
            return;
        }
        SurfaceHolder holder = getHolder();
        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
            mMediaPlayer.setDisplay(holder);
        }
    }

    @Override
    public void setSurfaceListener(@Nullable SurfaceListener listener) {
        mSurfaceListener = listener;
        notifySurfaceAvailableIfReady("listener");
    }

    @Override
    public boolean requiresValidSurfaceBeforePrepare() {
        return true;
    }

    @Override
    public boolean hasValidSurface() {
        try {
            SurfaceHolder holder = getHolder();
            return holder != null
                    && holder.getSurface() != null
                    && holder.getSurface().isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            requestLayout();
        }
    }

    @Override
    public void setVideoRotation(int degree) {
        mMeasureHelper.setVideoRotation(degree);
        setRotation(degree);
    }

    @Override
    public void setScaleType(int scaleType) {
        mMeasureHelper.setScreenScale(scaleType);
        requestLayout();
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public Bitmap doScreenShot() {
        return null;
    }

    @Override
    public void release() {
        mSurfaceListener = null;
        mMediaPlayer = null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
        notifySurfaceAvailableIfReady("created");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // ExoPlayer already receives size changes from its SurfaceHolder callback. Rebinding
        // here makes affected vendor codecs try MediaCodec.setOutputSurface() unnecessarily.
        notifySurfaceAvailableIfReady("changed");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        SurfaceListener listener = mSurfaceListener;
        if (listener != null) {
            listener.onSurfaceDestroyed(this);
        }
        if (mMediaPlayer != null) {
            // A fullscreen move briefly destroys the old Surface after the new one has
            // already been attached. Do not let that stale callback detach the live decoder.
            if (isParentVideoViewMovingFullScreen()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isAttachedToWindow()) {
                return;
            }
            if (mMediaPlayer instanceof SystemCodecPlayer) {
                ((SystemCodecPlayer) mMediaPlayer).detachDisplay(holder);
            } else {
                mMediaPlayer.setDisplay(null);
            }
        }
    }

    private boolean isParentVideoViewMovingFullScreen() {
        ViewParent parent = getParent();
        while (parent != null) {
            if (parent instanceof VideoView) {
                return ((VideoView) parent).isFullScreenViewMoving();
            }
            parent = parent.getParent();
        }
        return false;
    }

    private void notifySurfaceAvailableIfReady(String reason) {
        SurfaceListener listener = mSurfaceListener;
        if (listener == null || !hasValidSurface()) {
            return;
        }
        Log.i(TAG, "echo-surface-ready reason=" + reason
                + " w=" + getWidth()
                + " h=" + getHeight());
        listener.onSurfaceAvailable(this);
    }
}
