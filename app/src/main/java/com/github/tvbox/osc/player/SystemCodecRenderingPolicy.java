package com.github.tvbox.osc.player;

/**
 * Decides when the system playback bridge may report its first rendering event.
 *
 * <p>Some TV firmware delivers a decoded first frame while ExoPlayer is still
 * buffering. A first frame is not proof that the playback clock is advancing,
 * so the bridge waits for the native playing signal or a real position advance.</p>
 */
public final class SystemCodecRenderingPolicy {
    private SystemCodecRenderingPolicy() {
    }

    /**
     * Returns whether the player has enough evidence to dispatch
     * {@code MEDIA_INFO_RENDERING_START}.
     *
     * <p>The playback state is intentionally not an input because vendor firmware
     * can report it late. A selected video track still requires a rendered frame,
     * and every route also requires proof that the playback clock has started.</p>
     */
    public static boolean shouldNotify(boolean released,
                                       boolean alreadyNotified,
                                       boolean playWhenReady,
                                       boolean selectedVideoTrack,
                                       boolean selectedAudioTrack,
                                       boolean firstFrameRendered,
                                       boolean nativeIsPlaying,
                                       boolean positionAdvanced) {
        if (released || alreadyNotified || !playWhenReady) {
            return false;
        }
        if (selectedVideoTrack && !firstFrameRendered) {
            return false;
        }
        if (!selectedVideoTrack && !selectedAudioTrack && !firstFrameRendered) {
            return false;
        }
        return nativeIsPlaying || positionAdvanced;
    }
}
