package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.provider.Settings;
import android.os.Build;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import com.github.tvbox.osc.base.App;

import java.util.WeakHashMap;

public final class HdrOutputManager {
    private HdrOutputManager() {
    }

    private static final WeakHashMap<Activity, Float> HDR_BRIGHTNESS_BACKUP = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Integer> HDR_SYSTEM_BRIGHTNESS_BACKUP = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Integer> HDR_SYSTEM_BRIGHTNESS_MODE_BACKUP = new WeakHashMap<>();

    public static boolean requestHdr(Context context, String reason) {
        return requestHdr(context, reason, true);
    }

    public static boolean requestBrightnessBoostOnly(Context context, String reason, boolean boostBrightness) {
        Activity activity = findActivity(context);
        if (activity != null && Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> requestBrightnessBoostOnly(activity, reason + "-main", boostBrightness));
            LOG.i("echo-hdr-window brightness-only-posted-main reason=" + reason);
            return true;
        }
        Window window = activity == null ? null : activity.getWindow();
        if (window == null) {
            LOG.i("echo-hdr-window brightness-only-skip no-window reason=" + reason);
            return false;
        }
        try {
            WindowManager.LayoutParams attrs = window.getAttributes();
            applyJava64HdrBrightness(activity, attrs, boostBrightness, reason);
            window.setAttributes(attrs);
            WindowManager.LayoutParams applied = window.getAttributes();
            LOG.i("echo-hdr-window brightness-only reason=" + reason
                    + " brightness=" + applied.screenBrightness
                    + " boost=" + boostBrightness
                    + " caps=" + HdrDeviceSupport.query(activity).summary);
            return true;
        } catch (Throwable th) {
            LOG.e("echo-hdr-window brightness-only-failed reason=" + reason + " err=" + th.getMessage());
            return false;
        }
    }

    public static boolean requestHdr(Context context, String reason, boolean boostBrightness) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LOG.i("echo-hdr-window skip sdk=" + Build.VERSION.SDK_INT + " reason=" + reason);
            return false;
        }
        Activity activity = findActivity(context);
        if (activity != null && Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> requestHdr(activity, reason + "-main", boostBrightness));
            LOG.i("echo-hdr-window posted-main reason=" + reason);
            return true;
        }
        Window window = activity == null ? null : activity.getWindow();
        if (window == null) {
            LOG.i("echo-hdr-window skip no-window reason=" + reason);
            return false;
        }
        try {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.setColorMode(ActivityInfo.COLOR_MODE_HDR);
            applyJava64HdrBrightness(activity, attrs, boostBrightness, reason);
            window.setAttributes(attrs);
            WindowManager.LayoutParams applied = window.getAttributes();
            LOG.i("echo-hdr-window requested reason=" + reason
                    + " colorMode=" + applied.getColorMode()
                    + " brightness=" + applied.screenBrightness
                    + " boost=" + boostBrightness
                    + " hdrColorMode=" + ActivityInfo.COLOR_MODE_HDR
                    + " caps=" + HdrDeviceSupport.query(activity).summary);
            return true;
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
        if (activity != null && Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> releaseHdr(activity, reason + "-main"));
            LOG.i("echo-hdr-window release-posted-main reason=" + reason);
            return;
        }
        Window window = activity == null ? null : activity.getWindow();
        if (window == null) {
            return;
        }
        try {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.setColorMode(ActivityInfo.COLOR_MODE_DEFAULT);
            applyJava64HdrBrightness(activity, attrs, false, reason);
            window.setAttributes(attrs);
            LOG.i("echo-hdr-window released reason=" + reason
                    + " colorMode=" + window.getAttributes().getColorMode()
                    + " brightness=" + window.getAttributes().screenBrightness);
        } catch (Throwable th) {
            LOG.e("echo-hdr-window release-failed reason=" + reason + " err=" + th.getMessage());
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

    private static void applyJava64HdrBrightness(Activity activity,
                                                 WindowManager.LayoutParams attrs,
                                                 boolean enableHdrBrightness,
                                                 String reason) {
        if (activity == null || attrs == null || !App.isJava64Build()) {
            return;
        }
        if (isTvLikeHost(activity)) {
            return;
        }
        try {
            if (enableHdrBrightness) {
                if (!HDR_BRIGHTNESS_BACKUP.containsKey(activity)) {
                    HDR_BRIGHTNESS_BACKUP.put(activity, attrs.screenBrightness);
                }
                attrs.screenBrightness = 1.0f;
                applyJava64SystemHdrBrightness(activity, true, reason);
                LOG.i("echo-hdr-brightness boost reason=" + reason + " prev=" + HDR_BRIGHTNESS_BACKUP.get(activity));
            } else {
                if (HDR_BRIGHTNESS_BACKUP.containsKey(activity)) {
                    Float original = HDR_BRIGHTNESS_BACKUP.remove(activity);
                    attrs.screenBrightness = original == null ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : original;
                    LOG.i("echo-hdr-brightness restore reason=" + reason + " restored=" + attrs.screenBrightness);
                } else {
                    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    LOG.i("echo-hdr-brightness auto reason=" + reason);
                }
                applyJava64SystemHdrBrightness(activity, false, reason);
            }
        } catch (Throwable th) {
            LOG.e("echo-hdr-brightness failed reason=" + reason + " err=" + th.getMessage());
        }
    }

    private static void applyJava64SystemHdrBrightness(Activity activity,
                                                       boolean enableHdrBrightness,
                                                       String reason) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            if (!Settings.System.canWrite(activity)) {
                LOG.i("echo-hdr-system-brightness skip-no-write reason=" + reason);
                return;
            }
            if (enableHdrBrightness) {
                if (!HDR_SYSTEM_BRIGHTNESS_BACKUP.containsKey(activity)) {
                    HDR_SYSTEM_BRIGHTNESS_BACKUP.put(activity,
                            Settings.System.getInt(activity.getContentResolver(),
                                    Settings.System.SCREEN_BRIGHTNESS, 255));
                }
                if (!HDR_SYSTEM_BRIGHTNESS_MODE_BACKUP.containsKey(activity)) {
                    HDR_SYSTEM_BRIGHTNESS_MODE_BACKUP.put(activity,
                            Settings.System.getInt(activity.getContentResolver(),
                                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL));
                }
                Settings.System.putInt(activity.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(activity.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, 255);
                LOG.i("echo-hdr-system-brightness boost reason=" + reason
                        + " backup=" + HDR_SYSTEM_BRIGHTNESS_BACKUP.get(activity));
            } else {
                Integer originalBrightness = HDR_SYSTEM_BRIGHTNESS_BACKUP.remove(activity);
                Integer originalMode = HDR_SYSTEM_BRIGHTNESS_MODE_BACKUP.remove(activity);
                if (originalMode != null) {
                    Settings.System.putInt(activity.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE, originalMode);
                }
                if (originalBrightness != null) {
                    Settings.System.putInt(activity.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, originalBrightness);
                }
                LOG.i("echo-hdr-system-brightness restore reason=" + reason
                        + " brightness=" + originalBrightness
                        + " mode=" + originalMode);
            }
        } catch (Throwable th) {
            LOG.e("echo-hdr-system-brightness failed reason=" + reason + " err=" + th.getMessage());
        }
    }

    private static boolean isTvLikeHost(Activity activity) {
        if (activity instanceof com.github.tvbox.osc.base.BaseActivity) {
            return ((com.github.tvbox.osc.base.BaseActivity) activity).isTvDevice();
        }
        return false;
    }
}
