package com.github.tvbox.osc.util.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Classify the response body, never the endpoint name (live.json is not a playlist). */
public final class LiveConfigContent {
    private LiveConfigContent() { }

    public static String normalize(String sourceUrl, String body) {
        if (body == null) return "";
        String content = body.replace("\ufeff", "").trim();
        if (content.isEmpty() || content.startsWith("{") || content.startsWith("[")) return content;
        LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> channels = new LinkedHashMap<>();
        TxtSubscribe.parse(channels, content);
        if (channels.isEmpty()) return content;
        JsonObject source = new JsonObject();
        source.addProperty("name", "txt_m3u");
        source.addProperty("type", 0);
        source.addProperty("url", sourceUrl);
        JsonArray sources = new JsonArray();
        sources.add(source);
        JsonObject config = new JsonObject();
        config.add("lives", sources);
        return config.toString();
    }
}
