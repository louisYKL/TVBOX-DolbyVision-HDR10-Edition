package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Optional two-generation diagnostic log store with a strict 1 MB total budget. */
final class RuntimeLogStore {
    private static final String ACTIVE_FILE = "runtime.log";
    private static final String ARCHIVE_FILE = "runtime.1.log";

    private RuntimeLogStore() {
    }

    static synchronized void append(String level, String message) {
        App app = App.getInstance();
        if (app == null || message == null) {
            return;
        }
        File directory = StorageBudgetManager.getLogDir();
        if (!directory.exists() && !directory.mkdirs()) {
            return;
        }
        File active = new File(directory, ACTIVE_FILE);
        File archive = new File(directory, ARCHIVE_FILE);
        byte[] record = (System.currentTimeMillis() + " " + level + " " + message + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (record.length > InternalLogPolicy.MAX_FILE_BYTES) {
            return;
        }

        // A failed cleanup must never turn diagnostic data into unbounded app storage.
        if (!discardOversizedGeneration(active) || !discardOversizedGeneration(archive)) {
            return;
        }
        if (InternalLogPolicy.shouldRotate(active.length(), record.length)) {
            if (archive.exists() && !archive.delete()) {
                return;
            }
            if (!active.renameTo(archive)) {
                return;
            }
        }
        if (!InternalLogPolicy.fitsInGeneration(active.length(), record.length)) {
            return;
        }
        try (FileOutputStream output = new FileOutputStream(active, true)) {
            output.write(record);
        } catch (Throwable ignored) {
        }
    }

    private static boolean discardOversizedGeneration(File file) {
        return file.length() <= InternalLogPolicy.MAX_FILE_BYTES
                || !file.exists()
                || file.delete();
    }
}
