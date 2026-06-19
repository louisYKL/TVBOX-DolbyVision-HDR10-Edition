package com.github.tvbox.osc.player;

import android.media.MediaPlayer;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import xyz.doikki.videoplayer.player.AndroidMediaPlayer;

public final class SystemPlayerTrackManager {

    private SystemPlayerTrackManager() {
    }

    public static TrackInfo getTrackInfo(AndroidMediaPlayer mediaPlayer) {
        TrackInfo data = new TrackInfo();
        if (mediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return data;
        }
        MediaPlayer.TrackInfo[] trackInfos = mediaPlayer.getTrackInfo();
        if (trackInfos == null) {
            return data;
        }
        int selectedAudio = mediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int selectedTimedText = mediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int selectedSubtitle = mediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
        for (int i = 0; i < trackInfos.length; i++) {
            MediaPlayer.TrackInfo info = trackInfos[i];
            if (info == null) {
                continue;
            }
            try {
                int type = info.getTrackType();
                if (type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
                        && type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                        && type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                    continue;
                }
                String language = getFriendlyLanguage(info.getLanguage(), null);
                TrackInfoBean bean = new TrackInfoBean();
                bean.trackId = i;
                bean.index = i;
                bean.language = language;
                bean.renderId = type;
                bean.name = buildDisplayName(type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO ? "音轨" : "字幕",
                        type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO ? data.getAudio().size() + 1 : data.getSubtitle().size() + 1,
                        language, "");
                if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    bean.selected = i == selectedAudio;
                } else if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                    bean.selected = i == selectedSubtitle;
                } else {
                    bean.selected = i == selectedTimedText;
                }
                if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    data.addAudio(bean);
                } else {
                    data.addSubtitle(bean);
                }
            } catch (Throwable ignored) {
            }
        }
        return data;
    }

    public static void selectTrack(AndroidMediaPlayer mediaPlayer, @Nullable TrackInfoBean track) {
        if (mediaPlayer == null || track == null) {
            return;
        }
        clearSubtitleSelections(mediaPlayer, track);
        mediaPlayer.selectTrack(track.trackId);
    }

    public static void clearSubtitleSelections(AndroidMediaPlayer mediaPlayer, @Nullable TrackInfoBean exceptTrack) {
        if (mediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        MediaPlayer.TrackInfo[] trackInfos = mediaPlayer.getTrackInfo();
        if (trackInfos == null) {
            return;
        }
        for (int i = 0; i < trackInfos.length; i++) {
            MediaPlayer.TrackInfo info = trackInfos[i];
            if (info == null) {
                continue;
            }
            int type;
            try {
                type = info.getTrackType();
            } catch (Throwable ignored) {
                continue;
            }
            if (type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                    && type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                continue;
            }
            if (exceptTrack != null && exceptTrack.trackId == i) {
                continue;
            }
            try {
                mediaPlayer.deselectTrack(i);
            } catch (Throwable ignored) {
            }
        }
    }

    public static int findPreferredSubtitleTrack(TrackInfo trackInfo) {
        if (trackInfo == null || trackInfo.getSubtitle().isEmpty()) {
            return -1;
        }
        TrackInfoBean best = findPreferredSubtitleTrackBean(trackInfo);
        return best == null ? -1 : best.trackId;
    }

    @Nullable
    public static TrackInfoBean findPreferredSubtitleTrackBean(TrackInfo trackInfo) {
        if (trackInfo == null || trackInfo.getSubtitle().isEmpty()) {
            return null;
        }
        TrackInfoBean simplifiedChinese = null;
        TrackInfoBean traditionalChinese = null;
        TrackInfoBean genericChinese = null;
        for (TrackInfoBean bean : trackInfo.getSubtitle()) {
            String key = ((bean.language == null ? "" : bean.language) + " " + (bean.name == null ? "" : bean.name)).toLowerCase(Locale.US);
            if (containsSimplifiedChinese(key)) {
                simplifiedChinese = bean;
                break;
            }
            if (containsTraditionalChinese(key) && traditionalChinese == null) {
                traditionalChinese = bean;
            }
            if (containsChinese(key) && genericChinese == null) {
                genericChinese = bean;
            }
        }
        if (simplifiedChinese != null) {
            return simplifiedChinese;
        }
        if (traditionalChinese != null) {
            return traditionalChinese;
        }
        if (genericChinese != null) {
            return genericChinese;
        }
        return trackInfo.getSubtitle().get(0);
    }

    @Nullable
    public static Subtitle findPreferredExternalSubtitle(List<Subtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            return null;
        }
        Subtitle simplifiedChinese = null;
        Subtitle traditionalChinese = null;
        Subtitle genericChinese = null;
        for (Subtitle subtitle : subtitles) {
            String key = getSubtitleSearchKey(subtitle);
            if (containsSimplifiedChinese(key)) {
                simplifiedChinese = subtitle;
                break;
            }
            if (containsTraditionalChinese(key) && traditionalChinese == null) {
                traditionalChinese = subtitle;
            }
            if (containsChinese(key) && genericChinese == null) {
                genericChinese = subtitle;
            }
        }
        if (simplifiedChinese != null) {
            return simplifiedChinese;
        }
        if (traditionalChinese != null) {
            return traditionalChinese;
        }
        if (genericChinese != null) {
            return genericChinese;
        }
        return subtitles.get(0);
    }

    public static List<Subtitle> buildExternalSubtitleList(org.json.JSONArray array) {
        List<Subtitle> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            org.json.JSONObject obj = array.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String url = obj.optString("url", "");
            if (TextUtils.isEmpty(url)) {
                continue;
            }
            String name = obj.optString("name", "字幕 " + (i + 1));
            String format = obj.optString("format", "");
            String ext = guessExtension(format, url);
            Subtitle subtitle = new Subtitle();
            subtitle.setName(name);
            subtitle.setIsZip(false);
            if (!hasKnownSubtitleExt(url)) {
                String suffix = new String((name + ext).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                subtitle.setUrl(url + "#" + java.net.URLEncoder.encode(suffix));
            } else {
                subtitle.setUrl(url);
            }
            list.add(subtitle);
        }
        return list;
    }

    public static boolean isPreferredExternalSubtitle(Subtitle subtitle) {
        if (subtitle == null) {
            return false;
        }
        String key = getSubtitleSearchKey(subtitle);
        return containsSimplifiedChinese(key) || containsTraditionalChinese(key) || containsChinese(key);
    }

    private static String getSubtitleSearchKey(Subtitle subtitle) {
        return ((subtitle == null || subtitle.getName() == null ? "" : subtitle.getName())
                + " "
                + (subtitle == null || subtitle.getUrl() == null ? "" : subtitle.getUrl())).toLowerCase(Locale.US);
    }

    public static com.github.tvbox.osc.subtitle.model.Subtitle createInternalSubtitle(String text) {
        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
        subtitle.content = text == null ? "" : text;
        subtitle.start = new Time("hh:mm:ss,ms", "00:00:00,000");
        subtitle.end = new Time("hh:mm:ss,ms", "00:00:00,001");
        return subtitle;
    }

    private static boolean hasKnownSubtitleExt(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.endsWith(".srt") || lower.endsWith(".ass") || lower.endsWith(".ssa")
                || lower.endsWith(".stl") || lower.endsWith(".scc") || lower.endsWith(".ttml")
                || lower.endsWith(".vtt");
    }

    private static String guessExtension(String format, String url) {
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.US);
        if (lowerUrl.endsWith(".vtt")) return ".vtt";
        if (lowerUrl.endsWith(".ttml") || lowerUrl.endsWith(".xml")) return ".ttml";
        if (lowerUrl.endsWith(".ass") || lowerUrl.endsWith(".ssa")) return ".ass";
        if (lowerUrl.endsWith(".scc")) return ".scc";
        if (lowerUrl.endsWith(".stl")) return ".stl";
        if (format == null) return ".srt";
        switch (format) {
            case "text/x-ssa":
                return ".ass";
            case "text/vtt":
                return ".vtt";
            case "application/ttml+xml":
                return ".ttml";
            case "application/x-subrip":
            default:
                return ".srt";
        }
    }

    private static String getFriendlyLanguage(String language, String rawInfo) {
        String text = ((language == null ? "" : language) + " " + (rawInfo == null ? "" : rawInfo)).toLowerCase(Locale.US);
        if (text.contains("yue") || text.contains("cantonese") || text.contains("粤") || text.contains("广东")) {
            return "粤语";
        }
        if (containsChinese(text)) {
            return "中文字幕";
        }
        if (text.contains("en") || text.contains("eng") || text.contains("english") || text.contains("英")) {
            return "英文字幕";
        }
        if (text.contains("ja") || text.contains("jpn") || text.contains("japanese") || text.contains("日")) {
            return "日文字幕";
        }
        if (text.contains("ko") || text.contains("kor") || text.contains("korean") || text.contains("韩")) {
            return "韩文字幕";
        }
        return "";
    }

    private static String buildDisplayName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(" ").append(number);
        if (!TextUtils.isEmpty(language)) {
            builder.append(" - ").append(language);
        }
        if (!TextUtils.isEmpty(detail)) {
            builder.append(" ").append(detail);
        }
        return builder.toString();
    }

    private static boolean containsSimplifiedChinese(String value) {
        return value.contains("简中") || value.contains("简体") || value.contains("chs")
                || value.contains("zh-hans") || value.contains("zh_cn") || value.contains("zh-cn");
    }

    private static boolean containsTraditionalChinese(String value) {
        return value.contains("繁中") || value.contains("繁体") || value.contains("cht")
                || value.contains("zh-hant") || value.contains("zh_tw") || value.contains("zh-tw")
                || value.contains("zh_hk") || value.contains("zh-hk");
    }

    private static boolean containsChinese(String value) {
        return value.contains("中文") || value.contains("中字") || value.contains("国语")
                || value.contains("zh") || value.contains("chi") || value.contains("zho")
                || value.contains("chs") || value.contains("cht") || value.contains("中");
    }
}
