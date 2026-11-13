package com.github.tvbox.osc.webdav;

import okhttp3.HttpUrl;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WebDavConfigTest {
    @Test
    public void httpsHostnameUsesTlsDefaultPort() {
        String normalized = WebDavConfig.normalizeAddress("https://dav.example.com");
        HttpUrl url = HttpUrl.parse(normalized);

        assertEquals("https", url.scheme());
        assertEquals(443, url.port());
        assertEquals("/dav/", url.encodedPath());
    }

    @Test
    public void httpsIpv4AddressUsesTlsDefaultPort() {
        String normalized = WebDavConfig.normalizeAddress("https://192.168.0.116");
        HttpUrl url = HttpUrl.parse(normalized);

        assertEquals("192.168.0.116", url.host());
        assertEquals(443, url.port());
        assertEquals("/dav/", url.encodedPath());
    }

    @Test
    public void httpsIpv6AddressUsesTlsDefaultPort() {
        String normalized = WebDavConfig.normalizeAddress("https://[2001:db8::116]/media");
        HttpUrl url = HttpUrl.parse(normalized);

        assertEquals("2001:db8::116", url.host());
        assertEquals(443, url.port());
        assertEquals("/media/dav/", url.encodedPath());
    }

    @Test
    public void explicitHttpsPortAndHttpDefaultPortArePreserved() {
        HttpUrl https = HttpUrl.parse(WebDavConfig.normalizeAddress(
                "https://dav.example.com:8443/media/dav/"));
        HttpUrl http = HttpUrl.parse(WebDavConfig.normalizeAddress("http://192.168.0.116"));

        assertEquals(8443, https.port());
        assertEquals("/media/dav/", https.encodedPath());
        assertEquals(5244, http.port());
    }

    @Test
    public void unsupportedSchemesAreRejected() {
        assertNull(WebDavConfig.normalizeAddress("ftp://dav.example.com"));
    }
}
