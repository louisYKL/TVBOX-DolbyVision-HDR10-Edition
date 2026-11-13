package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Regression coverage for the file-backed UI -> :player handoff payload. */
public class PlaybackHandoffPayloadTest {
    @Test
    public void payloadRoundTripsLargeDetailAndResolvedJson() throws Exception {
        VodInfo vod = new VodInfo();
        vod.id = "vod-88g";
        vod.name = "Long remux";
        vod.playFlag = "webdav";
        vod.playIndex = 17;
        vod.seriesMap = new LinkedHashMap<>();
        List<VodInfo.VodSeries> episodes = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            episodes.add(new VodInfo.VodSeries("Episode " + index,
                    "https://dav.example/video/episode-" + index + ".mkv"));
        }
        vod.seriesMap.put("webdav", episodes);

        SourceBean source = new SourceBean();
        source.setKey("source-key");
        source.setName("WebDAV source");
        source.setApi("https://dav.example/api");
        source.setPlayerType(0);
        source.setExt("{\"headers\":{\"Authorization\":\"Bearer token\"}}");

        String largeDescription = repeat("detail text with subtitle and HDR metadata ", 30_000);
        String resolvedJson = "{\"flag\":\"webdav\",\"index\":17,\"url\":\"https://dav.example/video.mkv\","
                + "\"headers\":{\"Range\":\"bytes=0-25165823\"},\"detail\":\""
                + largeDescription + "\"}";

        PlaybackHandoff.Payload original = newPayload(vod, source, resolvedJson);
        PlaybackHandoff.Payload copy = roundTrip(original);

        assertNotNull(copy);
        assertNotNull(copy.vodInfo);
        assertNotNull(copy.sourceBean);
        assertEquals(vod.id, copy.vodInfo.id);
        assertEquals(vod.name, copy.vodInfo.name);
        assertEquals(vod.playFlag, copy.vodInfo.playFlag);
        assertEquals(vod.playIndex, copy.vodInfo.playIndex);
        assertEquals(vod.seriesMap.get("webdav").size(),
                copy.vodInfo.seriesMap.get("webdav").size());
        assertEquals(vod.seriesMap.get("webdav").get(17).url,
                copy.vodInfo.seriesMap.get("webdav").get(17).url);
        assertEquals(source.getKey(), copy.sourceBean.getKey());
        assertEquals(source.getApi(), copy.sourceBean.getApi());
        assertEquals(source.getExt(), copy.sourceBean.getExt());
        assertEquals(resolvedJson, copy.resolvedPlayResult);
        org.junit.Assert.assertTrue(copy.resolvedPlayResult.length() > 1_000_000);
    }

    @Test
    public void payloadPreservesNullOptionalFields() throws Exception {
        VodInfo vod = new VodInfo();
        vod.id = "minimal";
        PlaybackHandoff.Payload copy = roundTrip(newPayload(vod, null, null));
        assertEquals("minimal", copy.vodInfo.id);
        assertNull(copy.sourceBean);
        assertNull(copy.resolvedPlayResult);
    }

    private static PlaybackHandoff.Payload newPayload(VodInfo vod, SourceBean source,
                                                        String resolvedJson) throws Exception {
        Constructor<PlaybackHandoff.Payload> constructor = PlaybackHandoff.Payload.class
                .getDeclaredConstructor(VodInfo.class, SourceBean.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(vod, source, resolvedJson);
    }

    private static PlaybackHandoff.Payload roundTrip(PlaybackHandoff.Payload value)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(bytes);
        output.writeObject(value);
        output.close();
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        Object copy = input.readObject();
        input.close();
        return (PlaybackHandoff.Payload) copy;
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
