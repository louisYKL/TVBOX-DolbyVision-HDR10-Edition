package com.github.tvbox.osc.player;

import android.content.Context;

import xyz.doikki.videoplayer.player.PlayerFactory;

public class Java64CodecPlayerFactory extends PlayerFactory<Java64CodecPlayer> {
    public static Java64CodecPlayerFactory create() {
        return new Java64CodecPlayerFactory();
    }

    @Override
    public Java64CodecPlayer createPlayer(Context context) {
        return new Java64CodecPlayer(context);
    }
}
