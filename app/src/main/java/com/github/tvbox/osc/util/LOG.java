package com.github.tvbox.osc.util;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class LOG {
    private static String TAG = "TVBox-runtime";
    private static final int MAX_PENDING_LINES = 2048;
    private static final long TRIM_INTERVAL_MS = 5000L;
    private static final BlockingQueue<LogEntry> PENDING_LINES = new ArrayBlockingQueue<>(MAX_PENDING_LINES);
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean(false);
    private static final AtomicInteger DROPPED_LINES = new AtomicInteger(0);
    private static final AtomicLong LAST_TRIM_AT = new AtomicLong(0L);

    public static void e(String msg) {
        String line = "" + msg;
        Log.e(TAG, line);
        appendToFile("E", line);
    }

    public static void i(String msg) {
        String line = "" + msg;
        Log.i(TAG, line);
        appendToFile("I", line);
    }

    private static void appendToFile(String level, String msg) {
        ensureWriterStarted();
        if (!PENDING_LINES.offer(new LogEntry(level, msg, System.currentTimeMillis()))) {
            DROPPED_LINES.incrementAndGet();
        }
    }

    private static void ensureWriterStarted() {
        if (WRITER_STARTED.compareAndSet(false, true)) {
            Thread writerThread = new Thread(LOG::drainLoop, "tvbox-log-writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }
    }

    private static void drainLoop() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        List<LogEntry> batch = new ArrayList<>(64);
        while (true) {
            try {
                LogEntry head = PENDING_LINES.take();
                batch.clear();
                batch.add(head);
                PENDING_LINES.drainTo(batch, 63);

                int dropped = DROPPED_LINES.getAndSet(0);
                if (dropped > 0) {
                    batch.add(new LogEntry("W", "log queue overflow dropped=" + dropped, System.currentTimeMillis()));
                }

                writeBatch(batch, dateFormat);
                trimLogsIfNeeded();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeBatch(List<LogEntry> batch, SimpleDateFormat dateFormat) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            File file = StorageBudgetManager.getLogFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            StringBuilder builder = new StringBuilder(batch.size() * 96);
            for (LogEntry entry : batch) {
                builder.append(dateFormat.format(new Date(entry.timeMs)))
                        .append(' ')
                        .append(entry.level)
                        .append('/')
                        .append(TAG)
                        .append(' ')
                        .append(entry.message)
                        .append('\n');
            }
            try (FileOutputStream outputStream = new FileOutputStream(file, true)) {
                outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trimLogsIfNeeded() {
        long now = System.currentTimeMillis();
        long last = LAST_TRIM_AT.get();
        if (last != 0L && now - last < TRIM_INTERVAL_MS) {
            return;
        }
        if (LAST_TRIM_AT.compareAndSet(last, now)) {
            try {
                StorageBudgetManager.trimLogs();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class LogEntry {
        final String level;
        final String message;
        final long timeMs;

        LogEntry(String level, String message, long timeMs) {
            this.level = level;
            this.message = message;
            this.timeMs = timeMs;
        }
    }
}
