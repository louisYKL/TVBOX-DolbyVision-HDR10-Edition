package com.github.tvbox.osc.player;

import android.media.MediaPlayer;
import android.os.Build;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import xyz.doikki.videoplayer.player.AndroidMediaPlayer;

public final class SystemPlayerTrackManager {
    private static final Map<String, String> ISO_LANGUAGE_NAMES = buildIsoLanguageNames();

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
        int subtitleTrackCount = countSubtitleTracks(trackInfos);
        boolean singleSystemSubtitleTrack = subtitleTrackCount == 1;
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
                String rawLanguage = safeTrackLanguage(info);
                String rawInfo = safeTrackInfoDump(info);
                boolean subtitleTrack = type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                        || type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT;
                boolean unreliableSystemSubtitleMetadata = subtitleTrack
                        && singleSystemSubtitleTrack
                        && isLikelyMisleadingSingleSystemSubtitle(rawLanguage, rawInfo);
                String language = unreliableSystemSubtitleMetadata
                        ? ""
                        : getFriendlyLanguage(rawLanguage, rawInfo);
                TrackInfoBean bean = new TrackInfoBean();
                bean.trackId = i;
                bean.index = i;
                bean.language = language;
                bean.rawLanguage = rawLanguage;
                bean.rawTitle = rawInfo;
                bean.rawCodec = rawInfo;
                bean.rawMimeType = rawInfo;
                bean.renderId = type;
                bean.unreliableMetadata = unreliableSystemSubtitleMetadata;
                bean.autoSelectBlocked = unreliableSystemSubtitleMetadata;
                String displayLanguage = unreliableSystemSubtitleMetadata ? "" : language;
                bean.name = buildDisplayName(type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO ? "音轨" : "字幕",
                        type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO ? data.getAudio().size() + 1 : data.getSubtitle().size() + 1,
                        displayLanguage, buildTrackDetail(rawInfo));
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

    public static TrackInfo mergeSubtitleMetadata(@Nullable TrackInfo baseTrackInfo,
                                                  @Nullable List<TrackInfoBean> metadataTracks) {
        TrackInfo merged = new TrackInfo();
        int systemSubtitleCount = 0;
        if (baseTrackInfo != null) {
            for (TrackInfoBean audio : baseTrackInfo.getAudio()) {
                if (audio != null) {
                    merged.addAudio(audio);
                }
            }
        }
        LinkedHashMap<String, TrackInfoBean> ordered = new LinkedHashMap<>();
        if (baseTrackInfo != null) {
            for (TrackInfoBean subtitle : baseTrackInfo.getSubtitle()) {
                if (subtitle == null) {
                    continue;
                }
                systemSubtitleCount++;
                subtitle.metadataOnly = false;
                ordered.put(buildSubtitleIdentityKey(subtitle), subtitle);
            }
        }
        int reliableSystemSubtitleCount = countReliableSystemSubtitleTracks(baseTrackInfo);
        boolean preferSystemSubtitleList = systemSubtitleCount > 0;
        if (metadataTracks != null) {
            for (TrackInfoBean subtitle : metadataTracks) {
                if (subtitle == null) {
                    continue;
                }
                subtitle.metadataOnly = true;
                String key = buildSubtitleIdentityKey(subtitle);
                TrackInfoBean existing = ordered.get(key);
                if (existing == null) {
                    // Vendor MediaPlayer implementations often expose only the first
                    // subtitle track. Keep probe-discovered tracks that have a real
                    // extractor index so the user can map/select every language, while
                    // suppressing a metadata duplicate of an already exposed language.
                    if (preferSystemSubtitleList
                            && hasEquivalentRuntimeSubtitle(ordered.values(), subtitle)) {
                        continue;
                    }
                    // Metadata may describe the list, but it is not a native-selectable track.
                    // Only expose it when the running player has no subtitle track at all.
                    if (subtitle.extractorTrackIndex >= 0 || isReliableMetadataSubtitle(subtitle)) {
                        ordered.put(key, subtitle);
                    }
                } else {
                    hydrateSubtitleMetadata(existing, subtitle);
                }
            }
        }
        boolean allowUnreliableSystemFallback = systemSubtitleCount > 0
                && reliableSystemSubtitleCount == 0;
        for (TrackInfoBean subtitle : ordered.values()) {
            if (subtitle != null
                    && !subtitle.metadataOnly
                    && subtitle.unreliableMetadata
                    && allowUnreliableSystemFallback) {
                subtitle.autoSelectBlocked = false;
            }
            if (subtitle != null
                    && !subtitle.metadataOnly
                    && subtitle.trackId < 0) {
                continue;
            }
            merged.addSubtitle(subtitle);
        }
        return merged;
    }

