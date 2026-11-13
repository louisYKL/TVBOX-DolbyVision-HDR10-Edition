package com.github.tvbox.osc.player;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaPeriodId;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

final class SystemCodecLoadControl extends DefaultLoadControl {
    interface BufferReadiness {
        boolean hasRequiredBuffer(int requiredBytes);
    }

    interface EndOfMediaCheck {
        boolean isBufferedToEnd();
    }

    private final BufferReadiness bufferReadiness;
    private final int startBufferBytes;
    private final int maximumLoadBufferBytes;
    private final DefaultAllocator allocator;
    private final EndOfMediaCheck endOfMediaCheck;
    private volatile boolean byteGatedSource;
    private volatile boolean liveOrDynamicSource;
    private volatile long lastLoadDurationUs;
    private volatile int lastLoadAllocatedBytes;
    private volatile boolean lastContinueLoading;
    private volatile long lastStartDurationUs;
    private volatile boolean lastStartAllowed;
    private volatile boolean lastStartWasRebuffering;
    private final Timeline.Window window = new Timeline.Window();

    SystemCodecLoadControl(DefaultAllocator allocator,
                           int minBufferMs,
                           int maxBufferMs,
                           int startBufferBytes,
                           int maxBufferBytes,
                           boolean byteGatedSource,
                           BufferReadiness bufferReadiness,
                           EndOfMediaCheck endOfMediaCheck) {
        super(allocator, minBufferMs, maxBufferMs,
                SystemCodecBufferPolicy.PLAYBACK_BUFFER_MS,
                SystemCodecBufferPolicy.REBUFFER_MS,
                maxBufferBytes, false, 0, false);
        this.bufferReadiness = bufferReadiness;
        this.startBufferBytes = startBufferBytes;
        this.maximumLoadBufferBytes = maxBufferBytes;
        this.allocator = allocator;
        this.endOfMediaCheck = endOfMediaCheck;
        this.byteGatedSource = byteGatedSource;
    }

    void setSourcePolicy(boolean byteGatedSource, boolean liveOrDynamicSource) {
        this.byteGatedSource = byteGatedSource;
        this.liveOrDynamicSource = liveOrDynamicSource;
    }

    void setLiveOrDynamicSource(boolean liveOrDynamicSource) {
        this.liveOrDynamicSource = liveOrDynamicSource;
    }

    @Override
    public boolean shouldContinueLoading(long playbackPositionUs,
                                         long bufferedDurationUs,
                                         float playbackSpeed) {
        // Keep loading toward the byte gate, but never override the device-specific allocator
        // budget. The loopback Range fill lane continues independently within its own cache cap.
        lastLoadDurationUs = bufferedDurationUs;
        lastLoadAllocatedBytes = allocator.getTotalBytesAllocated();
        boolean result = shouldWaitForByteBuffer() && !hasRequiredBufferNow()
                ? lastLoadAllocatedBytes < maximumLoadBufferBytes
                : super.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed);
        lastContinueLoading = result;
        return result;
    }

    @Override
    public boolean shouldStartPlayback(Timeline timeline,
                                       MediaPeriodId mediaPeriodId,
                                       long bufferedDurationUs,
                                       float playbackSpeed,
                                       boolean rebuffering,
                                       long targetLiveOffsetUs) {
        if (timeline != null && !timeline.isEmpty()) {
            timeline.getWindow(0, window);
            // A progressive HTTP/WebDAV source can expose a temporary dynamic window while
            // its duration is still being resolved. The source policy is authoritative: a
            // byte-gated Range VOD must keep using the byte runway even in that state.
            if (shouldUseTimePolicyForTimeline(byteGatedSource,
                    window.isLive() || window.isDynamic)) {
                liveOrDynamicSource = true;
                return super.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs,
                        playbackSpeed, rebuffering, targetLiveOffsetUs);
            }
        }
        if (!shouldWaitForByteBuffer()) {
            // HLS/live and non-network sources retain ExoPlayer's normal bounded time policy.
            return super.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs,
                    playbackSpeed, rebuffering, targetLiveOffsetUs);
        }
        // The byte gate proves that the bounded Range cache has a contiguous runway, but it
        // does not prove that ExoPlayer has decoded enough samples into its queues. A 4K
        // remux can consume 48 MiB in roughly one second; returning true from this method as
        // soon as the raw cache reaches the byte threshold causes READY/BUFFERING oscillation
        // with only a couple of seconds of playable media. Keep the byte gate as a prerequisite
        // and retain ExoPlayer's bounded time gate for the actual sample queues (including the
        // larger after-rebuffer threshold).
        lastStartDurationUs = bufferedDurationUs;
        lastStartWasRebuffering = rebuffering;
        boolean result = hasRequiredBufferNow()
                && super.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs,
                playbackSpeed, rebuffering, targetLiveOffsetUs);
        lastStartAllowed = result;
        return result;
    }

    String getBufferDiagnostics() {
        return "loadDurationUs=" + lastLoadDurationUs
                + " loadAllocatedBytes=" + lastLoadAllocatedBytes
                + " allocatorLimit=" + maximumLoadBufferBytes
                + " continueLoading=" + lastContinueLoading
                + " startDurationUs=" + lastStartDurationUs
                + " startAllowed=" + lastStartAllowed
                + " startRebuffer=" + lastStartWasRebuffering;
    }

    boolean hasRequiredBufferNow() {
        // A sample queue's EOF snapshot can still describe the previous position while a
        // seek is being applied. Only the Range session proves a VOD byte runway, including
        // the shorter remaining tail of a file. Never bypass that proof with a stale timeline.
        return (bufferReadiness != null && bufferReadiness.hasRequiredBuffer(startBufferBytes))
                || (!byteGatedSource && endOfMediaCheck != null && endOfMediaCheck.isBufferedToEnd());
    }

    boolean shouldWaitForByteBuffer() {
        return byteGatedSource && !liveOrDynamicSource;
    }

    static boolean shouldUseTimePolicyForTimeline(boolean byteGatedSource,
                                                  boolean timelineDynamic) {
        return timelineDynamic && !byteGatedSource;
    }

}
