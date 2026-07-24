package com.github.tvbox.osc.player;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.text.Cue;

import java.util.List;

/** Common track, subtitle, and runtime HDR callbacks for built-in players. */
public interface CompatTrackSelectorPlayer {
    TrackInfo getTrackInfo();

    void selectAudioTrack(@Nullable TrackInfoBean track);

    void selectSubtitleTrack(@Nullable TrackInfoBean track);

    void clearSubtitleTrackSelection();

    boolean shouldDelaySubtitleSelection(int attempt);

    boolean shouldDelaySubtitleSelection(int currentSubtitleCount, int attempt);

    void setOnSubtitleTextListener(@Nullable SubtitleTextListener listener);

    void setOnBitmapSubtitleCueListener(@Nullable BitmapSubtitleCueListener listener);

    void setOnRuntimeVideoModeListener(@Nullable RuntimeVideoModeListener listener);

    interface SubtitleTextListener {
        void onSubtitleText(String text);
    }

    interface BitmapSubtitleCueListener {
        void onBitmapSubtitleCues(List<Cue> cues);
    }

    interface RuntimeVideoModeListener {
        void onRuntimeVideoMode(boolean hdr, boolean dolbyVision, String outputMode, String reason);
    }
}
