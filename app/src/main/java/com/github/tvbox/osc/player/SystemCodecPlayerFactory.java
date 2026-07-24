package com.github.tvbox.osc.player;

import android.content.Context;

import xyz.doikki.videoplayer.player.PlayerFactory;

public final class SystemCodecPlayerFactory extends PlayerFactory<SystemCodecPlayer> {
    public static SystemCodecPlayerFactory create() {
        return new SystemCodecPlayerFactory();
    }

    @Override
    public SystemCodecPlayer createPlayer(Context context) {
        return new SystemCodecPlayer(context);
    }
}
