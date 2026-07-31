package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemPlaybackUrlPolicyTest {
    @Test
    public void localSpiderMp4AndMatroskaPlaybackEndpointsStayDirect() {
        assertTrue(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(
                "http://127.0.0.1:6677/proxy/play/disk/movie.mp4"));
        assertTrue(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(
                "http://localhost:6677/proxy/play/disk/movie.mkv"));
    }

    @Test
    public void encodedLocalPlaybackPathStaysDirect() {
        assertTrue(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(
                "http://127.0.0.1:6677/proxy/play/%E5%A4%B8%E7%88%B6%E7%9B%98/movie.mp4"));
    }

    @Test
    public void appStreamProxyAndRemoteUrlsAreNotMisclassified() {
        assertFalse(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(
                "http://127.0.0.1:9978/proxy?go=stream&url=http%3A%2F%2F127.0.0.1"));
        assertFalse(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(
                "http://127.0.0.1:9978/proxy/play/movie.mp4"));
        assertFalse(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(
                "https://media.example.com/proxy/play/movie.mp4"));
        assertFalse(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl("not a url"));
    }

    @Test
    public void localSpiderRangeEndpointWithQueryStaysDirect() {
        String direct = "http://localhost:6677/proxy/play/disk/movie.mp4?token=abc%2B123";

        assertTrue(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(direct));
    }

    @Test
    public void legacy9978StreamWrapperIsNeverClassifiedAsSpiderRangeEndpoint() {
        String direct = "http://127.0.0.1:6677/proxy/play/disk/movie.mp4?token=abc";
        String legacyWrapped = "http://127.0.0.1:9978/proxy?go=stream&url="
                + "http%3A%2F%2F127.0.0.1%3A6677%2Fproxy%2Fplay%2Fdisk%2Fmovie.mp4%3Ftoken%3Dabc";

        assertTrue(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(direct));
        assertFalse(SystemPlaybackUrlPolicy.isLocalProxyPlayUrl(legacyWrapped));
    }
}
