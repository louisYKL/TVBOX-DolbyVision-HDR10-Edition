package com.github.tvbox.osc.player;

import java.nio.ByteBuffer;

/** Keeps the HDR10/HLG base layer while removing Dolby Vision RPU and EL NAL units. */
final class DolbyVisionRpuStripper {
    private byte[] scratch = new byte[0];
    private int lastInputBytes;
    private int lastOutputBytes;

    /**
     * Compacts one Annex-B access unit in place. The scratch array grows only for a new maximum
     * access-unit size and is reused for the rest of playback.
     */
    int stripInPlace(ByteBuffer data) {
        if (data == null) {
            return 0;
        }
        int origin = data.position();
        int inputBytes = data.remaining();
        if (inputBytes < 5) {
            return 0;
        }
        ensureCapacity(inputBytes);
        data.get(scratch, 0, inputBytes);
        data.position(origin);
        lastInputBytes = inputBytes;
        lastOutputBytes = inputBytes;

        int firstStart = findStartCode(scratch, 0, inputBytes);
        if (firstStart < 0) {
            // ISO-BMFF/Matroska samples commonly use a 4-byte (occasionally 2-byte)
            // big-endian NAL length instead of Annex-B start codes. The TV HEVC
            // decoder can black-screen when DV RPU/EL NALs remain in that form too.
            int removed = stripLengthPrefixed(4, inputBytes);
            if (removed < 0) {
                removed = stripLengthPrefixed(2, inputBytes);
            }
            if (removed > 0) {
                data.position(origin);
                data.put(scratch, 0, lastOutputBytes);
                data.limit(origin + lastOutputBytes);
                data.position(origin);
            }
            return Math.max(0, removed);
        }

        int read = firstStart;
        int write = firstStart;
        int removed = 0;
        while (read >= 0 && read < inputBytes) {
            int payload = read + startCodeLength(scratch, read, inputBytes);
            int nextStart = findStartCode(scratch, payload + 2, inputBytes);
            int end = nextStart < 0 ? inputBytes : nextStart;
            if (payload < end) {
                int nalType = (scratch[payload] >> 1) & 0x3F;
                if (nalType == 62 || nalType == 63) {
                    removed++;
                } else {
                    write = copyKeptRange(read, end, write);
                }
            } else {
                // Keep malformed tail bytes so filtering cannot create an empty decoder input.
                write = copyKeptRange(read, end, write);
            }
            read = nextStart;
        }

        if (removed == 0) {
            return 0;
        }
        lastOutputBytes = write;
        data.position(origin);
        data.put(scratch, 0, write);
        data.limit(origin + write);
        data.position(origin);
        return removed;
    }

    /** Returns -1 when the sample is not a complete length-prefixed access unit. */
    private int stripLengthPrefixed(int lengthBytes, int inputBytes) {
        int read = 0;
        int removed = 0;
        while (read + lengthBytes <= inputBytes) {
            int nalLength = 0;
            for (int i = 0; i < lengthBytes; i++) {
                nalLength = (nalLength << 8) | (scratch[read + i] & 0xff);
            }
            int nalStart = read + lengthBytes;
            int nalEnd = nalStart + nalLength;
            if (nalLength <= 0 || nalEnd > inputBytes) {
                return -1;
            }
            int nalType = (scratch[nalStart] >> 1) & 0x3F;
            if (nalType == 62 || nalType == 63) {
                removed++;
            }
            read = nalEnd;
        }
        if (read != inputBytes || removed == 0) {
            return read == inputBytes ? 0 : -1;
        }
        // Validate the complete sample before compacting. This keeps a failed 4-byte
        // parse intact so the 2-byte fallback can inspect the original bytes.
        read = 0;
        int write = 0;
        while (read < inputBytes) {
            int nalLength = 0;
            for (int i = 0; i < lengthBytes; i++) {
                nalLength = (nalLength << 8) | (scratch[read + i] & 0xff);
            }
            int nalEnd = read + lengthBytes + nalLength;
            int nalType = (scratch[read + lengthBytes] >> 1) & 0x3F;
            if (nalType != 62 && nalType != 63) {
                int rangeLength = nalEnd - read;
                if (write != read) {
                    System.arraycopy(scratch, read, scratch, write, rangeLength);
                }
                write += rangeLength;
            }
            read = nalEnd;
        }
        lastOutputBytes = write;
        return removed;
    }

    int lastInputBytes() {
        return lastInputBytes;
    }

    int lastOutputBytes() {
        return lastOutputBytes;
    }

    int scratchCapacity() {
        return scratch.length;
    }

    private int copyKeptRange(int start, int end, int write) {
        int length = end - start;
        if (length > 0 && write != start) {
            System.arraycopy(scratch, start, scratch, write, length);
        }
        return write + length;
    }

    private void ensureCapacity(int required) {
        if (scratch.length >= required) {
            return;
        }
        int capacity = Math.max(1024, scratch.length);
        while (capacity < required && capacity < (Integer.MAX_VALUE >>> 1)) {
            capacity <<= 1;
        }
        scratch = new byte[Math.max(capacity, required)];
    }

    private static int findStartCode(byte[] data, int from, int limit) {
        for (int i = Math.max(0, from); i + 2 < limit; i++) {
            if (data[i] != 0 || data[i + 1] != 0) {
                continue;
            }
            if (data[i + 2] == 1) {
                return i;
            }
            if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int start, int limit) {
        return start + 3 < limit
                && data[start] == 0
                && data[start + 1] == 0
                && data[start + 2] == 0
                && data[start + 3] == 1 ? 4 : 3;
    }
}
