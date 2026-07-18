package xyz.doikki.videoplayer.player;

final class Tv32MatroskaRoutePolicy {
    private Tv32MatroskaRoutePolicy() {
    }

    static boolean shouldUseDirectUri(boolean hdrLike, boolean startupPreflight) {
        // The released v0.2.1 TV32 path gives HDR Matroska to the vendor HTTP URI stack.
        // Feeding the same file through AppFuse/MediaDataSource makes Huawei MediaPlayer
        // open an encoded E-AC3 track against the speaker output, which is silent. An uncertain
        // startup probe is not HDR evidence and must keep the bounded range-cache route.
        return hdrLike;
    }

    static boolean shouldDelayResumeSeekUntilRenderingStart(boolean rangeBacked,
                                                             boolean directUri) {
        // A prepared MediaPlayer can seek before start. Keeping that single lifecycle avoids
        // rendering the opening frame, pausing, seeking, and rebuilding the audio pipeline.
        return false;
    }

}
