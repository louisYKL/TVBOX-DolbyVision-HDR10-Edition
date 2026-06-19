package com.github.tvbox.osc.util;

import android.app.Activity;
import android.util.DisplayMetrics;

import com.orhanobut.hawk.Hawk;

public class SubtitleHelper {

    public static int getSubtitleTextAutoSize(Activity activity) {
        if (activity == null) {
            return 28;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        } catch (Throwable th) {
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        }
        int width = Math.max(metrics.widthPixels, metrics.heightPixels);
        int height = Math.min(metrics.widthPixels, metrics.heightPixels);
        float scaledDensity = metrics.scaledDensity <= 0f ? 1f : metrics.scaledDensity;
        double diagonalInches = estimateDiagonalInches(activity, metrics, width, height);
        boolean tvLike = diagonalInches >= 18.0 || ScreenUtils.isTv(activity);
        boolean tabletLike = !tvLike && diagonalInches >= 7.0;
        float targetPxRatio = tvLike ? 0.052f : tabletLike ? 0.040f : 0.040f;
        int targetSp = Math.round((height * targetPxRatio) / scaledDensity);
        int minSp = tvLike ? 42 : tabletLike ? 24 : 16;
        int maxSp = tvLike ? 72 : tabletLike ? 40 : 28;
        return clamp(targetSp, minSp, maxSp);
    }

    public static int getTextSize(Activity activity) {
        int autoSize = getSubtitleTextAutoSize(activity);
        int subtitleConfigSize = Hawk.get(HawkConfig.SUBTITLE_TEXT_SIZE, autoSize);
        if (ScreenUtils.isTv(activity) && subtitleConfigSize < autoSize - 8) {
            subtitleConfigSize = autoSize;
            Hawk.put(HawkConfig.SUBTITLE_TEXT_SIZE, subtitleConfigSize);
        }
        return normalizeTextSize(activity, subtitleConfigSize);
    }

    public static void setTextSize(int size) {
        Hawk.put(HawkConfig.SUBTITLE_TEXT_SIZE, size);
    }

    public static int normalizeTextSize(Activity activity, int size) {
        int autoSize = getSubtitleTextAutoSize(activity);
        boolean tvLike = activity != null && ScreenUtils.isTv(activity);
        int min = tvLike ? Math.max(28, autoSize - 8) : Math.max(14, autoSize - 10);
        int max = tvLike ? autoSize + 18 : autoSize + 12;
        return clamp(size, min, max);
    }

    public static int getTimeDelay() {
        int subtitleConfigTimeDelay = Hawk.get(HawkConfig.SUBTITLE_TIME_DELAY, 0);
        return subtitleConfigTimeDelay;
    }

    public static void setTimeDelay(int delay) {
        Hawk.put(HawkConfig.SUBTITLE_TIME_DELAY, delay);
    }

    private static double estimateDiagonalInches(Activity activity, DisplayMetrics metrics, int width, int height) {
        float xdpi = metrics.xdpi;
        float ydpi = metrics.ydpi;
        if (xdpi >= 80f && xdpi <= 640f && ydpi >= 80f && ydpi <= 640f) {
            double w = width / xdpi;
            double h = height / ydpi;
            double diagonal = Math.sqrt(w * w + h * h);
            if (diagonal >= 3.0 && diagonal <= 120.0) {
                return diagonal;
            }
        }
        return ScreenUtils.getSqrt(activity);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

}
