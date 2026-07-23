package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;

public final class LOG {
    private LOG() {
    }

    public static void e(String msg) {
        write("E", msg);
    }

    public static void i(String msg) {
        write("I", msg);
    }

    private static void write(String level, String msg) {
        try {
            // Persistent diagnostic logs are opt-in only. Normal playback never writes them.
            if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
                RuntimeLogStore.append(level, msg);
            }
        } catch (Throwable ignored) {
        }
    }
}
