package com.github.tvbox.osc.util;

import static android.content.Context.UI_MODE_SERVICE;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.github.tvbox.osc.BuildConfig;

public class ScreenUtils {

    public static double getSqrt(Activity activity) {
        WindowManager wm = activity.getWindowManager();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        double x = Math.pow(dm.widthPixels / dm.xdpi, 2);
        double y = Math.pow(dm.heightPixels / dm.ydpi, 2);
        double screenInches = Math.sqrt(x + y);// 屏幕尺寸
        return screenInches;
    }

    private static boolean checkScreenLayoutIsLarge(Context context) {
        return (context.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) > Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    private static boolean checkIsPhone(Context context) {
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        return telephonyManager != null && telephonyManager.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
    }

    public static boolean isTv(Context context) {
        if (context == null) {
            return false;
        }
        Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
        UiModeManager uiModeManager = (UiModeManager) appContext.getSystemService(UI_MODE_SERVICE);
        boolean uiModeTv = uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        if (uiModeTv) {
            return true;
        }
        if (!isJava64Build()) {
            return checkScreenLayoutIsLarge(appContext) && !checkIsPhone(appContext);
        }
        PackageManager pm = appContext.getPackageManager();
        if (pm != null) {
            if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                return true;
            }
            boolean hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
            return checkScreenLayoutIsLarge(appContext) && !hasTouchscreen && !checkIsPhone(appContext);
        }
        return false;
    }

    public static boolean isTv32Device(Context context) {
        return context != null && !isJava64Build() && isTv(context);
    }

    private static boolean isJava64Build() {
        return "java64".equals(BuildConfig.FLAVOR) || "python64".equals(BuildConfig.FLAVOR);
    }


}
