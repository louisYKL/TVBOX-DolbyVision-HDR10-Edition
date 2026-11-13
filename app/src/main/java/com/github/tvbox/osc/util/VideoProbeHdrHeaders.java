package com.github.tvbox.osc.util;

import java.util.Map;

/** Carries bounded, internal probe metadata to the actual decoder configuration. */
public final class VideoProbeHdrHeaders {
    private VideoProbeHdrHeaders() { }

    public static void apply(Map<String, String> headers, VideoStreamProbe.Result probe) {
        if (headers != null) {
            (probe == null ? VideoColorMetadata.EMPTY : probe.colorMetadata).putHeaders(headers);
        }
    }
}
