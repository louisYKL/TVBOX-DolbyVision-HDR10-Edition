package com.github.tvbox.osc.ui.tv.widget;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.WeakHashMap;

public final class LiquidGlassSnapshotManager {
    private static final WeakHashMap<View, SnapshotHolder> HOLDERS = new WeakHashMap<>();
    private static final int DOWNSAMPLE = 6;
    private static final int BLUR_RADIUS = 14;

    private LiquidGlassSnapshotManager() {
    }

    public static void attach(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        SnapshotHolder holder = HOLDERS.get(decor);
        if (holder != null) {
            return;
        }
        holder = new SnapshotHolder(decor);
        HOLDERS.put(decor, holder);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(holder.layoutListener);
        holder.schedule();
    }

    public static void detach(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        SnapshotHolder holder = HOLDERS.remove(decor);
        if (holder == null) {
            return;
        }
        if (decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(holder.layoutListener);
        }
        holder.release();
    }

    public static void refresh(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        SnapshotHolder holder = HOLDERS.get(decor);
        if (holder != null) {
            holder.schedule();
        }
    }

    static boolean drawBackdrop(Canvas canvas, View view, RectF dst, Paint paint) {
        View root = view.getRootView();
        SnapshotHolder holder = HOLDERS.get(root);
        if (holder == null || holder.bitmap == null || holder.bitmap.isRecycled()) {
            return false;
        }
        if (view.getWidth() <= 0 || view.getHeight() <= 0 || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return false;
        }
        int[] rootPos = holder.rootLocation;
        int[] viewPos = holder.viewLocation;
        root.getLocationOnScreen(rootPos);
        view.getLocationOnScreen(viewPos);
        int left = Math.max(0, (viewPos[0] - rootPos[0]) / holder.downsample);
        int top = Math.max(0, (viewPos[1] - rootPos[1]) / holder.downsample);
        int right = Math.min(holder.bitmap.getWidth(), Math.max(left + 1, (viewPos[0] - rootPos[0] + view.getWidth()) / holder.downsample));
        int bottom = Math.min(holder.bitmap.getHeight(), Math.max(top + 1, (viewPos[1] - rootPos[1] + view.getHeight()) / holder.downsample));
        holder.srcRect.set(left, top, right, bottom);
        canvas.drawBitmap(holder.bitmap, holder.srcRect, dst, paint);
        return true;
    }

    private static final class SnapshotHolder {
        final View root;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Rect srcRect = new Rect();
        final int[] rootLocation = new int[2];
        final int[] viewLocation = new int[2];
        final Runnable refreshRunnable = new Runnable() {
            @Override
            public void run() {
                capture();
            }
        };
        final ViewTreeObserver.OnGlobalLayoutListener layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                schedule();
            }
        };
        Bitmap bitmap;
        final int downsample;
        final boolean reducedQuality;
        boolean scheduled;

        SnapshotHolder(View root) {
            this.root = root;
            ActivityManager manager = (ActivityManager) root.getContext()
                    .getSystemService(Activity.ACTIVITY_SERVICE);
            reducedQuality = manager != null && manager.isLowRamDevice();
            // Keep the cached backdrop bounded on low-memory TV firmware. The
            // glass gradient remains full quality; only the blurred backdrop is
            // sampled more coarsely because it is a supporting visual layer.
            downsample = reducedQuality ? 8 : DOWNSAMPLE;
        }

        void schedule() {
            if (scheduled) {
                return;
            }
            scheduled = true;
            handler.postDelayed(refreshRunnable, 48);
        }

        void capture() {
            scheduled = false;
            if (root.getWidth() <= 0 || root.getHeight() <= 0) {
                return;
            }
            ArrayList<View> glassViews = new ArrayList<>();
            collectGlassViews(root, glassViews);
            int size = glassViews.size();
            float[] previousAlpha = new float[size];
            for (int i = 0; i < size; i++) {
                previousAlpha[i] = glassViews.get(i).getAlpha();
                glassViews.get(i).setAlpha(0f);
            }
            int bmpWidth = Math.max(1, root.getWidth() / downsample);
            int bmpHeight = Math.max(1, root.getHeight() / downsample);
            Bitmap captureBitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(captureBitmap);
            canvas.scale(1f / downsample, 1f / downsample);
            root.draw(canvas);
            for (int i = 0; i < size; i++) {
                glassViews.get(i).setAlpha(previousAlpha[i]);
            }
            Bitmap blurred = StackBlur.blur(captureBitmap, BLUR_RADIUS);
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            bitmap = blurred;
            invalidateGlassViews(glassViews);
        }

        void release() {
            handler.removeCallbacksAndMessages(null);
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
                bitmap = null;
            }
        }

        private void collectGlassViews(View view, ArrayList<View> result) {
            if (view instanceof LiquidGlassFrameLayout) {
                result.add(view);
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    collectGlassViews(group.getChildAt(i), result);
                }
            }
        }

        private void invalidateGlassViews(ArrayList<View> views) {
            for (View glassView : views) {
                glassView.invalidate();
            }
        }
    }
}
