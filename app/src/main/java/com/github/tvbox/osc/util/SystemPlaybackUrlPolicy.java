package com.github.tvbox.osc.util;

import java.net.URI;

/** Pure URL classification rules for the system-codec playback route. */
public final class SystemPlaybackUrlPolicy {
    private SystemPlaybackUrlPolicy() {
    }

    /**
     * Identifies an existing loopback playback proxy endpoint. It must stay direct: sending it
     * through another local stream proxy duplicates Range ownership and can stall large VODs.
     */
    public static boolean isLocalProxyPlayUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            return ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && uri.getPort() == 6677
                    && path != null
                    && path.contains("/proxy/play/");
        } catch (Exception ignored) {
            return false;
        }
    }
}
