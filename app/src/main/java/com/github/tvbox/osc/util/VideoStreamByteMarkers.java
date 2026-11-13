package com.github.tvbox.osc.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Allocation-bounded, single-pass ASCII metadata scan over a binary container prefix. */
final class VideoStreamByteMarkers {
    static final int DOLBY_VISION = 1;
    static final int HEVC = 1 << 1;
    static final int MATROSKA = 1 << 2;
    static final int HDR10 = 1 << 3;
    static final int HDR10_PLUS = 1 << 4;
    private static final int DOVI = 1 << 5;
    private static final int PROFILE_BASE_BIT = 8;
    private static final int PROFILE_COUNT = 6;
    private static final int PROFILE_5 = 1 << 14;
    private static final int PROFILE_7 = 1 << 15;
    private static final int PROFILE_8 = 1 << 16;
    private static final int ALPHABET_SIZE = 44;
    private static final Matcher MATCHER = buildMatcher();

    private VideoStreamByteMarkers() { }

    static boolean contains(byte[] data, int length, int marker) {
        throwIfCancelled();
        int limit = data == null ? 0 : Math.max(0, Math.min(length, data.length));
        if ((marker & MATROSKA) != 0 && limit >= 4 && (data[0] & 255) == 0x1a
                && data[1] == 0x45 && (data[2] & 255) == 0xdf && (data[3] & 255) == 0xa3) {
            return true;
        }
        int state = 0;
        for (int offset = 0; offset < limit; offset++) {
            if ((offset & 0x3fff) == 0) throwIfCancelled();
            int symbol = symbol(data[offset] & 255);
            state = symbol < 0 ? 0 : MATCHER.transitions[state][symbol];
            if ((MATCHER.outputs[state] & marker) != 0) return true;
        }
        return false;
    }

    static Scan scan(byte[] data, int length) {
        throwIfCancelled();
        int limit = data == null ? 0 : Math.max(0, Math.min(length, data.length));
        int flags = limit >= 4 && (data[0] & 255) == 0x1a && data[1] == 0x45
                && (data[2] & 255) == 0xdf && (data[3] & 255) == 0xa3 ? MATROSKA : 0;
        int state = 0;
        int[] profiles = new int[PROFILE_COUNT];
        Arrays.fill(profiles, -1);
        for (int offset = 0; offset < limit; offset++) {
            if ((offset & 0x3fff) == 0) {
                throwIfCancelled();
            }
            int symbol = symbol(data[offset] & 255);
            state = symbol < 0 ? 0 : MATCHER.transitions[state][symbol];
            int found = MATCHER.outputs[state];
            flags |= found;
            if ((found >>> PROFILE_BASE_BIT & 0x3f) != 0) {
                for (int profile = 0; profile < PROFILE_COUNT; profile++) {
                    if ((found & (1 << (PROFILE_BASE_BIT + profile))) != 0 && profiles[profile] < 0) {
                        profiles[profile] = firstIntAfter(data, offset + 1, limit);
                    }
                }
            }
        }
        int profile = -1;
        for (int index = 0; index < profiles.length; index++) {
            int value = profiles[index];
            if (index == 3 && (flags & DOVI) == 0) {
                continue;
            }
            if (index >= 4 && value > 12) {
                value /= 10;
            }
            if (value > 0) {
                profile = value;
                break;
            }
        }
        if (profile < 0) {
            profile = (flags & PROFILE_5) != 0 ? 5
                    : (flags & PROFILE_7) != 0 ? 7 : (flags & PROFILE_8) != 0 ? 8 : -1;
        }
        return new Scan(flags, profile);
    }

