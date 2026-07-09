package com.github.tvbox.osc.player;

import androidx.annotation.Nullable;

public interface RuntimeVideoModeAwarePlayer {
    void setOnRuntimeVideoModeListener(@Nullable MPVCompatPlayer.OnRuntimeVideoModeListener listener);
}
