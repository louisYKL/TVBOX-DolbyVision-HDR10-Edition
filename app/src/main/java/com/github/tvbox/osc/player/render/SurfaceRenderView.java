package com.github.tvbox.osc.player.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.player.CompatTrackSelectorPlayer;
import com.github.tvbox.osc.player.MPVCompatPlayer;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.MeasureHelper;

public class SurfaceRenderView extends SurfaceView implements IRenderView, SurfaceHolder.Callback {
    private MeasureHelper mMeasureHelper;

    private AbstractPlayer mMediaPlayer;

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
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mMediaPlayer != null) {
            if (mMediaPlayer instanceof MPVCompatPlayer || mMediaPlayer instanceof CompatTrackSelectorPlayer) {
                if (isParentVideoViewMovingFullScreen()) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isAttachedToWindow()) {
                    return;
                }
                mMediaPlayer.setDisplay(null);
                return;
            }
            mMediaPlayer.setDisplay(null);
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
}
