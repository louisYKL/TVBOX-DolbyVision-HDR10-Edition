package xyz.doikki.videoplayer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xyz.doikki.videoplayer.player.AbstractPlayer;

public class SurfaceRenderView extends SurfaceView implements IRenderView, SurfaceHolder.Callback {
    private final MeasureHelper mMeasureHelper = new MeasureHelper();
    @Nullable
    private AbstractPlayer mMediaPlayer;
    @Nullable
    private SurfaceListener mSurfaceListener;

    public SurfaceRenderView(Context context) {
        super(context);
        init();
    }

    public SurfaceRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SurfaceRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        setZOrderOnTop(false);
        setZOrderMediaOverlay(false);
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        mMediaPlayer = player;
        refreshSurface();
        notifySurfaceAvailableIfReady();
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
        notifySurfaceAvailableIfReady();
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
        notifySurfaceAvailableIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
        notifySurfaceAvailableIfReady();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        SurfaceListener listener = mSurfaceListener;
        if (listener != null) {
            listener.onSurfaceDestroyed(this);
        }
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(null);
        }
    }

    private void notifySurfaceAvailableIfReady() {
        SurfaceListener listener = mSurfaceListener;
        if (listener != null && hasValidSurface()) {
            listener.onSurfaceAvailable(this);
        }
    }
}
