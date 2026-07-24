package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class StorageBudgetManager {
    public static final long MAX_CACHE_BYTES = 100L * 1024L * 1024L;
    public static final long MAX_INTERNAL_LOG_BYTES = InternalLogPolicy.MAX_TOTAL_BYTES;
    private static final Comparator<File> OLDEST_FIRST = new Comparator<File>() {
        @Override
        public int compare(File left, File right) {
            long leftModified = safeLastModified(left);
            long rightModified = safeLastModified(right);
            if (leftModified == rightModified) {
                return 0;
            }
            return leftModified < rightModified ? -1 : 1;
        }
    };

    private StorageBudgetManager() {
    }

    public static void enforceAllBudgets() {
        try {
            trimLogs();
        } catch (Throwable ignored) {
        }
        try {
            trimCaches();
        } catch (Throwable ignored) {
        }
    }

    public static void trimLogs() {
        trimDirectoryToBudget(getLogDir(), MAX_INTERNAL_LOG_BYTES, true);
    }

    public static void trimCaches() {
        App app = App.getInstance();
        if (app == null) {
            return;
        }
        List<File> roots = new ArrayList<>();
        addIfExists(roots, app.getCacheDir());
        addIfExists(roots, app.getExternalCacheDir());
        addIfExists(roots, new File(app.getFilesDir(), "csp"));
        addIfExists(roots, new File(app.getCacheDir(), "catvod_jsapi"));
        addIfExists(roots, new File(app.getCacheDir(), "catvod_csp"));
        addIfExists(roots, new File(app.getCacheDir(), "ijkcaches"));
        addIfExists(roots, new File(app.getCacheDir(), "thunder"));
        addIfExists(roots, new File(FileUtils.getExternalCachePath()));
        trimDirectoriesToBudget(roots, MAX_CACHE_BYTES);
    }

    public static File getLogFile() {
        return new File(getLogDir(), "runtime.log");
    }

    static File getLogDir() {
        App app = App.getInstance();
        if (app == null) {
            return new File("logs");
        }
        return new File(app.getFilesDir(), "logs");
    }

    private static void trimDirectoriesToBudget(List<File> roots, long maxBytes) {
        List<File> allEntries = new ArrayList<>();
        long totalBytes = 0L;
        for (File root : roots) {
            if (root == null || !root.exists()) {
                continue;
            }
            totalBytes += collectChildren(root, allEntries);
        }
        if (totalBytes <= maxBytes) {
            return;
        }
        Collections.sort(allEntries, OLDEST_FIRST);
        for (File entry : allEntries) {
            if (totalBytes <= maxBytes) {
                break;
            }
            long entryBytes = fileLength(entry);
            deleteRecursively(entry);
            totalBytes -= entryBytes;
        }
    }

    private static void trimDirectoryToBudget(File dir, long maxBytes, boolean keepNewestFile) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        List<File> entries = new ArrayList<>(Arrays.asList(files));
        long totalBytes = 0L;
        for (File file : entries) {
            totalBytes += fileLength(file);
        }
        if (totalBytes <= maxBytes) {
            return;
        }
        Collections.sort(entries, OLDEST_FIRST);
        File newest = keepNewestFile ? Collections.max(entries, OLDEST_FIRST) : null;
        for (File file : entries) {
            if (totalBytes <= maxBytes) {
                break;
            }
            if (keepNewestFile && file.equals(newest)) {
                continue;
            }
            long fileBytes = fileLength(file);
            deleteRecursively(file);
            totalBytes -= fileBytes;
        }
        if (keepNewestFile && newest != null && newest.exists() && fileLength(newest) > maxBytes) {
            FileUtils.writeSimple(new byte[0], newest);
        }
    }

    private static long collectChildren(File root, List<File> entries) {
        if (root == null || !root.exists()) {
            return 0L;
        }
        if (root.isFile()) {
            entries.add(root);
            return root.length();
        }
        long total = 0L;
        File[] children = root.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            entries.add(child);
            total += fileLength(child);
        }
        return total;
    }

    private static long fileLength(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            total += fileLength(child);
        }
        return total;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static long safeLastModified(File file) {
        if (file == null || !file.exists()) {
            return Long.MIN_VALUE;
        }
        return file.lastModified();
    }

    private static void addIfExists(List<File> roots, File file) {
        if (file != null && !containsPath(roots, file)) {
            roots.add(file);
        }
    }

    private static boolean containsPath(List<File> roots, File file) {
        String target = safePath(file);
        if (TextUtils.isEmpty(target)) {
            return false;
        }
        for (File root : roots) {
            if (target.equals(safePath(root))) {
                return true;
            }
        }
        return false;
    }

    private static String safePath(File file) {
        try {
            return file == null ? null : file.getCanonicalPath();
        } catch (Exception ignored) {
            return file == null ? null : file.getAbsolutePath();
        }
    }
}
