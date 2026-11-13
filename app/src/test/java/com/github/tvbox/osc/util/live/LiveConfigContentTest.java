package com.github.tvbox.osc.util.live;

import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveConfigContentTest {
    @Test public void jsonAtLiveEndpointIsNotMistakenForTextPlaylist() {
        String body = "{\"lives\":[{\"name\":\"new\",\"type\":0,\"url\":\"https://new.test/list\"}]}";
        assertEquals(body, LiveConfigContent.normalize("https://new.test/live.json", body));
    }

    @Test public void extensionlessM3uWrapsTheCurrentAddressAndEscapesItSafely() {
        String url = "https://new.test/download?key=\"token\"&x=1";
        String body = "\ufeff#EXTM3U\n#EXTINF:-1 group-title=\"TV\",News\nhttps://new.test/channel.m3u8\n";
        String normalized = LiveConfigContent.normalize(url, body);
        assertEquals(url, JsonParser.parseString(normalized).getAsJsonObject()
                .getAsJsonArray("lives").get(0).getAsJsonObject().get("url").getAsString());
    }

    @Test public void txtPlaylistDoesNotRequireAnExtension() {
        String normalized = LiveConfigContent.normalize("https://new.test/list",
                "News,#genre#\nChannel,http://new.test/channel\n");
        assertTrue(JsonParser.parseString(normalized).getAsJsonObject().has("lives"));
    }

    @Test public void errorPagesAndEmptyResponsesDoNotInventAPlayableSource() {
        assertEquals("<html>error</html>", LiveConfigContent.normalize("http://host/live", "<html>error</html>"));
        assertEquals("", LiveConfigContent.normalize("http://host/live", null));
    }
}
