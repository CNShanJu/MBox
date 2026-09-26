package com.github.tvbox.osc.util;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import com.github.tvbox.osc.player.api.PlayConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Objects;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;

    public void init(VideoView videoView) {
        try {
            defaultPlayerConfig.put("pl", PlayConfig.getPlayType());
            defaultPlayerConfig.put("ijk", PlayConfig.getIjkCodec());
            defaultPlayerConfig.put("pr", PlayConfig.getRenderType());
            defaultPlayerConfig.put("sc", PlayConfig.getScaleType());
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
        JSONObject playerConfig = LiveConfig.channelPlayerConfig(channelName);
        if (playerConfig == null) {
            if (!currentPlayerConfig.toString().equals(defaultPlayerConfig.toString()))
                getDefaultLiveChannelPlayer(videoView);
            return;
        }
        if (playerConfig.toString().equals(currentPlayerConfig.toString()))
            return;

        try {
            if (playerConfig.getInt("pl") == currentPlayerConfig.getInt("pl")
                    && playerConfig.getInt("pr") == currentPlayerConfig.getInt("pr")
                    && playerConfig.getString("ijk").equals(currentPlayerConfig.getString("ijk"))) {
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
        int playerTypeIndex = 0;
        try {
            playerTypeIndex = LivePlayerTypes.typeIndex(
                    currentPlayerConfig.getInt("pl"), currentPlayerConfig.getString("ijk"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return playerTypeIndex;
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
        JSONObject playerConfig = currentPlayerConfig;
        try {
            playerConfig.put("pl", LivePlayerTypes.playerTypeOf(playerType));
            playerConfig.put("ijk", LivePlayerTypes.ijkCodecOf(playerType));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PlayerHelper.updateCfg(videoView, playerConfig);

        if (playerConfig.toString().equals(defaultPlayerConfig.toString()))
            LiveConfig.deleteChannelPlayerConfig(channelName);
        else
            LiveConfig.setChannelPlayerConfig(channelName, playerConfig);

        currentPlayerConfig = playerConfig;
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale, String channelName){
        videoView.setScreenScaleType(playerScale);

        JSONObject playerConfig = currentPlayerConfig;
        try {
            playerConfig.put("sc", playerScale);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (playerConfig.toString().equals(defaultPlayerConfig.toString()))
            LiveConfig.deleteChannelPlayerConfig(channelName);
        else
            LiveConfig.setChannelPlayerConfig(channelName, playerConfig);

        currentPlayerConfig = playerConfig;
    }
}