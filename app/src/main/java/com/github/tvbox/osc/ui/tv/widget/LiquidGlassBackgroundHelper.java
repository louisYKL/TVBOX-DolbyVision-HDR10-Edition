package com.github.tvbox.osc.ui.tv.widget;

import android.content.res.Resources;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import androidx.annotation.ColorInt;

import com.github.tvbox.osc.R;

final class LiquidGlassBackgroundHelper {
    private final RectF rect = new RectF();
    private final RectF highlightRect = new RectF();
    private final RectF causticRect = new RectF();
    private final RectF rippleRect = new RectF();
    private final RectF innerRect = new RectF();
    private final RectF glowRect = new RectF();
    private final Path path = new Path();
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint causticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint movingHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float radius;
    private final float innerInset;
    private final float highlightHeight;
    private final float glowInset;
    LiquidGlassBackgroundHelper(Resources resources, float radius) {
        this.radius = radius;
        innerInset = resources.getDimension(R.dimen.vs_2);
        highlightHeight = resources.getDimension(R.dimen.vs_22);
        glowInset = resources.getDimension(R.dimen.vs_6);

        strokePaint.setStyle(Paint.Style.STROKE);
        innerStrokePaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setMaskFilter(new BlurMaskFilter(resources.getDimension(R.dimen.vs_10), BlurMaskFilter.Blur.NORMAL));
        ripplePaint.setStyle(Paint.Style.FILL);
    }

    void draw(Canvas canvas, int width, int height, boolean focused, float animationProgress,
              @ColorInt int baseTop, @ColorInt int baseBottom,
              @ColorInt int accentTop, @ColorInt int accentBottom,
              @ColorInt int stroke, @ColorInt int innerStroke,
              @ColorInt int glow) {
        if (width <= 0 || height <= 0) {
            return;
        }
        rect.set(0f, 0f, width, height);
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        float progress = Math.max(0f, Math.min(1f, animationProgress));
        float phase = focused ? (0.16f + progress * 0.64f) : 0.26f;
        float sway = focused ? (float) Math.sin(progress * Math.PI) : 0f;
        int dynamicBoost = focused ? Math.round(58f * progress) : 0;

        int save = canvas.save();
        canvas.clipPath(path);

        basePaint.setShader(new LinearGradient(
                0f, 0f, 0f, height,
                focused ? accentTop : baseTop,
                focused ? accentBottom : baseBottom,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, basePaint);

        highlightRect.set(rect.left, rect.top, rect.right,
                rect.top + Math.min(height * 0.58f, highlightHeight * 3.4f));
        highlightPaint.setShader(new LinearGradient(
                0f, 0f, width * 0.94f, highlightRect.bottom,
                Color.argb((focused ? 44 : 36) + dynamicBoost / 2, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(highlightRect, radius, radius, highlightPaint);

        float movingCenterX = width * (0.08f + phase * 0.92f);
        float movingCenterY = height * (focused ? (0.24f + progress * 0.1f) : 0.2f);
        movingHighlightPaint.setShader(new RadialGradient(
                movingCenterX,
                movingCenterY,
                Math.max(width, height) * (focused ? (0.44f + progress * 0.22f) : 0.5f),
                new int[]{
                        Color.argb((focused ? 54 : 40) + dynamicBoost, 255, 255, 255),
                        Color.argb((focused ? 24 : 16) + dynamicBoost / 2, 214, 236, 255),
                        Color.argb(0, 255, 255, 255)
                },
                new float[]{0f, 0.38f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, movingHighlightPaint);

        causticRect.set(rect.left + glowInset, rect.top + height * 0.18f,
                rect.right - glowInset, rect.bottom - height * 0.26f);
        causticPaint.setShader(new LinearGradient(
                causticRect.left, causticRect.top,
                causticRect.right, causticRect.bottom,
                Color.argb((focused ? 18 : 12) + dynamicBoost / 3, 214, 237, 255),
                Color.argb((focused ? 6 : 4) + dynamicBoost / 8, 255, 255, 255),
                Shader.TileMode.CLAMP));
        canvas.drawOval(causticRect, causticPaint);

        rippleRect.set(
                rect.left + width * (0.08f + (focused ? progress * 0.12f : 0f)),
                rect.top + height * (0.44f - sway * 0.03f),
                rect.right - width * (0.14f - (focused ? progress * 0.06f : 0f)),
                rect.bottom - height * 0.08f);
        ripplePaint.setShader(new LinearGradient(
                rippleRect.left, rippleRect.top,
                rippleRect.right, rippleRect.bottom,
                new int[]{
                        Color.argb((focused ? 18 : 12) + dynamicBoost / 3, 255, 255, 255),
                        Color.argb((focused ? 28 : 18) + dynamicBoost / 2, 166, 210, 255),
                        Color.argb(0, 255, 255, 255)
                },
                new float[]{0f, 0.46f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawOval(rippleRect, ripplePaint);

        if (focused) {
            glowPaint.setColor(Color.argb(22 + dynamicBoost / 2, Color.red(glow), Color.green(glow), Color.blue(glow)));
            glowRect.set(rect.left + innerInset, rect.top + innerInset,
                    rect.right - innerInset, rect.bottom - innerInset);
            canvas.drawRoundRect(glowRect, radius - innerInset, radius - innerInset, glowPaint);
        }

        strokePaint.setColor(stroke);
        strokePaint.setStrokeWidth(Math.max(1f, innerInset * 0.82f));
        canvas.drawRoundRect(new RectF(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f), radius, radius, strokePaint);

        innerStrokePaint.setColor(innerStroke);
        innerStrokePaint.setStrokeWidth(Math.max(1f, innerInset * 0.68f));
        innerRect.set(rect.left + innerInset, rect.top + innerInset,
                rect.right - innerInset, rect.bottom - innerInset);
        canvas.drawRoundRect(innerRect, Math.max(0f, radius - innerInset), Math.max(0f, radius - innerInset), innerStrokePaint);

        canvas.restoreToCount(save);
    }
}
