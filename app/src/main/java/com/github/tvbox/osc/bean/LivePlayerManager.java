package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;
    private String currentApi="";

    public void init(VideoView videoView) {
        try {
            currentApi=Hawk.get(HawkConfig.LIVE_API_URL,"");
            defaultPlayerConfig.put("pl", PlayerHelper.PLAYER_TYPE_SYSTEM);
            defaultPlayerConfig.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 1));
            defaultPlayerConfig.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
            defaultPlayerConfig.put(HawkConfig.PLAYER_IS_LIVE, true);
            defaultPlayerConfig.put(HawkConfig.PLAYER_SELECTION_MANUAL, false);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig);
        try {
            currentPlayerConfig = new JSONObject(defaultPlayerConfig.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void getLiveChannelPlayer(VideoView videoView, String channelName) {
        channelName=currentCfgKey(channelName);
        JSONObject playerConfig = Hawk.get(channelName, null);
        if (playerConfig == null) {
            if (!currentPlayerConfig.toString().equals(defaultPlayerConfig.toString()))
                getDefaultLiveChannelPlayer(videoView);
            return;
        }
        if (playerConfig.toString().equals(currentPlayerConfig.toString()))
            return;

        try {
            playerConfig.put("pl", PlayerHelper.PLAYER_TYPE_SYSTEM);
            playerConfig.put(HawkConfig.PLAYER_IS_LIVE, true);
            if (!playerConfig.optBoolean(HawkConfig.PLAYER_SELECTION_MANUAL, false)) {
                playerConfig = new JSONObject(defaultPlayerConfig.toString());
            }
            if (playerConfig.getInt("pl") == currentPlayerConfig.getInt("pl")
                    && playerConfig.getInt("pr") == currentPlayerConfig.getInt("pr")) {
                videoView.setScreenScaleType(playerConfig.getInt("sc"));
            } else {
                PlayerHelper.updateCfg(videoView, playerConfig);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        currentPlayerConfig = playerConfig;
    }

    public int getLivePlayerType() {
        return 0;
    }

    public int getCurrentPlayerType() {
        try {
            return currentPlayerConfig == null ? PlayerHelper.PLAYER_TYPE_SYSTEM : currentPlayerConfig.getInt("pl");
        } catch (JSONException e) {
            e.printStackTrace();
            return PlayerHelper.PLAYER_TYPE_SYSTEM;
        }
    }

    public int getLivePlayerScale() {
        try {
            return currentPlayerConfig.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void changeLivePlayerType(VideoView videoView, int playerType, String channelName) {
        channelName=currentCfgKey(channelName);
        JSONObject playerConfig = currentPlayerConfig;
        try {
            playerConfig.put("pl", PlayerHelper.PLAYER_TYPE_SYSTEM);
            playerConfig.put(HawkConfig.PLAYER_IS_LIVE, true);
            playerConfig.put(HawkConfig.PLAYER_SELECTION_MANUAL, true);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        if (playerConfig.toString().equals(defaultPlayerConfig.toString()))
            Hawk.delete(channelName);
        else
            Hawk.put(channelName, playerConfig);

        currentPlayerConfig = playerConfig;
    }

    public boolean switchLivePlayer(VideoView videoView, String channelName) {
        channelName = currentCfgKey(channelName);
        JSONObject playerConfig = currentPlayerConfig;
        if (playerConfig == null) {
            LOG.i("echo-liveSwitchPlayer: skip empty player config");
            return false;
        }
        LOG.i("echo-liveSwitchPlayer: system-player-only");
        return true;
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale, String channelName){
        channelName=currentCfgKey(channelName);
        videoView.setScreenScaleType(playerScale);

        JSONObject playerConfig = currentPlayerConfig;
        try {
            playerConfig.put("sc", playerScale);
            playerConfig.put(HawkConfig.PLAYER_IS_LIVE, true);
            playerConfig.put(HawkConfig.PLAYER_SELECTION_MANUAL, true);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (playerConfig.toString().equals(defaultPlayerConfig.toString()))
            Hawk.delete(channelName);
        else
            Hawk.put(channelName, playerConfig);

        currentPlayerConfig = playerConfig;
    }

    private String currentCfgKey(String channelName)
    {
        return currentApi+"_"+channelName;
    }
}
