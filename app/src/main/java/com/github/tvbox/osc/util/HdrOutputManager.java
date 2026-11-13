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
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
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
    private static final Map<Activity, HdrWindowObserver> WINDOW_OBSERVERS = new WeakHashMap<>();
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
        if (activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        Window window = activity.getWindow();
        if (window == null) {
            LOG.i("echo-hdr-window skip no-window reason=" + reason);
            return false;
        }
        boolean applied = applyHdrColorMode(window, activity, reason, ignoredBrightness);
        // A posted Runnable is not an attachment callback: it can execute while Decor is
        // detached, and it does not cover later reattachment or a dialog returning focus.
        // Observe those actual transitions without polling or repeatedly relaying out frames.
        View decor = window.getDecorView();
        if (decor != null) {
            clearWindowObserver(activity);
            HdrWindowObserver observer = new HdrWindowObserver(
                    activity, decor, generation, reason, ignoredBrightness);
            WINDOW_OBSERVERS.put(activity, observer);
            observer.start();
        }
        return applied;
    }

    private static boolean applyHdrColorMode(Window window, Activity activity, String reason,
                                             boolean ignoredBrightness) {
        try {
            HdrDeviceSupport.Capabilities capabilities = activity == null
                    ? null : HdrDeviceSupport.query(activity);
            int previousColorMode = window.getAttributes().getColorMode();
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
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs.getColorMode() != ActivityInfo.COLOR_MODE_HDR) {
                    attrs.setColorMode(ActivityInfo.COLOR_MODE_HDR);
                    window.setAttributes(attrs);
                }
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
                    + " requestAccepted=" + confirmed
                    + " actualDisplayOutput=codec-surface-controlled"
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
        clearWindowObserver(activity);
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

    private static void clearWindowObserver(Activity activity) {
        HdrWindowObserver observer = WINDOW_OBSERVERS.remove(activity);
        if (observer != null) {
            observer.stop();
        }
    }

    /** Weak references keep a finished Activity from being retained by pending View callbacks. */
    private static final class HdrWindowObserver implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<Activity> activity;
        private final WeakReference<View> decor;
        private final long generation;
        private final String reason;
        private final boolean ignoredBrightness;
        private WeakReference<ViewTreeObserver> focusObserver;

        HdrWindowObserver(Activity owner, View root, long generation, String reason,
                          boolean ignoredBrightness) {
            activity = new WeakReference<>(owner);
            decor = new WeakReference<>(root);
            this.generation = generation;
            this.reason = reason;
            this.ignoredBrightness = ignoredBrightness;
        }

        void start() {
            View root = decor.get();
            if (root == null) return;
            root.addOnAttachStateChangeListener(this);
            if (root.isAttachedToWindow()) {
                onViewAttachedToWindow(root);
            }
        }

        void stop() {
            View root = decor.get();
            if (root != null) root.removeOnAttachStateChangeListener(this);
            removeFocusObserver();
        }

        private void removeFocusObserver() {
            ViewTreeObserver observer = focusObserver == null ? null : focusObserver.get();
            if (observer != null && observer.isAlive()) {
                observer.removeOnWindowFocusChangeListener(this);
            }
            focusObserver = null;
        }

        private void reapply() {
            Activity owner = activity.get();
            View root = decor.get();
            if (owner == null || root == null || !root.isAttachedToWindow()
                    || owner.isFinishing() || owner.isDestroyed()
                    || !isCurrentRequest(owner, generation)) {
                return;
            }
            Window window = owner.getWindow();
            if (window != null) {
                applyHdrColorMode(window, owner, reason + "-window-ready", ignoredBrightness);
            }
        }

        @Override
        public void onViewAttachedToWindow(View root) {
            removeFocusObserver();
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.addOnWindowFocusChangeListener(this);
                focusObserver = new WeakReference<>(observer);
            }
            root.postOnAnimation(this::reapply);
        }

        @Override
        public void onViewDetachedFromWindow(View root) {
            removeFocusObserver();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus) reapply();
        }
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
