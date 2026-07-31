package com.github.tvbox.osc.subtitle.widget;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.text.Cue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Normalizes embedded bitmap subtitles (PGS/VobSub) for TV panels.
 *
 * <p>Text subtitles are rendered by {@link SimpleSubtitleView}. Bitmap subtitle cues bypass that
 * view and are painted directly by ExoPlayer, so text alpha/style fixes cannot affect them. PGS
 * images commonly contain very low-alpha anti-aliased pixels on HDR output. Rendering those
 * images through an SDR-white or HDR-grey mask with a small black outline keeps the glyph
 * readable without changing the cue's placement, timing, or scale.</p>
 */
public final class BitmapSubtitleRenderer {
    private static final int HDR_FILL_RED = 180;
    private static final int HDR_FILL_GREEN = 180;
    private static final int HDR_FILL_BLUE = 180;
    private static final int SDR_FILL_RED = 255;
    private static final int SDR_FILL_GREEN = 255;
    private static final int SDR_FILL_BLUE = 255;
    private static final int OUTLINE_RADIUS = 2;
    private static final int MIN_FILL_ALPHA = 220;

    /** The ExoPlayer bitmap is immutable from our point of view; cache both output modes. */
    private static final WeakHashMap<Bitmap, BitmapVariants> NORMALIZED_BITMAPS = new WeakHashMap<>();

    private BitmapSubtitleRenderer() {
    }

    /** Returns SDR-normalized cues for callers that do not carry output-mode information. */
    public static List<Cue> normalizeCues(@Nullable List<Cue> cues) {
        return normalizeCues(cues, false);
    }

    /** Returns immutable bitmap cues normalized for the active HDR or SDR output mode. */
    public static List<Cue> normalizeCues(@Nullable List<Cue> cues, boolean hdrOutput) {
        if (cues == null || cues.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<Cue> normalized = new ArrayList<>(cues.size());
        boolean changed = false;
        for (Cue cue : cues) {
            if (cue == null || cue.bitmap == null) {
                normalized.add(cue);
                continue;
            }
            Bitmap bitmap = normalizeBitmap(cue.bitmap, hdrOutput);
            if (bitmap != cue.bitmap) {
                normalized.add(cue.buildUpon().setBitmap(bitmap).build());
                changed = true;
            } else {
                normalized.add(cue);
            }
        }
        if (!changed && cues instanceof java.util.RandomAccess) {
            // ExoPlayer already supplies an immutable list in normal operation. Do not allocate
            // another list when no bitmap needed conversion.
            return cues;
        }
        return Collections.unmodifiableList(normalized);
    }

    /**
     * Creates a grey-on-HDR or white-on-SDR bitmap while preserving transparent pixels and
     * anti-aliased shape.
     * The result is cached by source bitmap identity because ExoPlayer reuses PGS bitmaps across
     * cue callbacks.
     */
    public static Bitmap normalizeBitmap(@Nullable Bitmap source) {
        return normalizeBitmap(source, false);
    }

    public static Bitmap normalizeBitmap(@Nullable Bitmap source, boolean hdrOutput) {
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return source;
        }
        synchronized (NORMALIZED_BITMAPS) {
            BitmapVariants variants = NORMALIZED_BITMAPS.get(source);
            Bitmap cached = variants == null ? null : variants.get(hdrOutput);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }

        final int width = source.getWidth();
        final int height = source.getHeight();
        final int pixelCount = width * height;
        // Subtitle bitmaps are normally ARGB_8888. getPixels() also supports RGB_565, while the
        // output explicitly uses ARGB_8888 so the outline can retain transparent surroundings.
        try {
            int[] pixels = new int[pixelCount];
            source.getPixels(pixels, 0, width, 0, 0, width, height);
            int visiblePixels = 0;
            for (int pixel : pixels) {
                if (pixelCoverage(pixel) > 0) {
                    visiblePixels++;
                }
            }
            if (visiblePixels == 0) {
                // Do not turn an empty cue into a visible rectangle.
                return source;
            }

            int[] output = new int[pixelCount];
            final int fillRed = hdrOutput ? HDR_FILL_RED : SDR_FILL_RED;
            final int fillGreen = hdrOutput ? HDR_FILL_GREEN : SDR_FILL_GREEN;
            final int fillBlue = hdrOutput ? HDR_FILL_BLUE : SDR_FILL_BLUE;
            for (int y = 0; y < height; y++) {
                int rowStart = y * width;
                for (int x = 0; x < width; x++) {
                    int index = rowStart + x;
                    int alpha = pixelCoverage(pixels[index]);
                    if (alpha > 0) {
                        // Keep anti-aliased edges but never allow the glyph body to remain a faint,
                        // almost transparent color on HDR output.
                        int fillAlpha = Math.max(MIN_FILL_ALPHA, alpha);
                        output[index] = Color.argb(fillAlpha, fillRed, fillGreen, fillBlue);
                        continue;
                    }
                    if (hasVisibleNeighbour(pixels, width, height, x, y, OUTLINE_RADIUS)) {
                        output[index] = Color.BLACK;
                    }
                }
            }

            Bitmap normalized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            normalized.setHasAlpha(true);
            normalized.setPixels(output, 0, width, 0, 0, width, height);
            synchronized (NORMALIZED_BITMAPS) {
                BitmapVariants variants = NORMALIZED_BITMAPS.get(source);
                if (variants == null) {
                    variants = new BitmapVariants();
                    NORMALIZED_BITMAPS.put(source, variants);
                }
                Bitmap raced = variants.get(hdrOutput);
                if (raced != null && !raced.isRecycled()) {
                    normalized.recycle();
                    return raced;
                }
                variants.set(hdrOutput, normalized);
            }
            return normalized;
        } catch (Throwable ignored) {
            // A malformed or hardware-only cue must not take down video playback. ExoPlayer can
            // still render the original bitmap if a platform rejects pixel access.
            return source;
        }
    }

    private static boolean hasVisibleNeighbour(int[] pixels,
                                               int width,
                                               int height,
                                               int x,
                                               int y,
                                               int radius) {
        int left = Math.max(0, x - radius);
        int right = Math.min(width - 1, x + radius);
        int top = Math.max(0, y - radius);
        int bottom = Math.min(height - 1, y + radius);
        for (int neighbourY = top; neighbourY <= bottom; neighbourY++) {
            int row = neighbourY * width;
            for (int neighbourX = left; neighbourX <= right; neighbourX++) {
                if (pixelCoverage(pixels[row + neighbourX]) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Some vendor PGS decoders expose an ALPHA_8-like bitmap with coverage in RGB and alpha=0.
     * Treat the brightest RGB component as coverage so the cue cannot degrade into an invisible
     * source bitmap with only a white/black edge left on screen.
     */
    private static int pixelCoverage(int pixel) {
        int alpha = Color.alpha(pixel);
        if (alpha > 0) {
            return alpha;
        }
        return Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel)));
    }

    private static final class BitmapVariants {
        private Bitmap sdr;
        private Bitmap hdr;

        Bitmap get(boolean hdrOutput) {
            return hdrOutput ? hdr : sdr;
        }

        void set(boolean hdrOutput, Bitmap bitmap) {
            if (hdrOutput) {
                hdr = bitmap;
            } else {
                sdr = bitmap;
            }
        }
    }
}
