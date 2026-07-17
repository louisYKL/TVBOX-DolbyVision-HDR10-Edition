package com.github.tvbox.osc.util;

import android.animation.TimeInterpolator;
import android.os.Build;
import android.view.View;

import android.view.animation.PathInterpolator;
import android.view.animation.DecelerateInterpolator;

public class FocusAnim {
    private static final TimeInterpolator INTERPOLATOR =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    ? new PathInterpolator(0.22f, 1f, 0.36f, 1f)
                    : new DecelerateInterpolator(1.35f);
    private static final TimeInterpolator RELEASE_INTERPOLATOR =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    ? new PathInterpolator(0.18f, 0.84f, 0.22f, 1f)
                    : new DecelerateInterpolator(1.15f);

    public static void apply(View view, boolean focused, float scale) {
        if (view == null) {
            return;
        }
        float requestedScale = Math.min(scale, 1.018f);
        float targetScale = focused ? Math.max(1.01f, requestedScale) : 1f;
        float targetAlpha = focused ? 1f : 0.992f;
        float targetTranslationY = focused ? -2.6f : 0f;
        float targetTranslationX = 0f;
        ViewPropertyAnimatorCompat.animate(view, focused, targetScale, targetAlpha, targetTranslationX, targetTranslationY);
    }

    private static final class ViewPropertyAnimatorCompat {
        private ViewPropertyAnimatorCompat() {
        }

        private static void animate(View view, boolean focused, float targetScale, float targetAlpha, float targetTranslationX, float targetTranslationY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                float targetTranslationZ = focused ? 20f : 0f;
                view.animate()
                        .cancel();
                view.animate()
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .alpha(targetAlpha)
                        .translationX(targetTranslationX)
                        .translationY(targetTranslationY)
                        .translationZ(targetTranslationZ)
                        .setDuration(focused ? 360 : 300)
                        .setInterpolator(focused ? INTERPOLATOR : RELEASE_INTERPOLATOR)
                        .start();
            } else {
                view.animate()
                        .cancel();
                view.animate()
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .alpha(targetAlpha)
                        .translationX(targetTranslationX)
                        .translationY(targetTranslationY)
                        .setDuration(focused ? 360 : 300)
                        .setInterpolator(focused ? INTERPOLATOR : RELEASE_INTERPOLATOR)
                        .start();
            }
        }
    }
}
