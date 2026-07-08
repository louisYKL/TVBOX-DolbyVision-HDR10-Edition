package com.github.tvbox.osc.ui.tv.widget;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

public class LiquidGlassTextView extends AppCompatTextView {
    private static final long GLASS_FRAME_DELAY_MS = 16L;
    private static final long GLASS_PULSE_DURATION_MS = 320L;
    private final RectF outlineRect = new RectF();
    private final Path clipPath = new Path();
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private LiquidGlassBackgroundHelper glassHelper;
    private float cornerRadius;
    private int baseTopColor;
    private int baseBottomColor;
    private int accentTopColor;
    private int accentBottomColor;
    private int strokeColor;
    private int innerStrokeColor;
    private int glowColor;
    private int focusedTextColor;
    private int unfocusedTextColor;
    private long glassPulseStartMs = -1L;

    public LiquidGlassTextView(Context context) {
        this(context, null);
    }

    public LiquidGlassTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public LiquidGlassTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context.getResources());
    }

    private void init(Resources resources) {
        cornerRadius = resources.getDimension(R.dimen.vs_20);
        glassHelper = new LiquidGlassBackgroundHelper(resources, cornerRadius);
        baseTopColor = ContextCompat.getColor(getContext(), R.color.apple_tv_glass_base_top);
        baseBottomColor = ContextCompat.getColor(getContext(), R.color.apple_tv_glass_base_bottom);
        accentTopColor = ContextCompat.getColor(getContext(), R.color.apple_tv_glass_focus_top);
        accentBottomColor = ContextCompat.getColor(getContext(), R.color.apple_tv_glass_focus_bottom);
        strokeColor = ContextCompat.getColor(getContext(), R.color.apple_tv_glass_edge);
        innerStrokeColor = ContextCompat.getColor(getContext(), R.color.apple_tv_glass_inner_edge);
        glowColor = ContextCompat.getColor(getContext(), R.color.apple_tv_focus_glow);
        focusedTextColor = ContextCompat.getColor(getContext(), R.color.apple_tv_selected_text_dark);
        unfocusedTextColor = getCurrentTextColor();
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        getPaint().setAntiAlias(true);
        getPaint().setSubpixelText(true);
        getPaint().setDither(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
            setClipToOutline(true);
        }
    }

    public void setGlassRadius(float radius) {
        cornerRadius = radius;
        glassHelper = new LiquidGlassBackgroundHelper(getResources(), radius);
        invalidateOutline();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean focused = shouldUseFocusedGlass();
        float animationProgress = currentAnimationProgress(focused);
        outlineRect.set(0f, 0f, getWidth(), getHeight());
        LiquidGlassSnapshotManager.drawBackdrop(canvas, this, outlineRect, backdropPaint);
        glassHelper.draw(
                canvas,
                getWidth(),
                getHeight(),
                focused,
                animationProgress,
                baseTopColor,
                baseBottomColor,
                accentTopColor,
                accentBottomColor,
                strokeColor,
                innerStrokeColor,
                glowColor
        );
        int saveCount = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(outlineRect, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        super.onDraw(canvas);
        canvas.restoreToCount(saveCount);
        if (focused && animationProgress < 1f && isShown() && getAlpha() > 0f) {
            postInvalidateOnAnimationCompat();
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, @Nullable android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused && shouldUseFocusedGlass()) {
            glassPulseStartMs = SystemClock.uptimeMillis();
        } else {
            glassPulseStartMs = -1L;
        }
        applyFocusTextContrast();
        invalidate();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        applyFocusTextContrast();
    }

    private void postInvalidateOnAnimationCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            postInvalidateOnAnimation();
        } else {
            postInvalidateDelayed(GLASS_FRAME_DELAY_MS);
        }
    }

    private float currentAnimationProgress(boolean focused) {
        if (!focused || glassPulseStartMs < 0L) {
            return focused ? 1f : 0f;
        }
        float elapsed = (SystemClock.uptimeMillis() - glassPulseStartMs) / (float) GLASS_PULSE_DURATION_MS;
        return Math.max(0f, Math.min(1f, elapsed));
    }

    private boolean shouldUseFocusedGlass() {
        Object tag = getTag();
        if (tag instanceof String && ((String) tag).contains("static-glass")) {
            return false;
        }
        return isFocused() || isActivated();
    }

    private void applyFocusTextContrast() {
        Object tag = getTag();
        if (tag instanceof String && ((String) tag).contains("static-glass")) {
            setTextColor(unfocusedTextColor);
            return;
        }
        boolean focused = shouldUseFocusedGlass();
        int targetColor = focused ? focusedTextColor : unfocusedTextColor;
        if (Color.alpha(targetColor) == 0) {
            targetColor = focused ? ContextCompat.getColor(getContext(), R.color.apple_tv_selected_text_dark)
                    : ContextCompat.getColor(getContext(), R.color.apple_tv_text_primary);
        }
        setTextColor(targetColor);
    }
}
