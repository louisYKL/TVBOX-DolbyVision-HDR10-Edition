package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Requests the platform HDR color mode for the playback window.
 *
 * Display brightness is deliberately never changed here. HDR tone mapping and
 * panel brightness belong to the device/TV firmware, and changing either
 * WindowManager.LayoutParams.screenBrightness or Settings.System would fight
 * the user's display settings and break HDR phones with adaptive brightness.
 */
public final class HdrOutputManager {
    private static final Map<Activity, Long> REQUEST_GENERATIONS = new WeakHashMap<>();
    private static long nextGeneration;

    private HdrOutputManager() {
    }

    public static boolean requestHdr(Context context, String reason) {
        return requestHdr(context, reason, false);
    }

    /** Legacy compatibility entry point; brightness is owned by the device. */
    public static boolean requestBrightnessBoostOnly(Context context, String reason,
                                                      boolean boostBrightness) {
        LOG.i("echo-hdr-window brightness-only-ignored reason=" + reason
                + " requested=" + boostBrightness);
        return false;
    }

    /**
     * Enables ActivityInfo.COLOR_MODE_HDR. The legacy brightness argument is
     * retained for source compatibility but is never applied.
     */
    public static boolean requestHdr(Context context, String reason, boolean ignoredBrightness) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LOG.i("echo-hdr-window skip sdk=" + Build.VERSION.SDK_INT + " reason=" + reason);
            return false;
        }
        Activity activity = findActivity(context);
        if (activity == null) {
            LOG.i("echo-hdr-window skip no-activity reason=" + reason);
            return false;
        }
        long generation = beginRequest(activity);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> applyHdrRequest(activity, reason + "-main",
                    ignoredBrightness, generation));
            // The actual WindowManager result is only available on the UI thread. Do not
            // report success to a background caller before that result is confirmed; callers
            // currently use this as a fire-and-forget request and the attached retry below
            // records the definitive outcome.
            LOG.i("echo-hdr-window posted-main reason=" + reason + " confirmed=pending");
            return false;
        }
        return applyHdrRequest(activity, reason, ignoredBrightness, generation);
    }

    private static boolean applyHdrRequest(Activity activity, String reason,
                                           boolean ignoredBrightness, long generation) {
        if (!isCurrentRequest(activity, generation)) {
            LOG.i("echo-hdr-window stale-request reason=" + reason);
            return false;
        }
        Window window = activity.getWindow();
        if (window == null) {
            LOG.i("echo-hdr-window skip no-window reason=" + reason);
            return false;
        }
        boolean applied = applyHdrColorMode(window, activity, reason, ignoredBrightness);
        // Some Android phone window managers discard a color-mode change made before the
        // decor view is attached. Re-apply once after attachment; this is idempotent and
        // avoids waiting until the first decoded frame to enter HDR.
        View decor = window.getDecorView();
        if (decor != null) {
            boolean queued = decor.post(() -> {
                if (isCurrentRequest(activity, generation)) {
                    applyHdrColorMode(window, activity, reason + "-attached", ignoredBrightness);
                } else {
                    LOG.i("echo-hdr-window stale-attached-request reason=" + reason);
                }
            });
            if (!queued) {
                LOG.i("echo-hdr-window attached-retry-not-queued reason=" + reason);
            }
        }
        return applied;
    }

    private static boolean applyHdrColorMode(Window window, Activity activity, String reason,
                                             boolean ignoredBrightness) {
        try {
            HdrDeviceSupport.Capabilities capabilities = activity == null
                    ? null : HdrDeviceSupport.query(activity);
            WindowManager.LayoutParams attrs = window.getAttributes();
            int previousColorMode = attrs.getColorMode();
            // Use both APIs. setColorMode is the public API on API 26+ and is honored by
            // vendors that do not apply a mutated LayoutParams instance until a later relayout.
            boolean directApiApplied = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    window.setColorMode(ActivityInfo.COLOR_MODE_HDR);
                    directApiApplied = true;
                } catch (Throwable directFailure) {
                    LOG.i("echo-hdr-window direct-api-failed reason=" + reason
                            + " err=" + directFailure.getClass().getSimpleName());
                }
            }
            boolean layoutParamsApplied = false;
            try {
                attrs.setColorMode(ActivityInfo.COLOR_MODE_HDR);
                window.setAttributes(attrs);
                layoutParamsApplied = true;
            } catch (Throwable attrsFailure) {
                LOG.i("echo-hdr-window layout-params-failed reason=" + reason
                        + " err=" + attrsFailure.getClass().getSimpleName());
            }
            int appliedColorMode = window.getAttributes().getColorMode();
            boolean confirmed = appliedColorMode == ActivityInfo.COLOR_MODE_HDR;
            LOG.i("echo-hdr-window requested reason=" + reason
                    + " previousColorMode=" + previousColorMode
                    + " colorMode=" + appliedColorMode
                    + " confirmed=" + confirmed
                    + " directApi=" + directApiApplied
                    + " layoutParams=" + layoutParamsApplied
                    + " brightness=system-controlled"
                    + " boostIgnored=" + ignoredBrightness
                    + " hdrColorMode=" + ActivityInfo.COLOR_MODE_HDR
                    + " caps=" + (capabilities == null ? "unknown" : capabilities.summary));
            if (!confirmed) {
                LOG.i("echo-hdr-window not-confirmed reason=" + reason
                        + " displaySupportsHdr="
                        + (capabilities != null && capabilities.displaySupportsHdr()));
            }
            return confirmed;
        } catch (Throwable th) {
            LOG.e("echo-hdr-window failed reason=" + reason + " err=" + th.getMessage());
            return false;
        }
    }

    public static void releaseHdr(Context context, String reason) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        Activity activity = findActivity(context);
        if (activity == null) {
            return;
        }
        long generation = beginRequest(activity);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> applyDefaultColorMode(activity, reason + "-main", generation));
            LOG.i("echo-hdr-window release-posted-main reason=" + reason);
            return;
        }
        applyDefaultColorMode(activity, reason, generation);
    }

    private static void applyDefaultColorMode(Activity activity, String reason, long generation) {
        if (!isCurrentRequest(activity, generation)) {
            LOG.i("echo-hdr-window stale-release reason=" + reason);
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        try {
            WindowManager.LayoutParams attrs = window.getAttributes();
            int previousColorMode = attrs.getColorMode();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.setColorMode(ActivityInfo.COLOR_MODE_DEFAULT);
            }
            attrs.setColorMode(ActivityInfo.COLOR_MODE_DEFAULT);
            if (previousColorMode != attrs.getColorMode()) {
                window.setAttributes(attrs);
            }
            LOG.i("echo-hdr-window released reason=" + reason
                    + " colorMode=" + window.getAttributes().getColorMode()
                    + " brightness=system-controlled");
        } catch (Throwable th) {
            LOG.e("echo-hdr-window release-failed reason=" + reason + " err=" + th.getMessage());
        }
    }

    private static synchronized long beginRequest(Activity activity) {
        long generation = ++nextGeneration;
        REQUEST_GENERATIONS.put(activity, generation);
        return generation;
    }

    private static synchronized boolean isCurrentRequest(Activity activity, long generation) {
        Long current = REQUEST_GENERATIONS.get(activity);
        return current != null && current == generation;
    }

    public static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
