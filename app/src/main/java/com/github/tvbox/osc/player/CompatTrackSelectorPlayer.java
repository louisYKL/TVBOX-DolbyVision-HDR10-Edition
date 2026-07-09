package com.github.tvbox.osc.player;

import androidx.annotation.Nullable;

public interface CompatTrackSelectorPlayer {
    TrackInfo getTrackInfo();

    void selectAudioTrack(@Nullable TrackInfoBean track);

    void selectSubtitleTrack(@Nullable TrackInfoBean track);

    void clearSubtitleTrackSelection();

    boolean shouldDelaySubtitleSelection(int attempt);

    boolean shouldDelaySubtitleSelection(int currentSubtitleCount, int attempt);

    void setOnSubtitleTextListener(@Nullable SubtitleTextListener listener);

    interface SubtitleTextListener {
        void onSubtitleText(String text);
    }
}
