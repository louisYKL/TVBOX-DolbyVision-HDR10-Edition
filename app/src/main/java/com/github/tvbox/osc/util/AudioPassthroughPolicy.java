package com.github.tvbox.osc.util;

final class AudioPassthroughPolicy {
    static final int AC3 = 1;
    static final int E_AC3 = 1 << 1;
    static final int DTS = 1 << 2;
    static final int TRUE_HD = 1 << 3;
    static final int E_AC3_JOC = 1 << 4;

    private AudioPassthroughPolicy() {
    }

    static boolean supports(int requiredEncodings, int deviceEncodings) {
        return requiredEncodings != 0
                && (requiredEncodings & ~deviceEncodings) == 0;
    }
}
