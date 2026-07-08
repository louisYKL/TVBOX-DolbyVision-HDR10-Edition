package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.app.UiModeManager;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.graphics.Rect;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.ui.tv.widget.LiquidGlassFrameLayout;
import com.github.tvbox.osc.ui.tv.widget.LiquidGlassSnapshotManager;
import com.github.tvbox.osc.ui.tv.widget.LiquidGlassTextView;
import com.github.tvbox.osc.util.AppManager;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import me.jessyan.autosize.AutoSizeCompat;
import me.jessyan.autosize.internal.CustomAdapt;
import xyz.doikki.videoplayer.util.CutoutUtil;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt {
    protected Context mContext;
    private LoadService mLoadService;
    private static final PathInterpolator APPLE_TV_TRANSITION =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    ? new PathInterpolator(0.22f, 1f, 0.36f, 1f)
                    : null;

    private static float screenRatio = -100.0f;
    private Boolean tvDevice;
    private boolean contentInitialized;
    private boolean activityRegistered;
    private boolean deferredLandscapeContent;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (isJava64Build()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        }
        try {
            if (screenRatio < 0) {
                DisplayMetrics dm = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(dm);
                int screenWidth = dm.widthPixels;
                int screenHeight = dm.heightPixels;
                screenRatio = (float) Math.max(screenWidth, screenHeight) / (float) Math.min(screenWidth, screenHeight);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        super.onCreate(savedInstanceState);
        mContext = this;
        registerActivityIfNeeded();
        if (shouldDeferLandscapeContent()) {
            deferredLandscapeContent = true;
            showDeferredLandscapePlaceholder();
            return;
        }
        inflateActivityContentIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureLandscapeContentInflated();
        hideSysBar();
        configureWindowMaterial();
        if (isTvDevice()) {
            LiquidGlassSnapshotManager.attach(this);
            LiquidGlassSnapshotManager.refresh(this);
        }
        changeWallpaper(false);
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor != null && isTvDevice()) {
            applyAppleTvBlur(decor.findViewById(android.R.id.content));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ensureLandscapeContentInflated();
            hideSysBar();
            configureWindowMaterial();
            if (isTvDevice()) {
                LiquidGlassSnapshotManager.refresh(this);
            }
        }
    }

    public void hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    protected void configureWindowMaterial() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.setElevation(0f);
            window.setBackgroundDrawable(null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    protected void applyAppleTvBlur(View view) {
        if (view == null) {
            return;
        }
        if (!isTvDevice()) {
            clearBlurRecursively(view);
            return;
        }
        try {
            applyBlurRecursively(view);
        } catch (Throwable ignored) {
        }
    }

    private void clearBlurRecursively(View view) {
        if (view == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(null);
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearBlurRecursively(group.getChildAt(i));
            }
        }
    }

    private void applyBlurRecursively(View view) {
        if (view == null) {
            return;
        }
        if (view instanceof LiquidGlassFrameLayout || view instanceof LiquidGlassTextView) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Drawable background = view.getBackground();
            Object tag = view.getTag();
            boolean glassTarget = background != null
                    && !(view instanceof TextView)
                    && (!(tag instanceof String) || !((String) tag).contains("no_blur"));
            try {
                if (glassTarget) {
                    view.setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP));
                } else {
                    view.setRenderEffect(null);
                }
            } catch (Throwable ignored) {
                view.setRenderEffect(null);
            }
        } else {
            if (view.getBackground() != null) {
                view.setAlpha(Math.max(view.getAlpha(), 0.985f));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyBlurRecursively(group.getChildAt(i));
            }
        }
    }

    @Override
    public Resources getResources() {
        if (Looper.myLooper() == Looper.getMainLooper() && shouldApplyLandscapeDensity()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this);
        }
        return super.getResources();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ensureLandscapeContentInflated();
    }

    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        if (mLoadService != null) {
            mLoadService.showCallback(LoadingCallback.class);
        }
    }

    protected boolean isLoading() {
        if (mLoadService != null && mLoadService.getCurrentCallback() != null) {
            return mLoadService.getCurrentCallback().equals(LoadingCallback.class);
        }
        return false;
    }

    protected void showEmpty() {
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onDestroy() {
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor != null) {
            decor.removeCallbacks(mEnsureLandscapeContentRunnable);
        }
        if (isTvDevice()) {
            LiquidGlassSnapshotManager.detach(this);
        }
        super.onDestroy();
        AppManager.getInstance().finishActivity(this);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
        applyActivityTransition();
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
        applyActivityTransition();
    }

    @Override
    public void finish() {
        super.finish();
        applyBackTransition();
    }

    protected void applyActivityTransition() {
        overridePendingTransition(0, 0);
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor != null) {
            decor.setAlpha(0.82f);
            decor.setScaleX(0.972f);
            decor.setScaleY(0.972f);
            decor.setTranslationY(26f);
            decor.setTranslationX(0f);
            decor.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(520)
                    .setInterpolator(APPLE_TV_TRANSITION)
                    .start();
        }
    }

    protected void applyBackTransition() {
        overridePendingTransition(0, 0);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public float getSizeInDp() {
        if (!shouldApplyLandscapeDensity()) {
            return 0;
        }
        return isBaseOnWidth() ? 1280 : 720;
    }

    @Override
    public boolean isBaseOnWidth() {
        if (!shouldApplyLandscapeDensity()) {
            return true;
        }
        return !(screenRatio >= 4.0f);
    }

    private void updateAutoSizeConfigForLandscape() {
        try {
            int width = 0;
            int height = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
                if (bounds != null) {
                    width = bounds.width();
                    height = bounds.height();
                }
            } else {
                DisplayMetrics metrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                width = metrics.widthPixels;
                height = metrics.heightPixels;
            }
            if (width > 0 && height > 0) {
                int landscapeWidth = Math.max(width, height);
                int landscapeHeight = Math.min(width, height);
                me.jessyan.autosize.AutoSizeConfig.getInstance()
                        .setScreenWidth(landscapeWidth)
                        .setScreenHeight(landscapeHeight);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean shouldApplyLandscapeDensity() {
        if (isTvDevice()) {
            return true;
        }
        if (!isJava64Build()) {
            return false;
        }
        updateAutoSizeConfigForLandscape();
        return true;
    }

    public boolean isTvDevice() {
        if (tvDevice == null) {
            tvDevice = detectTvDevice();
        }
        return tvDevice;
    }

    private boolean detectTvDevice() {
        try {
            UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null
                    && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
            int screenLayout = super.getResources().getConfiguration().screenLayout
                    & Configuration.SCREENLAYOUT_SIZE_MASK;
            if (!isJava64Build()) {
                return screenLayout > Configuration.SCREENLAYOUT_SIZE_LARGE
                        && !getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY);
            }
            android.content.pm.PackageManager pm = getPackageManager();
            if (pm != null) {
                if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                        || pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)) {
                    return true;
                }
                boolean largeNoTouch = screenLayout > Configuration.SCREENLAYOUT_SIZE_LARGE
                        && !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
                        && !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY);
                return largeNoTouch;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isJava64Build() {
        return "java64".equals(BuildConfig.FLAVOR) || "python64".equals(BuildConfig.FLAVOR);
    }

    protected boolean isJava64PhoneDevice() {
        if (!isJava64Build() || isTvDevice()) {
            return false;
        }
        try {
            PackageManager pm = getPackageManager();
            return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean shouldDeferLandscapeContent() {
        return isJava64PhoneDevice() && !isLandscapeReadyForJava64Phone();
    }

    private boolean isLandscapeReadyForJava64Phone() {
        Configuration configuration = super.getResources().getConfiguration();
        return hasLandscapeWindowMetrics()
                || (configuration != null && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    private void showDeferredLandscapePlaceholder() {
        FrameLayout placeholder = new FrameLayout(this);
        placeholder.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        placeholder.setBackgroundResource(R.drawable.bg_apple_tv_shell);
        setContentView(placeholder);
        configureWindowMaterial();
    }

    private void ensureLandscapeContentInflated() {
        if (!contentInitialized && !shouldDeferLandscapeContent()) {
            inflateActivityContentIfNeeded();
        } else if (!contentInitialized && deferredLandscapeContent) {
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null) {
                decor.removeCallbacks(mEnsureLandscapeContentRunnable);
                decor.postDelayed(mEnsureLandscapeContentRunnable, 32L);
            }
        }
    }

    private void inflateActivityContentIfNeeded() {
        if (contentInitialized) {
            return;
        }
        setContentView(getLayoutResID());
        CutoutUtil.adaptCutoutAboveAndroidP(mContext, true);//设置刘海
        contentInitialized = true;
        deferredLandscapeContent = false;
        init();
    }

    private void registerActivityIfNeeded() {
        if (activityRegistered) {
            return;
        }
        AppManager.getInstance().addActivity(this);
        activityRegistered = true;
    }

    private final Runnable mEnsureLandscapeContentRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                return;
            }
            ensureLandscapeContentInflated();
        }
    };

    private boolean hasLandscapeWindowMetrics() {
        if (!isJava64Build() || isTvDevice()) {
            return false;
        }
        try {
            int width;
            int height;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
                width = bounds == null ? 0 : bounds.width();
                height = bounds == null ? 0 : bounds.height();
            } else {
                DisplayMetrics metrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                width = metrics.widthPixels;
                height = metrics.heightPixels;
            }
            return width > 0 && height > 0 && width > height;
        } catch (Throwable ignored) {
            return false;
        }
    }

    protected static BitmapDrawable globalWp = null;

    public void changeWallpaper(boolean force) {
        if (!force && globalWp != null)
            getWindow().setBackgroundDrawable(globalWp);
        try {
            File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
            if (wp.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
                // 从Options中获取图片的分辨率
                int imageHeight = opts.outHeight;
                int imageWidth = opts.outWidth;
                int picHeight = 720;
                int picWidth = 1080;
                int scaleX = imageWidth / picWidth;
                int scaleY = imageHeight / picHeight;
                int scale = 1;
                if (scaleX > scaleY && scaleY >= 1) {
                    scale = scaleX;
                }
                if (scaleX < scaleY && scaleX >= 1) {
                    scale = scaleY;
                }
                opts.inJustDecodeBounds = false;
                // 采样率
                opts.inSampleSize = scale;
                globalWp = new BitmapDrawable(BitmapFactory.decodeFile(wp.getAbsolutePath(), opts));
            } else {
                globalWp = null;
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            globalWp = null;
        }
        if (globalWp != null)
            getWindow().setBackgroundDrawable(globalWp);
        else
            getWindow().setBackgroundDrawableResource(R.drawable.bg_apple_tv_shell);
    }
}