    static void throwIfCancelled() {
        // isInterrupted() deliberately preserves the flag for outer probe catch/finally paths.
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("video probe cancelled");
        }
    }

    private static int firstIntAfter(byte[] data, int start, int limit) {
        int end = Math.min(limit, start + 64);
        int value = -1;
        for (int offset = start; offset < end; offset++) {
            int ch = data[offset] & 255;
            if (ch >= '0' && ch <= '9') {
                if (value < 0) value = 0;
                if (value > (Integer.MAX_VALUE - (ch - '0')) / 10) return -1;
                value = value * 10 + ch - '0';
            } else if (value >= 0) {
                return value;
            } else if (ch != ' ' && ch != ':' && ch != '=' && ch != '_'
                    && ch != '-' && ch != '.') {
                return -1;
            }
        }
        return value;
    }

    private static int symbol(int ch) {
        if (ch >= 'A' && ch <= 'Z') ch += 'a' - 'A';
        if (ch >= 'a' && ch <= 'z') return ch - 'a';
        if (ch >= '0' && ch <= '9') return 26 + ch - '0';
        switch (ch) {
            case '.': return 36;
            case '+': return 37;
            case '_': return 38;
            case '-': return 39;
            case '=': return 40;
            case ':': return 41;
            case '/': return 42;
            case ' ': return 43;
            default: return -1;
        }
    }

    private static Matcher buildMatcher() {
        Builder builder = new Builder();
        builder.add(DOLBY_VISION, "video/dolby-vision", "dovi configuration", "dolby vision",
                "dolby_vision", "dolby-vision", "dolbyvision", "dv profile", "dv_profile",
                "dv-profile", "dvhe", "dvh1", "dvc1", "dvcc", "dvvc", "dvv1");
        builder.add(HEVC, "video/hevc", "hev1", "hvc1", "hevc", "h265", "v_mpegh/iso/hevc");
        builder.add(MATROSKA, ".mkv", "matroska", "webm");
        builder.add(HDR10, "hdr10", "smpte2084", "smpte 2084", "arib-std-b67",
                "color-transfer=6", "color-transfer=7", "color_transfer=6", "color_transfer=7",
                "colortransfer=6", "colortransfer=7", "transfer=6", "transfer=7",
                "transfer:6", "transfer:7", "transfer=st2084", "transfer=smpte2084",
                "transfer=smpte-st-2084", "transfer=pq", "transfer=hlg", "transfer:hlg",
                "video-range=pq", "video-range=hlg", "video_range=pq", "video_range=hlg",
                "video range=pq", "video range=hlg", "smpte-st-2084", "hdr-static-info",
                "hdr vivid", "hdr-vivid", "hdrvivid", "cuva");
        builder.add(HDR10_PLUS, "hdr10+", "hdr10plus", "hdr10_plus", "hdr10-plus",
                "smpte2094", "smpte 2094");
        builder.add(DOVI, "dovi");
        String[] profileMarkers = {"dv_profile", "dv profile", "dv-profile", "profile=", "dvhe.", "dvh1."};
        for (int index = 0; index < profileMarkers.length; index++) {
            builder.add(1 << (PROFILE_BASE_BIT + index), profileMarkers[index]);
        }
        builder.add(PROFILE_5, "profile 5", "profile: 5");
        builder.add(PROFILE_7, "profile 7", "profile: 7");
        builder.add(PROFILE_8, "profile 8", "profile: 8");
        return builder.build();
    }

    static final class Scan {
        final int flags;
        final int dolbyVisionProfile;

        private Scan(int flags, int profile) {
            this.flags = flags;
            this.dolbyVisionProfile = profile;
        }

        boolean has(int marker) {
            return (flags & marker) != 0;
        }
    }

    private static final class Matcher {
        final char[][] transitions;
        final int[] outputs;

        Matcher(char[][] transitions, int[] outputs) {
            this.transitions = transitions;
            this.outputs = outputs;
        }
    }

    private static final class Builder {
        final ArrayList<char[]> transitions = new ArrayList<>();
        final ArrayList<Integer> outputs = new ArrayList<>();

        Builder() {
            addState();
        }

        private int addState() {
            transitions.add(new char[ALPHABET_SIZE]);
            outputs.add(0);
            return transitions.size() - 1;
        }

        void add(int flags, String... markers) {
            for (String marker : markers) {
                int state = 0;
                for (int index = 0; index < marker.length(); index++) {
                    int symbol = symbol(marker.charAt(index));
                    int next = transitions.get(state)[symbol];
                    if (next == 0) {
                        next = addState();
                        transitions.get(state)[symbol] = (char) next;
                    }
                    state = next;
                }
                outputs.set(state, outputs.get(state) | flags);
            }
        }

        Matcher build() {
            int[] fallback = new int[transitions.size()];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (char next : transitions.get(0)) {
                if (next != 0) queue.add((int) next);
            }
            while (!queue.isEmpty()) {
                int state = queue.remove();
                char[] row = transitions.get(state);
                char[] fallbackRow = transitions.get(fallback[state]);
                for (int symbol = 0; symbol < ALPHABET_SIZE; symbol++) {
                    int next = row[symbol];
                    if (next == 0) {
                        row[symbol] = fallbackRow[symbol];
                    } else {
                        fallback[next] = fallbackRow[symbol];
                        outputs.set(next, outputs.get(next) | outputs.get(fallback[next]));
                        queue.add(next);
                    }
                }
            }
            int[] resultOutputs = new int[outputs.size()];
            for (int index = 0; index < resultOutputs.length; index++) resultOutputs[index] = outputs.get(index);
            return new Matcher(transitions.toArray(new char[transitions.size()][]), resultOutputs);
        }
    }
}
