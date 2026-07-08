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
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.subtitle.DefaultSubtitleEngine;
import com.github.tvbox.osc.subtitle.SubtitleEngine;
import com.github.tvbox.osc.subtitle.model.Subtitle;
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

    private static final String EMPTY_TEXT = "";

    private SubtitleEngine mSubtitleEngine;
    private static final Pattern ASS_PRIMARY_COLOR =
            Pattern.compile("\\\\(?:1?c|c)&H([0-9A-Fa-f]{6,8})&");

    public boolean isInternal = false;

    public boolean hasInternal = false;

    private TextView backGroundText = null;//用于描边的TextView
    private boolean hdrSubtitleMode = false;
    private int requestedTextColor = Color.WHITE;
    private String lastRawSubtitleText = EMPTY_TEXT;

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
        requestedTextColor = getCurrentTextColor();
        applyEffectiveTextColor();
    }

    @Override
    public void onSubtitlePrepared(@Nullable final List<Subtitle> subtitles) {
        start();
    }

    @Override
    public void onSubtitleChanged(@Nullable final Subtitle subtitle) {
        if (subtitle == null) {
            lastRawSubtitleText = EMPTY_TEXT;
            setText(EMPTY_TEXT);
            return;
        }
        String text = subtitle.content;
        text = text.replaceAll("^.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,", "");
        lastRawSubtitleText = text;
        setText(buildStyledSubtitleText(text));
    }

    private CharSequence buildStyledSubtitleText(String rawText) {
        String text = rawText == null ? "" : rawText;
        text = text.replaceAll("(?:\\r\\n)", "\n");
        text = text.replaceAll("(?:\\r)", "\n");
        text = text.replaceAll("\\\\N", "\n");
        if (containsAssColor(text)) {
            return parseAssColorSubtitle(text);
        }
        text = text.replaceAll("\\{[\\s\\S]*?\\}", "");
        text = text.replaceAll("(?:\\n)", "<br />");
        Spanned spanned;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            spanned = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY);
        } else {
            spanned = Html.fromHtml(text);
        }
        return spanned == null ? "" : applyHdrColorSpans(spanned);
    }

    private boolean containsAssColor(String text) {
        return text != null && ASS_PRIMARY_COLOR.matcher(text).find();
    }

    private CharSequence parseAssColorSubtitle(String text) {
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
        return applyHdrColorSpans(builder);
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

    public void clearSubtitleCache() {
        String subtitleCacheKey = getPlaySubtitleCacheKey();
        if (subtitleCacheKey != null && subtitleCacheKey.length() > 0) {
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), "");
        }
    }

    public void setSubtitleTextColor(int color) {
        requestedTextColor = color;
        applyEffectiveTextColor();
    }

    public void setHdrSubtitleMode(boolean enabled) {
        if (hdrSubtitleMode == enabled) {
            return;
        }
        hdrSubtitleMode = enabled;
        applyEffectiveTextColor();
        if (!TextUtils.isEmpty(lastRawSubtitleText)) {
            setText(buildStyledSubtitleText(lastRawSubtitleText));
        }
        postInvalidate();
    }

    public void prepareForInternalSubtitle() {
        stop();
        reset();
        clearSubtitleCache();
        isInternal = true;
        lastRawSubtitleText = EMPTY_TEXT;
        setText(EMPTY_TEXT);
        setVisibility(VISIBLE);
        bringToFront();
    }

    private void applyEffectiveTextColor() {
        super.setTextColor(hdrSubtitleMode ? dimForHdr(requestedTextColor) : Color.WHITE);
    }

    private int dimForHdr(int color) {
        int alpha = Color.alpha(color);
        int red = Math.round(Color.red(color) * 0.68f);
        int green = Math.round(Color.green(color) * 0.68f);
        int blue = Math.round(Color.blue(color) * 0.68f);
        int max = Math.max(red, Math.max(green, blue));
        if (max <= 160) {
            return Color.argb(alpha, red, green, blue);
        }
        float scale = 160f / max;
        return Color.argb(alpha, Math.round(red * scale), Math.round(green * scale), Math.round(blue * scale));
    }

    private CharSequence applyHdrColorSpans(CharSequence text) {
        if (!hdrSubtitleMode || !(text instanceof Spanned)) {
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
                builder.setSpan(new ForegroundColorSpan(dimForHdr(span.getForegroundColor())), start, end, flags);
            }
        }
        BackgroundColorSpan[] backgroundSpans = builder.getSpans(0, builder.length(), BackgroundColorSpan.class);
        for (BackgroundColorSpan span : backgroundSpans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            int flags = builder.getSpanFlags(span);
            builder.removeSpan(span);
            if (start >= 0 && end > start) {
                builder.setSpan(new BackgroundColorSpan(dimForHdr(span.getBackgroundColor())), start, end, flags);
            }
        }
        return builder;
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
        backGroundText.setLayoutParams(params);
        super.setLayoutParams(params);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        String tt = backGroundText.getText() == null ? "" : backGroundText.getText().toString();
        String current = getText() == null ? "" : getText().toString();
        //两个TextView上的文字必须一致
        if (TextUtils.isEmpty(tt) || !tt.equals(current)) {
            backGroundText.setText(current);
            this.postInvalidate();
        }
        backGroundText.measure(widthMeasureSpec, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        backGroundText.setTextSize(size);
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
        backGroundText.layout(left, top, right, bottom);
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        //其他地方，backGroundText和super的先后顺序影响不会很大，但是此处必须要先绘制backGroundText，
        drawBackGroundText();
        backGroundText.draw(canvas);
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
        backGroundText.setTextColor(hdrSubtitleMode ? dimForHdr(Color.BLACK) : Color.BLACK);
        //将背景的文字对齐方式做同步
        backGroundText.setGravity(getGravity());
    }
}
