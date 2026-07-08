package com.github.catvod.crawler;

import com.github.tvbox.osc.util.LOG;

public class SpiderDebug {
    private static final long DUPLICATE_WINDOW_MS = 1000L;
    private static final int MAX_DUPLICATE_LOGS_PER_WINDOW = 3;
    private static final Object DUPLICATE_LOCK = new Object();
    private static String lastMessage = "";
    private static long lastWindowStartMs = 0L;
    private static int emittedCountInWindow = 0;
    private static int suppressedCount = 0;

    public static void log(Throwable th) {
        try {
            if (th == null) {
                return;
            }
            String message = th.getMessage();
            if (message == null) {
                message = "";
            }
            String detail;
            if (!message.trim().isEmpty() && !"null".equalsIgnoreCase(message.trim())) {
                detail = th.getClass().getSimpleName() + ": " + message;
            } else {
                detail = th.getClass().getSimpleName();
            }
            EmitDecision decision = decide(detail);
            emitSuppressedSummary(decision);
            if (!decision.emitCurrent) {
                return;
            }
            android.util.Log.d("SpiderLog", message, th);
            LOG.e("SpiderLog " + detail);
        } catch (Throwable th1) {

        }
    }

    public static void log(String msg) {
        try {
            String safeMessage = msg == null ? "" : msg;
            EmitDecision decision = decide(safeMessage);
            emitSuppressedSummary(decision);
            if (!decision.emitCurrent) {
                return;
            }
            android.util.Log.d("SpiderLog", safeMessage);
            LOG.i("SpiderLog " + safeMessage);
        } catch (Throwable th1) {

        }
    }

    private static EmitDecision decide(String message) {
        long now = System.currentTimeMillis();
        synchronized (DUPLICATE_LOCK) {
            String summary = null;
            boolean newWindow = !message.equals(lastMessage) || now - lastWindowStartMs > DUPLICATE_WINDOW_MS;
            if (newWindow) {
                summary = buildSuppressedSummaryLocked();
                lastMessage = message;
                lastWindowStartMs = now;
                emittedCountInWindow = 1;
                suppressedCount = 0;
                return new EmitDecision(true, summary);
            }
            if (emittedCountInWindow < MAX_DUPLICATE_LOGS_PER_WINDOW) {
                emittedCountInWindow++;
                return new EmitDecision(true, null);
            }
            suppressedCount++;
            return new EmitDecision(false, null);
        }
    }

    private static String buildSuppressedSummaryLocked() {
        if (suppressedCount <= 0 || lastMessage == null || lastMessage.isEmpty()) {
            return null;
        }
        return "suppressed duplicate x" + suppressedCount + " msg=" + lastMessage;
    }

    private static void emitSuppressedSummary(EmitDecision decision) {
        try {
            if (decision == null || decision.summary == null) {
                return;
            }
            android.util.Log.d("SpiderLog", decision.summary);
            LOG.i("SpiderLog " + decision.summary);
        } catch (Throwable ignored) {
        }
    }

    private static final class EmitDecision {
        final boolean emitCurrent;
        final String summary;

        EmitDecision(boolean emitCurrent, String summary) {
            this.emitCurrent = emitCurrent;
            this.summary = summary;
        }
    }

    public static String ec(int i) {
        return "";
    }
}