    private static boolean hasEquivalentRuntimeSubtitle(
            Iterable<TrackInfoBean> runtimeTracks, @Nullable TrackInfoBean candidate) {
        if (runtimeTracks == null || candidate == null) {
            return false;
        }
        String candidateLanguage = safeLower(firstNonEmpty(candidate.language, candidate.rawLanguage));
        String candidateRawLanguage = normalizeLanguageCode(candidate.rawLanguage);
        String candidateTitle = safeLower(candidate.rawTitle);
        for (TrackInfoBean runtime : runtimeTracks) {
            if (runtime == null || runtime.metadataOnly) {
                continue;
            }
            String runtimeLanguage = safeLower(firstNonEmpty(runtime.language, runtime.rawLanguage));
            String runtimeRawLanguage = normalizeLanguageCode(runtime.rawLanguage);
            if (!isEmpty(candidateLanguage) && candidateLanguage.equals(runtimeLanguage)) {
                return true;
            }
            if (!isEmpty(candidateRawLanguage) && candidateRawLanguage.equals(runtimeRawLanguage)) {
                return true;
            }
            if (!isEmpty(candidateTitle) && candidateTitle.equals(safeLower(runtime.rawTitle))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeLanguageCode(String value) {
        String normalized = safeLower(value);
        if (normalized.isEmpty()) {
            return "";
        }
        int separator = normalized.indexOf('-');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        separator = normalized.indexOf('_');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        if ("zho".equals(normalized) || "cmn".equals(normalized)) {
            return "zh";
        }
        if ("chi".equals(normalized)) {
            return "zh";
        }
        if ("eng".equals(normalized)) {
            return "en";
        }
        if ("jpn".equals(normalized)) {
            return "ja";
        }
        if ("kor".equals(normalized)) {
            return "ko";
        }
        return normalized;
    }

    public static void selectTrack(AndroidMediaPlayer mediaPlayer, @Nullable TrackInfoBean track) {
        if (mediaPlayer == null || track == null) {
            return;
        }
        // MediaPlayer replaces the selected track within the same type. Clearing every
        // subtitle first multiplies synchronous vendor calls and can block completion/seek
        // callbacks exactly while the decoder is transitioning.
        mediaPlayer.selectTrack(track.trackId);
    }

    public static boolean usesNativeSubtitleRenderer(@Nullable TrackInfoBean track) {
        return track != null
                && !track.metadataOnly
                && track.renderId == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE;
    }

    public static boolean isMetadataOnlySubtitleTrack(@Nullable TrackInfoBean track) {
        return track != null && track.metadataOnly;
    }

    public static boolean isReliableMetadataSubtitle(@Nullable TrackInfoBean track) {
        if (track == null || !track.metadataOnly || isBitmapSubtitleTrack(track)) {
            return false;
        }
        if (track.extractorTrackIndex >= 0) {
            return true;
        }
        String language = safeLower(firstNonEmpty(track.rawLanguage, track.language));
        String title = safeLower(track.rawTitle);
        String codec = safeLower(firstNonEmpty(track.rawCodec, track.rawMimeType));
        boolean explicitLanguage = containsExplicitChineseMarker(language)
                || containsExplicitChineseMarker(title)
                || containsSimplifiedChinese(title)
                || containsTraditionalChinese(title);
        boolean textCodec = codec.contains("subrip")
                || codec.contains("srt")
                || codec.contains("ass")
                || codec.contains("ssa")
                || codec.contains("webvtt")
                || codec.contains("vtt")
                || codec.contains("text");
        return explicitLanguage && textCodec;
    }

    public static void clearSubtitleSelections(AndroidMediaPlayer mediaPlayer, @Nullable TrackInfoBean exceptTrack) {
        if (mediaPlayer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            deselectSelectedSubtitleType(mediaPlayer,
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE, exceptTrack);
            deselectSelectedSubtitleType(mediaPlayer,
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT, exceptTrack);
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

    private static void deselectSelectedSubtitleType(AndroidMediaPlayer mediaPlayer,
                                                     int trackType,
                                                     @Nullable TrackInfoBean exceptTrack) {
        int selected = mediaPlayer.getSelectedTrack(trackType);
        if (selected >= 0 && (exceptTrack == null || exceptTrack.trackId != selected)) {
            mediaPlayer.deselectTrack(selected);
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
        boolean hasReliableCandidate = false;
        for (TrackInfoBean bean : trackInfo.getSubtitle()) {
            if (bean != null && !bean.autoSelectBlocked) {
                hasReliableCandidate = true;
                break;
            }
        }
        if (!hasReliableCandidate) {
            return null;
        }
        TrackInfoBean best = null;
        int bestScore = Integer.MIN_VALUE;
        for (TrackInfoBean bean : trackInfo.getSubtitle()) {
            if (bean == null || bean.autoSelectBlocked) {
                continue;
            }
            int score = scoreSubtitleTrack(bean);
            if (score > bestScore) {
                bestScore = score;
                best = bean;
            }
        }
        if (best != null && bestScore > Integer.MIN_VALUE / 2) {
            return best;
        }
        return null;
    }

    @Nullable
    public static Subtitle findPreferredExternalSubtitle(List<Subtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            return null;
        }
        Subtitle best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Subtitle subtitle : subtitles) {
            int score = scoreExternalSubtitle(subtitle);
            if (score > bestScore) {
                bestScore = score;
                best = subtitle;
            }
        }
        if (best != null && bestScore > Integer.MIN_VALUE / 2) {
            return best;
        }
        return subtitles.get(0);
    }

    @Nullable
    public static Subtitle findSelectedExternalSubtitle(List<Subtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            return null;
        }
        for (Subtitle subtitle : subtitles) {
            if (subtitle != null && subtitle.isSelected() && !isEmpty(subtitle.getUrl())) {
                return subtitle;
            }
        }
        return null;
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
            if (isEmpty(url)) {
                continue;
            }
            String name = obj.optString("name", "字幕 " + (i + 1));
            String format = obj.optString("format", "");
            String ext = guessExtension(format, url);
            Subtitle subtitle = new Subtitle();
            subtitle.setName(name);
            subtitle.setIsZip(false);
            subtitle.setSelected(obj.optBoolean("selected", false));
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
        return scoreExternalSubtitle(subtitle) > 0;
    }

    private static String getSubtitleSearchKey(Subtitle subtitle) {
        return ((subtitle == null || subtitle.getName() == null ? "" : subtitle.getName())
                + " "
                + (subtitle == null || subtitle.getUrl() == null ? "" : subtitle.getUrl())).toLowerCase(Locale.US);
    }

    public static com.github.tvbox.osc.subtitle.model.Subtitle createInternalSubtitle(String text) {
        return createInternalSubtitle((CharSequence) text);
    }

    public static com.github.tvbox.osc.subtitle.model.Subtitle createInternalSubtitle(CharSequence text) {
        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
        subtitle.styledContent = text;
        subtitle.content = text == null ? "" : text.toString();
        if (text instanceof android.text.Spanned) {
            android.text.Spanned spanned = (android.text.Spanned) text;
            subtitle.hasSourceStyle = spanned.length() > 0
                    && spanned.getSpans(0, spanned.length(), Object.class).length > 0;
        }
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

    private static void hydrateSubtitleMetadata(TrackInfoBean target, TrackInfoBean metadata) {
        if (target == null || metadata == null) {
            return;
        }
        if (target.extractorTrackIndex < 0) {
            target.extractorTrackIndex = metadata.extractorTrackIndex;
        }
        if (isEmpty(target.rawLanguage)) {
            target.rawLanguage = metadata.rawLanguage;
        }
        if (isEmpty(target.rawTitle)) {
            target.rawTitle = metadata.rawTitle;
        }
        if (isEmpty(target.rawCodec)) {
            target.rawCodec = metadata.rawCodec;
        }
        if (isEmpty(target.rawMimeType)) {
            target.rawMimeType = metadata.rawMimeType;
        }
        if (isEmpty(target.language)) {
            target.language = metadata.language;
        }
        if (isEmpty(target.name)) {
            target.name = metadata.name;
        }
        if (isEmpty(target.mappedSubtitlePath)) {
            target.mappedSubtitlePath = metadata.mappedSubtitlePath;
        }
        target.unreliableMetadata = target.unreliableMetadata && metadata.unreliableMetadata;
    }

    private static String buildSubtitleIdentityKey(@Nullable TrackInfoBean bean) {
        if (bean == null) {
            return "";
        }
        String language = safeLower(firstNonEmpty(bean.rawLanguage, bean.language));
        String title = safeLower(bean.rawTitle);
        String codec = safeLower(firstNonEmpty(bean.rawCodec, bean.rawMimeType));
        if (bean.metadataOnly) {
            return "meta|" + bean.trackId + "|" + bean.extractorTrackIndex + "|"
                    + language + "|" + title + "|" + codec;
        }
        return language + "|" + title + "|" + codec;
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

    public static String getFriendlyLanguage(String language, String rawInfo) {
        String languageText = safeLower(language);
        String infoText = safeLower(rawInfo);
        String text = languageText + " " + infoText;
        if (containsToken(languageText, "yue")
                || infoText.contains("cantonese") || infoText.contains("粤") || infoText.contains("广东")) {
            return "粤语";
        }
        if (containsChinese(languageText) || containsExplicitChineseMarker(infoText)) {
            return "中文字幕";
        }
        if (containsLanguageCode(languageText, "en", "eng")
                || containsLanguageName(infoText, "english", "英文", "英语")) {
            return "英文字幕";
        }
        if (containsLanguageCode(languageText, "ja", "jpn")
                || containsLanguageName(infoText, "japanese", "日文", "日语")) {
            return "日文字幕";
        }
        if (containsLanguageCode(languageText, "ko", "kor")
                || containsLanguageName(infoText, "korean", "韩文", "韩语")) {
            return "韩文字幕";
        }
        if (containsLanguageCode(languageText, "fr", "fra", "fre")
                || containsLanguageName(infoText, "french", "法文", "法语")) {
            return "法文字幕";
        }
        if (containsLanguageCode(languageText, "es", "spa")
                || containsLanguageName(infoText, "spanish", "西班牙文", "西班牙语")) {
            return "西班牙文字幕";
        }
        if (containsLanguageCode(languageText, "de", "deu", "ger")
                || containsLanguageName(infoText, "german", "德文", "德语")) {
            return "德文字幕";
        }
        if (containsLanguageCode(languageText, "it", "ita")
                || containsLanguageName(infoText, "italian", "意大利文", "意大利语")) {
            return "意大利文字幕";
        }
        if (containsLanguageCode(languageText, "pt", "por")
                || containsLanguageName(infoText, "portuguese", "葡萄牙文", "葡萄牙语")) {
            return "葡萄牙文字幕";
        }
        if (containsLanguageCode(languageText, "ru", "rus")
                || containsLanguageName(infoText, "russian", "俄文", "俄语")) {
            return "俄文字幕";
        }
        if (containsLanguageCode(languageText, "ar", "ara")
                || containsLanguageName(infoText, "arabic", "阿拉伯文", "阿拉伯语")) {
            return "阿拉伯文字幕";
        }
        String localizedLanguage = localizeIsoLanguage(languageText);
        if (!isEmpty(localizedLanguage)) {
            return localizedLanguage + "字幕";
        }
        return "";
    }

    private static Map<String, String> buildIsoLanguageNames() {
        HashMap<String, String> names = new HashMap<>();
        for (String code : Locale.getISOLanguages()) {
            Locale locale = new Locale(code);
            String displayName = locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
            if (!isEmpty(displayName) && !displayName.equalsIgnoreCase(code)) {
                names.put(code.toLowerCase(Locale.US), displayName);
            }
        }
        for (Locale locale : Locale.getAvailableLocales()) {
            try {
                String code = locale.getISO3Language();
                String displayName = locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
                if (!isEmpty(code) && !isEmpty(displayName)) {
                    names.putIfAbsent(code.toLowerCase(Locale.US), displayName);
                }
            } catch (java.util.MissingResourceException ignored) {
            }
        }
        names.put("chi", "中文");
        names.put("zho", "中文");
        names.put("cmn", "中文");
        names.put("yue", "粤语");
        return Collections.unmodifiableMap(names);
    }

    @Nullable
    private static String localizeIsoLanguage(String rawLanguage) {
        if (isEmpty(rawLanguage)) {
            return "";
        }
        String normalized = rawLanguage.trim().toLowerCase(Locale.US);
        int separator = normalized.indexOf('-');
        if (separator < 0) {
            separator = normalized.indexOf('_');
        }
        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }
        if (!(normalized.length() == 2 || normalized.length() == 3)) {
            return "";
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch < 'a' || ch > 'z') {
                return "";
            }
        }
        String displayName = ISO_LANGUAGE_NAMES.get(normalized);
        if (isEmpty(displayName) || "und".equals(normalized) || "mul".equals(normalized)
                || "zxx".equals(normalized)) {
            return "";
        }
        return displayName;
    }

    /**
     * Returns only subtitle tracks whose language can be identified from the
     * track metadata. Codec names, track numbers, and an `und` language tag
     * are deliberately not enough to expose a choice to the user.
     */
    public static List<TrackInfoBean> filterKnownSubtitleTracks(@Nullable List<TrackInfoBean> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<TrackInfoBean> known = new ArrayList<>();
        for (TrackInfoBean track : tracks) {
            if (isKnownSubtitleTrack(track)) {
                known.add(track);
            }
        }
        return known.isEmpty() ? Collections.emptyList() : known;
    }

    public static boolean isKnownSubtitleTrack(@Nullable TrackInfoBean track) {
        if (track == null) {
            return false;
        }
        String language = firstNonEmpty(track.rawLanguage, track.language);
        String info = firstNonEmpty(track.rawTitle, track.rawCodec, track.rawMimeType);
        return !isEmpty(getFriendlyLanguage(language, info));
    }

    private static boolean containsLanguageCode(String value, String... codes) {
        if (isEmpty(value) || codes == null) {
            return false;
        }
        for (String code : codes) {
            if (containsToken(value, code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLanguageName(String value, String... names) {
        if (isEmpty(value) || names == null) {
            return false;
        }
        for (String name : names) {
            if (!isEmpty(name) && value.contains(name.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    public static String buildTrackDetail(String rawInfo) {
        if (isEmpty(rawInfo)) {
            return "";
        }
        String normalized = rawInfo.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 42 ? normalized.substring(0, 42) : normalized;
    }

    public static String buildDisplayName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(" ").append(number);
        if (!isEmpty(language)) {
            builder.append(" - ").append(language);
        }
        if (!isEmpty(detail)) {
            builder.append(" ").append(detail);
        }
        return builder.toString();
    }

    private static boolean containsSimplifiedChinese(String value) {
        String lower = safeLower(value);
        String compact = compactLatin(lower);
        return lower.contains("简中") || lower.contains("简体")
                || containsToken(lower, "chs")
                || lower.contains("zh-hans") || lower.contains("zh_cn") || lower.contains("zh-cn")
                || lower.contains("cmn-hans") || lower.contains("zh-hans-cn")
                || compact.contains("zhhans")
                || compact.contains("zhcn")
                || compact.contains("cmnhans")
                || compact.contains("simplifiedchinese")
                || compact.contains("chinesesimplified")
                || lower.contains("simplified");
    }

    private static boolean containsTraditionalChinese(String value) {
        String lower = safeLower(value);
        String compact = compactLatin(lower);
        return lower.contains("繁中") || lower.contains("繁体")
                || containsToken(lower, "cht")
                || lower.contains("zh-hant") || lower.contains("zh_tw") || lower.contains("zh-tw")
                || lower.contains("zh_hk") || lower.contains("zh-hk")
                || lower.contains("cmn-hant") || lower.contains("zh-hant-tw")
                || compact.contains("zhhant")
                || compact.contains("zhtw")
                || compact.contains("zhhk")
                || compact.contains("cmnhant")
                || compact.contains("traditionalchinese")
                || compact.contains("chinesetraditional")
                || lower.contains("traditional");
    }

    private static boolean containsChinese(String value) {
        if (isEmpty(value)) {
            return false;
        }
        String lower = safeLower(value);
        if (containsSimplifiedChinese(lower) || containsTraditionalChinese(lower)) {
            return true;
        }
        return lower.contains("中文")
                || lower.contains("中字")
                || lower.contains("国语")
                || lower.contains("华语")
                || lower.contains("汉语")
                || containsToken(lower, "chinese")
                || containsToken(lower, "mandarin")
                || containsToken(lower, "cmn")
                || containsToken(lower, "zho")
                || containsToken(lower, "chi")
                || containsToken(lower, "zh");
    }

    private static int scoreSubtitleTrack(@Nullable TrackInfoBean bean) {
        if (bean == null) {
            return Integer.MIN_VALUE;
        }
        if (bean.autoSelectBlocked) {
            return Integer.MIN_VALUE / 4;
        }
        String key = buildTrackSearchKey(bean);
        boolean explicitChinese = containsExplicitChineseMarker(key);
        boolean genericChineseOnly = !explicitChinese && containsGenericChineseMarker(key);
        int score = scoreChinesePreference(key);
        if (isEmpty(bean.rawLanguage) && isEmpty(bean.rawTitle) && isEmpty(bean.name)) {
            score -= 40;
        }
        if (looksLikeNonChinese(key)) {
            score -= genericChineseOnly ? 260 : 180;
        }
        if (!containsChinese(key)) {
            score -= 20;
        } else if (genericChineseOnly) {
            // A bare chi/zho/zh/cmn tag is often a container-level or firmware
            // guess. Prefer explicit Simplified/Traditional/Chinese titles
            // when the player exposes the full track list.
            score -= 90;
        }
        if (isTextSubtitleTrack(bean)) {
            score += 18;
        } else if (isBitmapSubtitleTrack(bean)) {
            score -= 8;
        }
        if (bean.metadataOnly) {
            if (bean.extractorTrackIndex >= 0) {
                score += 36;
            } else {
                score -= 120;
            }
        }
        if (bean.selected) {
            score += 6;
        }
        return score;
    }

    private static int scoreExternalSubtitle(@Nullable Subtitle subtitle) {
        String key = getSubtitleSearchKey(subtitle);
        int score = scoreChinesePreference(key);
        if (looksLikeNonChinese(key)) {
            score -= 160;
        }
        if (!containsChinese(key)) {
            score -= 20;
        }
        return score;
    }

    private static int scoreChinesePreference(String key) {
        int score = 0;
        if (containsSimplifiedChinese(key)) {
            score += 320;
        }
        if (containsTraditionalChinese(key)) {
            score += 240;
        }
        if (containsChinese(key)) {
            score += 120;
        }
        if (key.contains("default") || key.contains("默认")) {
            score += 8;
        }
        if (key.contains("forced") || key.contains("强制")) {
            score -= 20;
        }
        return score;
    }

    private static boolean containsExplicitChineseMarker(String value) {
        if (isEmpty(value)) {
            return false;
        }
        String lower = safeLower(value);
        String compact = compactLatin(lower);
        return containsSimplifiedChinese(lower)
                || containsTraditionalChinese(lower)
                || lower.contains("中文")
                || lower.contains("中字")
                || lower.contains("国语")
                || lower.contains("华语")
                || containsToken(lower, "chinese")
                || containsToken(lower, "mandarin")
                || compact.contains("chinesesub")
                || compact.contains("zhongwen")
                || compact.contains("zhongzi");
    }

    private static boolean containsGenericChineseMarker(String value) {
        if (isEmpty(value)) {
            return false;
        }
        String lower = safeLower(value);
        return lower.contains("汉语")
                || containsToken(lower, "cmn")
                || containsToken(lower, "zho")
                || containsToken(lower, "chi")
                || containsToken(lower, "zh");
    }

    private static boolean looksLikeNonChinese(String key) {
        if (isEmpty(key)) {
            return false;
        }
        return key.contains("english") || key.contains(" eng") || key.startsWith("eng")
                || key.contains("jpn") || key.contains("japanese") || key.contains("日文")
                || key.contains("kor") || key.contains("korean") || key.contains("韩文")
                || key.contains("french") || key.contains("fr ")
                || key.contains("spanish") || key.contains("spa ");
    }

    private static String buildTrackSearchKey(@Nullable TrackInfoBean bean) {
        if (bean == null) {
            return "";
        }
        return firstNonEmpty(bean.rawLanguage, "") + " "
                + firstNonEmpty(bean.rawTitle, "") + " "
                + firstNonEmpty(bean.rawCodec, "") + " "
                + firstNonEmpty(bean.rawMimeType, "") + " "
                + firstNonEmpty(bean.language, "") + " "
                + firstNonEmpty(bean.name, "");
    }

    public static boolean isChineseSubtitleTrack(@Nullable TrackInfoBean bean) {
        return containsChinese(buildTrackSearchKey(bean));
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static boolean containsToken(String value, String token) {
        if (isEmpty(value) || isEmpty(token)) {
            return false;
        }
        String normalized = normalizeTokens(value);
        return (" " + normalized + " ").contains(" " + token.toLowerCase(Locale.US) + " ");
    }

    private static String normalizeTokens(String value) {
        String lower = safeLower(value);
        StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            } else {
                builder.append(' ');
            }
        }
        return builder.toString().trim().replaceAll("\\s+", " ");
    }

    private static String compactLatin(String value) {
        if (isEmpty(value)) {
            return "";
        }
        String lower = safeLower(value);
        StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    public static boolean isBitmapSubtitleTrack(@Nullable TrackInfoBean track) {
        if (track == null) {
            return false;
        }
        String key = buildTrackSearchKey(track).toLowerCase(Locale.US);
        return key.contains("pgs")
                || key.contains("hdmv_pgs_subtitle")
                || key.contains("dvd_subtitle")
                || key.contains("dvb_subtitle")
                || key.contains("vobsub")
                || key.contains("subpicture")
                || key.contains("xsub")
                || key.contains("sup");
    }

    public static boolean isTextSubtitleTrack(@Nullable TrackInfoBean track) {
        return track != null && !isBitmapSubtitleTrack(track);
    }

    private static String safeTrackLanguage(MediaPlayer.TrackInfo info) {
        try {
            return firstNonEmpty(info == null ? null : info.getLanguage(), "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeTrackInfoDump(MediaPlayer.TrackInfo info) {
        if (info == null) {
            return "";
        }
        try {
            return firstNonEmpty(info.toString(), "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isEmpty(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static int countSubtitleTracks(@Nullable MediaPlayer.TrackInfo[] trackInfos) {
        if (trackInfos == null || trackInfos.length == 0) {
            return 0;
        }
        int count = 0;
        for (MediaPlayer.TrackInfo info : trackInfos) {
            if (info == null) {
                continue;
            }
            try {
                int type = info.getTrackType();
                if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                        || type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    count++;
                }
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    private static boolean isLikelyMisleadingSingleSystemSubtitle(String rawLanguage, String rawInfo) {
        String tokenText = normalizeTokens(firstNonEmpty(rawLanguage, "") + " " + firstNonEmpty(rawInfo, ""));
        if (isEmpty(tokenText)) {
            return true;
        }
        return containsToken(tokenText, "chi")
                || containsToken(tokenText, "zho")
                || containsToken(tokenText, "cmn")
                || containsToken(tokenText, "zh")
                || tokenText.contains(" chinese ")
                || tokenText.contains(" mandarin ")
                || tokenText.contains(" han ")
                || tokenText.contains(" hans ")
                || tokenText.contains(" hant ")
                || tokenText.contains(" hanyu ");
    }

    private static int countReliableSystemSubtitleTracks(@Nullable TrackInfo trackInfo) {
        if (trackInfo == null || trackInfo.getSubtitle().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TrackInfoBean bean : trackInfo.getSubtitle()) {
            if (bean == null || bean.metadataOnly) {
                continue;
            }
            if (!isLikelyMisleadingSingleSystemSubtitle(bean.rawLanguage, bean.rawTitle)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}
