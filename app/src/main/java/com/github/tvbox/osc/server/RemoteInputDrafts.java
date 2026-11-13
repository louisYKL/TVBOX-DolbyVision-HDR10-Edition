package com.github.tvbox.osc.server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** At most five in-memory drafts. Contains no views, credentials on disk, or apply action. */
public final class RemoteInputDrafts {
    private static final RemoteInputDrafts INSTANCE = new RemoteInputDrafts();
    private final Map<String, PendingInput> fields = new HashMap<>();

    public static RemoteInputDrafts get() {
        return INSTANCE;
    }

    public synchronized PendingInput put(RemoteInputRequest request) {
        PendingInput pending = new PendingInput(request);
        fields.put(request.target, pending);
        return pending;
    }

    /** Only an opened native input consumes its own target; unrelated drafts remain queued. */
    public synchronized PendingInput take(String target) {
        return fields.remove(target);
    }

    public static final class PendingInput {
        public final RemoteInputRequest request;
        private final CountDownLatch filled = new CountDownLatch(1);

        private PendingInput(RemoteInputRequest request) {
            this.request = request;
        }

        /** Called after the native EditText has received the value on the main thread. */
        public void markFilled() {
            filled.countDown();
        }

        public boolean awaitFilled(long timeoutMs) {
            try {
                return filled.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
