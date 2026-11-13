/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.annotation.Nullable;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.subtitle.DefaultSubtitleEngine;
import com.github.tvbox.osc.subtitle.SubtitleEngine;
import com.github.tvbox.osc.subtitle.SubtitleHdrStylePolicy;
import com.github.tvbox.osc.subtitle.model.Style;
import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * @author AveryZhong.
 */

@SuppressLint("AppCompatCustomView")
public class SimpleSubtitleView extends TextView
        implements SubtitleEngine, SubtitleEngine.OnSubtitleChangeListener,
        SubtitleEngine.OnSubtitlePreparedListener {

    private static final String TAG = "SimpleSubtitleView";
    private static final String EMPTY_TEXT = "";
    private static final String MANUAL_SUBTITLE_SELECTION_SUFFIX = "@manual";

    private SubtitleEngine mSubtitleEngine;
    private static final Pattern ASS_PRIMARY_COLOR =
            Pattern.compile("\\\\(?:1?c|c)&H([0-9A-Fa-f]{6,8})&");

    public boolean isInternal = false;

    public boolean hasInternal = false;

    private TextView backGroundText = null;//用于描边的TextView
    private boolean hdrSubtitleMode = false;
    private static final int DEFAULT_SUBTITLE_COLOR = Color.WHITE;
    /** A single neutral HDR caption tone shared by text and bitmap subtitles. */
    private static final int HDR_SUBTITLE_TONE = 224;
    private int requestedTextColor = DEFAULT_SUBTITLE_COLOR;
    private String lastRawSubtitleText = EMPTY_TEXT;
    private boolean lastSubtitleHasDefinedStyle;
    private boolean lastSubtitleUsesHdrStyle;
    @Nullable
    private CharSequence lastSubtitleStyledContent;
    @Nullable
    private Style lastSubtitleSourceStyle;
    private float baseTextSize;
    private Typeface baseTypeface;
    private int basePaintFlags;
    private boolean applyingSourceStyle;
    private boolean externalCueLogged;
    private boolean externalRenderLogged;

    public SimpleSubtitleView(final Context context) {
        super(context);
        backGroundText = new TextView(context);
        init();
    }

    public SimpleSubtitleView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        backGroundText = new TextView(context, attrs);
        init();
    }

    public SimpleSubtitleView(final Context context, final AttributeSet attrs,
                              final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        backGroundText = new TextView(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mSubtitleEngine = new DefaultSubtitleEngine();
        mSubtitleEngine.setOnSubtitlePreparedListener(this);
        mSubtitleEngine.setOnSubtitleChangeListener(this);
        // Keep normal output white and let HDR mode dim it to a neutral grey. The duplicated
        // background view remains an outline only, so it cannot turn the glyph body black.
        requestedTextColor = DEFAULT_SUBTITLE_COLOR;
        baseTextSize = getTextSize();
        baseTypeface = getTypeface();
        basePaintFlags = getPaintFlags();
        applyEffectiveTextColor();
    }

    @Override
    public void onSubtitlePrepared(@Nullable final List<Subtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            Log.w(TAG, "Selected external subtitle contains no readable cues");
            LOG.i("echo-subtitle view-prepared-empty external=" + !isInternal);
            setText(EMPTY_TEXT);
            return;
        }
        LOG.i("echo-subtitle view-prepared cues=" + subtitles.size()
                + " external=" + !isInternal + " visibility=" + getVisibility()
                + " shown=" + isShown() + " width=" + getWidth() + " height=" + getHeight());
        // SubtitleLoader completes on the app executor. Always marshal view operations through
        // the TextView so a vendor player callback can never mutate this view off the UI thread.
        post(new Runnable() {
            @Override
            public void run() {
                if (subtitles.isEmpty()) {
                    return;
                }
                setVisibility(VISIBLE);
                bringToFront();
                start();
            }
        });
    }

    @Override
    public void onSubtitleChanged(@Nullable final Subtitle subtitle) {
        if (subtitle == null) {
            lastRawSubtitleText = EMPTY_TEXT;
            lastSubtitleHasDefinedStyle = false;
            lastSubtitleUsesHdrStyle = false;
            lastSubtitleStyledContent = null;
            lastSubtitleSourceStyle = null;
            setText(EMPTY_TEXT);
            return;
        }
        String text = subtitle.content == null ? EMPTY_TEXT : subtitle.content;
        if (!isInternal) {
            // A delayed internal-track discovery callback can hide the overlay after the
            // external file has parsed. Reassert the overlay at the point of actual cue
            // delivery, so a successfully selected subtitle cannot remain invisible.
            if (getVisibility() != VISIBLE) {
                int previousVisibility = getVisibility();
                setVisibility(VISIBLE);
                LOG.i("echo-subtitle external-render-reveal previousVisibility="
                        + previousVisibility);
            }
            bringToFront();
        }
        boolean firstExternalCue = !isInternal && !externalCueLogged && !TextUtils.isEmpty(text);
        text = text.replaceAll("^.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,", "");
        lastRawSubtitleText = text;
        lastSubtitleHasDefinedStyle = hasSourceStyle(subtitle.style) || subtitle.hasSourceStyle;
        lastSubtitleStyledContent = subtitle.styledContent;
        lastSubtitleSourceStyle = subtitle.style;
        renderSubtitleTextSafely(text, subtitle.styledContent, subtitle.style);
        if (firstExternalCue) {
            externalCueLogged = true;
            externalRenderLogged = true;
            LOG.i("echo-subtitle external-first-cue-rendered startMs=" + subtitle.start.mseconds
                    + " endMs=" + subtitle.end.mseconds + " textLength=" + text.length()
                    + " visibility=" + getVisibility() + " shown=" + isShown()
                    + " width=" + getWidth() + " height=" + getHeight()
                    + " parent=" + (getParent() == null ? "null" : getParent().getClass().getSimpleName()));
        } else if (!isInternal && externalRenderLogged && getVisibility() != VISIBLE) {
            LOG.i("echo-subtitle external-render-hidden visibility=" + getVisibility()
                    + " shown=" + isShown());
        }
    }

    private CharSequence buildStyledSubtitleText(String rawText, boolean preserveSourceStyle) {
        String text = rawText == null ? "" : rawText;
        text = text.replaceAll("(?:\\r\\n)", "\n");
        text = text.replaceAll("(?:\\r)", "\n");
        text = text.replaceAll("\\\\N", "\n");
        if (containsAssColor(text)) {
            return parseAssColorSubtitle(text, preserveSourceStyle);
        }
        text = text.replaceAll("\\{[\\s\\S]*?\\}", "");
        text = text.replaceAll("(?:\\n)", "<br />");
        Spanned spanned;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            spanned = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY);
        } else {
            spanned = Html.fromHtml(text);
        }
        return spanned == null ? "" : normalizeSubtitleColorSpans(spanned, preserveSourceStyle);
    }

    private boolean containsAssColor(String text) {
        return text != null && ASS_PRIMARY_COLOR.matcher(text).find();
    }

    private CharSequence parseAssColorSubtitle(String text, boolean preserveSourceStyle) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        Integer activeColor = null;
        int activeStart = 0;
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '{') {
                int end = text.indexOf('}', index);
                if (end >= 0) {
                    if (activeColor != null && activeStart < builder.length()) {
                        builder.setSpan(new ForegroundColorSpan(activeColor), activeStart, builder.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    String tag = text.substring(index + 1, end);
                    Integer color = findAssPrimaryColor(tag);
                    if (color != null) {
                        activeColor = color;
                    } else if (tag.contains("\\r")) {
                        activeColor = null;
                    }
                    activeStart = builder.length();
                    index = end + 1;
                    continue;
                }
            }
            builder.append(ch);
            index++;
        }
        if (activeColor != null && activeStart < builder.length()) {
            builder.setSpan(new ForegroundColorSpan(activeColor), activeStart, builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return normalizeSubtitleColorSpans(builder, preserveSourceStyle);
    }

    private Integer findAssPrimaryColor(String tag) {
        Matcher matcher = ASS_PRIMARY_COLOR.matcher(tag);
        Integer color = null;
        while (matcher.find()) {
            color = parseAssColor(matcher.group(1));
        }
        return color;
    }

    private Integer parseAssColor(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 6) {
            return null;
        }
        String hex = value.length() > 6 ? value.substring(value.length() - 6) : value;
        try {
            int b = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int r = Integer.parseInt(hex.substring(4, 6), 16);
            return Color.rgb(r, g, b);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void setSubtitlePath(final String path) {
        isInternal = false;
        externalCueLogged = false;
        externalRenderLogged = false;
        mSubtitleEngine.setSubtitlePath(path);
    }

    @Override
    public void setSubtitleDelay(Integer mseconds) {
        mSubtitleEngine.setSubtitleDelay(mseconds);
    }

    public void setPlaySubtitleCacheKey(String cacheKey) {
        mSubtitleEngine.setPlaySubtitleCacheKey(cacheKey);
    }

    public String getPlaySubtitleCacheKey() {
        return mSubtitleEngine.getPlaySubtitleCacheKey();
    }

    private String getManualSubtitleCacheKey() {
        String subtitleCacheKey = getPlaySubtitleCacheKey();
        if (TextUtils.isEmpty(subtitleCacheKey)) {
            return null;
        }
        return subtitleCacheKey + MANUAL_SUBTITLE_SELECTION_SUFFIX;
    }

    public void setManualExternalSubtitleSelection(boolean manualSelection) {
        String manualCacheKey = getManualSubtitleCacheKey();
        if (TextUtils.isEmpty(manualCacheKey)) {
            return;
        }
        String cacheKey = MD5.string2MD5(manualCacheKey);
        if (manualSelection) {
            CacheManager.save(cacheKey, "1");
        } else {
            CacheManager.delete(cacheKey, "");
        }
    }

    public boolean hasManualExternalSubtitleSelection() {
        String manualCacheKey = getManualSubtitleCacheKey();
        if (TextUtils.isEmpty(manualCacheKey)) {
            return false;
        }
        Object cached = CacheManager.getCache(MD5.string2MD5(manualCacheKey));
        return "1".equals(String.valueOf(cached));
    }

    public void clearSubtitleCache() {
        String subtitleCacheKey = getPlaySubtitleCacheKey();
        if (subtitleCacheKey != null && subtitleCacheKey.length() > 0) {
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), "");
        }
        String manualCacheKey = getManualSubtitleCacheKey();
        if (!TextUtils.isEmpty(manualCacheKey)) {
            CacheManager.delete(MD5.string2MD5(manualCacheKey), "");
        }
    }

    public void setSubtitleTextColor(int color) {
        requestedTextColor = color;
        applyEffectiveTextColor();
    }

    public void setHdrSubtitleMode(boolean enabled) {
        hdrSubtitleMode = enabled;
        if (!TextUtils.isEmpty(lastRawSubtitleText)) {
            renderSubtitleTextSafely(lastRawSubtitleText, lastSubtitleStyledContent, lastSubtitleSourceStyle);
        } else {
            lastSubtitleUsesHdrStyle = false;
            applyEffectiveTextColor();
        }
        postInvalidate();
    }

    private void renderSubtitleTextSafely(String rawText,
                                         @Nullable CharSequence styledContent,
                                         @Nullable Style sourceStyle) {
        try {
            renderSubtitleText(rawText, styledContent, sourceStyle);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Subtitle styling failed; rendering the original cue", exception);
            lastSubtitleHasDefinedStyle = false;
            lastSubtitleUsesHdrStyle = hdrSubtitleMode
                    && SubtitleHdrStylePolicy.shouldApplyHdrStyle(false, rawText);
            try {
                applySourceStyle(null);
            } catch (RuntimeException styleException) {
                Log.e(TAG, "Unable to restore base subtitle style", styleException);
            }
            super.setText(rawText == null ? EMPTY_TEXT : rawText);
            applyEffectiveTextColor();
            postInvalidate();
        }
    }

    private void renderSubtitleText(String rawText,
                                    @Nullable CharSequence styledContent,
                                    @Nullable Style sourceStyle) {
        lastSubtitleUsesHdrStyle = hdrSubtitleMode
                && SubtitleHdrStylePolicy.shouldApplyHdrStyle(lastSubtitleHasDefinedStyle, rawText);
        applySourceStyle(sourceStyle);
        CharSequence renderedText;
        if (lastSubtitleUsesHdrStyle) {
            renderedText = buildHdrSubtitleText(rawText);
        } else if (styledContent != null && lastSubtitleHasDefinedStyle) {
            renderedText = styledContent;
        } else {
            renderedText = buildStyledSubtitleText(rawText, lastSubtitleHasDefinedStyle
                    || !SubtitleHdrStylePolicy.shouldApplyHdrStyle(false, rawText));
        }
        setText(renderedText);
        applyEffectiveTextColor();
    }

    private CharSequence buildHdrSubtitleText(String rawText) {
        CharSequence styled = buildStyledSubtitleText(rawText, false);
        String plain = styled == null ? "" : styled.toString();
        return plain.replace("\r\n", "\n").replace('\r', '\n');
    }

    public void prepareForInternalSubtitle() {
        stop();
        reset();
        clearSubtitleCache();
        isInternal = true;
        lastRawSubtitleText = EMPTY_TEXT;
        lastSubtitleHasDefinedStyle = false;
        lastSubtitleUsesHdrStyle = false;
        lastSubtitleStyledContent = null;
        lastSubtitleSourceStyle = null;
        setText(EMPTY_TEXT);
        setVisibility(VISIBLE);
        bringToFront();
    }

    private void applyEffectiveTextColor() {
        if (lastSubtitleHasDefinedStyle) {
            return;
        }
        int effectiveColor = lastSubtitleUsesHdrStyle ? dimForHdr(requestedTextColor) : requestedTextColor;
        // Some ASS/HTML/TTML tracks carry an alpha channel. Keep the requested RGB tone but never
        // let the subtitle fill become transparent; the black outline alone is not readable.
        super.setTextColor(opaque(effectiveColor));
    }

    private int dimForHdr(int color) {
        // HDR10, HDR10+, HLG and Dolby Vision use one neutral tone regardless of
        // the source subtitle's ASS/HTML color. This keeps peak luminance and
        // perceived color consistent across text and bitmap subtitle tracks.
        return Color.rgb(HDR_SUBTITLE_TONE, HDR_SUBTITLE_TONE, HDR_SUBTITLE_TONE);
    }

    private int opaque(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private CharSequence normalizeSubtitleColorSpans(CharSequence text, boolean preserveSourceStyle) {
        if (preserveSourceStyle) {
            return text;
        }
        if (!(text instanceof Spanned)) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        ForegroundColorSpan[] spans = builder.getSpans(0, builder.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            int flags = builder.getSpanFlags(span);
            builder.removeSpan(span);
            if (start >= 0 && end > start) {
                int color = lastSubtitleUsesHdrStyle
                        ? dimForHdr(span.getForegroundColor())
                        : opaque(span.getForegroundColor());
                builder.setSpan(new ForegroundColorSpan(color), start, end, flags);
            }
        }
        BackgroundColorSpan[] backgroundSpans = builder.getSpans(0, builder.length(), BackgroundColorSpan.class);
        for (BackgroundColorSpan span : backgroundSpans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            int flags = builder.getSpanFlags(span);
            builder.removeSpan(span);
            if (start >= 0 && end > start) {
                int color = lastSubtitleUsesHdrStyle
                        ? dimForHdr(span.getBackgroundColor())
                        : opaque(span.getBackgroundColor());
                builder.setSpan(new BackgroundColorSpan(color), start, end, flags);
            }
        }
        return builder;
    }

    private boolean hasSourceStyle(@Nullable Style style) {
        return style != null;
    }

    private void applySourceStyle(@Nullable Style style) {
        super.setTextColor(requestedTextColor);
        applyingSourceStyle = true;
        try {
        if (style == null) {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseTextSize);
            super.setTypeface(baseTypeface);
            super.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            setPaintFlags(basePaintFlags);
            applyEffectiveTextColor();
            return;
        }
        if (!TextUtils.isEmpty(style.fontSize)) {
            try {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        Float.parseFloat(style.fontSize.trim()));
            } catch (NumberFormatException ignored) {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseTextSize);
            }
        }
        int typefaceStyle = (style.bold ? Typeface.BOLD : Typeface.NORMAL)
                | (style.italic ? Typeface.ITALIC : Typeface.NORMAL);
        super.setTypeface(TextUtils.isEmpty(style.font)
                ? baseTypeface : Typeface.create(style.font, typefaceStyle), typefaceStyle);
        if ("bottom-left".equals(style.textAlign)) {
            super.setGravity(Gravity.BOTTOM | Gravity.LEFT);
        } else if ("bottom-right".equals(style.textAlign)) {
            super.setGravity(Gravity.BOTTOM | Gravity.RIGHT);
        } else if ("mid-left".equals(style.textAlign)) {
            super.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        } else if ("mid-right".equals(style.textAlign)) {
            super.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        } else if ("mid-center".equals(style.textAlign)) {
            super.setGravity(Gravity.CENTER);
        } else {
            super.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }
        if (!TextUtils.isEmpty(style.color)) {
            Integer color = parseSourceStyleColor(style.color);
            if (color != null) {
                super.setTextColor(color);
            } else {
                applyEffectiveTextColor();
            }
        } else {
            applyEffectiveTextColor();
        }
        setPaintFlags(style.underline
                ? basePaintFlags | Paint.UNDERLINE_TEXT_FLAG
                : basePaintFlags & ~Paint.UNDERLINE_TEXT_FLAG);
        } finally {
            applyingSourceStyle = false;
        }
    }

    @Nullable
    private Integer parseSourceStyleColor(String rgba) {
        String value = rgba.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 8) {
            return null;
        }
        try {
            int red = Integer.parseInt(value.substring(0, 2), 16);
            int green = Integer.parseInt(value.substring(2, 4), 16);
            int blue = Integer.parseInt(value.substring(4, 6), 16);
            int alpha = Integer.parseInt(value.substring(6, 8), 16);
            return Color.argb(alpha, red, green, blue);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void reset() {
        mSubtitleEngine.reset();
    }

    @Override
    public void start() {
        mSubtitleEngine.start();
    }

    @Override
    public void pause() {
        mSubtitleEngine.pause();
    }

    @Override
    public void resume() {
        mSubtitleEngine.resume();
    }

    @Override
    public void stop() {
        mSubtitleEngine.stop();
    }

    @Override
    public void destroy() {
        mSubtitleEngine.destroy();
    }

    @Override
    public void bindToMediaPlayer(AbstractPlayer mediaPlayer) {
        mSubtitleEngine.bindToMediaPlayer(mediaPlayer);
    }

    @Override
    public void setOnSubtitlePreparedListener(final OnSubtitlePreparedListener listener) {
        mSubtitleEngine.setOnSubtitlePreparedListener(listener);
    }

    @Override
    public void setOnSubtitleChangeListener(final OnSubtitleChangeListener listener) {
        mSubtitleEngine.setOnSubtitleChangeListener(listener);
    }

    @Override
    protected void onDetachedFromWindow() {
        destroy();
        super.onDetachedFromWindow();
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        //同步布局参数
        if (backGroundText != null) {
            backGroundText.setLayoutParams(params);
        }
        super.setLayoutParams(params);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (backGroundText == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        //两个TextView上的文字必须一致
        if (!TextUtils.equals(backGroundText.getText(), getText())) {
            backGroundText.setText(getText() == null ? EMPTY_TEXT : getText().toString());
        }
        backGroundText.measure(widthMeasureSpec, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        if (backGroundText != null) {
            backGroundText.setTextSize(size);
        }
        if (!applyingSourceStyle) {
            baseTextSize = getTextSize();
        }
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        if (backGroundText != null) {
            backGroundText.setTextSize(unit, size);
        }
        if (!applyingSourceStyle) {
            baseTextSize = getTextSize();
        }
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        if (backGroundText != null) {
            backGroundText.setText(text == null ? "" : text.toString());
        }
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (backGroundText != null) {
            backGroundText.layout(left, top, right, bottom);
        }
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!lastSubtitleHasDefinedStyle
                && SubtitleHdrStylePolicy.shouldApplyHdrStyle(false, lastRawSubtitleText)) {
            if (backGroundText != null) {
                drawBackGroundText();
                backGroundText.draw(canvas);
            }
        }
        super.onDraw(canvas);
    }

    private void drawBackGroundText() {
        TextPaint tp = backGroundText.getPaint();
        // Render the background layer as outline only. Filling the duplicated
        // text body creates a second gray subtitle layer on TV panels.
        tp.setStrokeWidth(10);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeJoin(Paint.Join.ROUND);
        tp.setStrokeMiter(10f);
        tp.setAntiAlias(true);
        // The duplicate layer is an outline only. Keep it black in both SDR and
        // HDR; applying the HDR fill tone here turns the outline into a bright
        // second subtitle and makes the caption appear white.
        if (backGroundText.getCurrentTextColor() != Color.BLACK) {
            backGroundText.setTextColor(Color.BLACK);
        }
        //将背景的文字对齐方式做同步
        if (backGroundText.getGravity() != getGravity()) {
            backGroundText.setGravity(getGravity());
        }
    }
}
