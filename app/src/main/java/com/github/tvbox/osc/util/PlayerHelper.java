package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;

import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.player.api.IjkCodecConfigProviders;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.player.thirdparty.VlcPlayer;
import com.github.tvbox.osc.player.api.PlayConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;

import tv.danmaku.ijk.media.player.IjkLibLoader;
import xyz.doikki.videoplayer.exo.ExoMediaPlayerFactory;
import xyz.doikki.videoplayer.player.AndroidMediaPlayerFactory;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        if (videoView == null) return; // 防御:播放器视图未初始化时跳过
        int playerType = PlayConfig.getPlayType();
        int renderType = PlayConfig.getRenderType();
        String ijkCode = PlayConfig.getIjkCodec();
        int scale = PlayConfig.getScaleType();
        try {
            playerType = playerCfg.getInt("pl");
            renderType = playerCfg.getInt("pr");
            ijkCode = playerCfg.getString("ijk");
            scale = playerCfg.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        IJKCode codec = IjkCodecConfigProviders.get().getIJKCodec(ijkCode);
        PlayerFactory playerFactory = com.github.tvbox.osc.player.PlayerKernels.doikkiFactory(playerType, codec);
        if (playerType == 1) com.github.tvbox.osc.player.PlayerKernels.ensureIjkLibrariesLoaded();
        RenderViewFactory renderViewFactory = com.github.tvbox.osc.player.PlayerKernels.renderFactory(renderType);
        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
        videoView.setScreenScaleType(scale);
    }

    public static void updateCfg(VideoView videoView) {
        if (videoView == null) return; // 防御:播放器视图未初始化时跳过
        int playType = PlayConfig.getPlayType();
        PlayerFactory playerFactory = com.github.tvbox.osc.player.PlayerKernels.doikkiFactory(playType, null);
        if (playType == 1) com.github.tvbox.osc.player.PlayerKernels.ensureIjkLibrariesLoaded();
        int renderType = PlayConfig.getRenderType();
        RenderViewFactory renderViewFactory = com.github.tvbox.osc.player.PlayerKernels.renderFactory(renderType);
        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
    }


    public static void init() {
        // IJK 动态库加载单点(PlayerKernels)
        com.github.tvbox.osc.player.PlayerKernels.ensureIjkLibrariesLoaded();
    }

    public static String getPlayerName(int playType) {
        HashMap<Integer, String> playersInfo = getPlayersInfo();
        if (playersInfo.containsKey(playType)) {
            return playersInfo.get(playType);
        } else {
            return "系统播放器";
        }
    }

    private static HashMap<Integer, String> mPlayersInfo = null;
    public static HashMap<Integer, String> getPlayersInfo() {
        if (mPlayersInfo == null) {
            HashMap<Integer, String> playersInfo = new HashMap<>();
            playersInfo.put(0, "系统播放器");
            playersInfo.put(1, "IJK播放器");
            playersInfo.put(2, "Exo播放器");
            playersInfo.put(10, "MX播放器");
            playersInfo.put(11, "Reex播放器");
            playersInfo.put(12, "Kodi播放器");
            playersInfo.put(13, "附近TVBox");
            playersInfo.put(14, "VLC播放器");
            mPlayersInfo = playersInfo;
        }
        return mPlayersInfo;
    }

    private static HashMap<Integer, Boolean> mPlayersExistInfo = null;
    public static HashMap<Integer, Boolean> getPlayersExistInfo() {
        if (mPlayersExistInfo == null) {
            HashMap<Integer, Boolean> playersExist = new HashMap<>();
            playersExist.put(0, false);
            playersExist.put(1, true);
            playersExist.put(2, true);
            playersExist.put(10, MXPlayer.getPackageInfo() != null);
            playersExist.put(11, ReexPlayer.getPackageInfo() != null);
            playersExist.put(12, Kodi.getPackageInfo() != null);
            playersExist.put(13, RemoteTVBox.getAvalible() != null);
            playersExist.put(14, VlcPlayer.getPackageInfo() != null);
            mPlayersExistInfo = playersExist;
        }
        return mPlayersExistInfo;
    }

    public static Boolean getPlayerExist(int playType) {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.get(playType);
        } else {
            return false;
        }
    }

    public static ArrayList<Integer> getExistPlayerTypes() {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        ArrayList<Integer> existPlayers = new ArrayList<>();
        for(Integer playerType : playersExistInfo.keySet()) {
            if (playersExistInfo.get(playerType)) {
                existPlayers.add(playerType);
            }
        }
        return existPlayers;
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers) {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers);
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        boolean callResult = false;
        switch (playerType) {
            case 10: {
                callResult = MXPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 11: {
                callResult = ReexPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 12: {
                callResult = Kodi.run(activity, url, title, subtitle, headers);
                break;
            }
            case 13: {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers);
                break;
            }
            case 14: {
                callResult = VlcPlayer.run(activity, url, title, subtitle, progress);
                break;
            }
        }
        return callResult;
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    public static String getScaleName(int screenScaleType) {
        String scaleText = "默认";
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_DEFAULT:
                scaleText = "默认";
                break;
            case VideoView.SCREEN_SCALE_16_9:
                scaleText = "16:9";
                break;
            case VideoView.SCREEN_SCALE_4_3:
                scaleText = "4:3";
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                scaleText = "填充";
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                scaleText = "原始";
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                scaleText = "裁剪";
                break;
        }
        return scaleText;
    }

    public static String getDisplaySpeed(long speed) {
        if(speed > 1048576)
            return new DecimalFormat("#.00").format(speed / 1048576d) + "Mb/s";
        else if(speed > 1024)
            return (speed / 1024) + "Kb/s";
        else if (speed > 0)
            return speed + "B/s";
        else
            return "0Kb/s"; // 缓冲暂停瞬间速度=0:稳定占位,避免文字内容忽空造成"网速时显时不显"
    }

    // ---- 全局下载网速(与内核无关) ----
    // 原理与 ExoMediaPlayer 一致:用 TrafficStats 统计本应用(Uid)接收字节差分,
    // 无论 系统/ijk/exo 哪个内核,只要在播放,应用整体流量即真实下载速度。
    private static long sRxBaseline = -1;
    private static long sSampleTime = 0;
    private static long sSmoothSpeed = -1;

    /** 采样一次本应用实时下载速度(bytes/s),内核无关;约 1s 调一次 */
    public static long sampleNetworkSpeed() {
        Context ctx = com.github.tvbox.osc.base.App.getInstance();
        if (ctx == null) return 0;
        long total;
        try {
            long uidRx = android.net.TrafficStats.getUidRxBytes(ctx.getApplicationInfo().uid);
            total = uidRx == android.net.TrafficStats.UNSUPPORTED
                    ? android.net.TrafficStats.getTotalRxBytes() : uidRx;
        } catch (Throwable th) {
            total = android.net.TrafficStats.getTotalRxBytes();
        }
        long time = System.currentTimeMillis();
        if (sRxBaseline < 0 || sSampleTime == 0) {
            sRxBaseline = total;
            sSampleTime = time;
            return 0;
        }
        long dt = time - sSampleTime;
        long diff = total - sRxBaseline;
        if (diff < 0) diff = 0;
        sRxBaseline = total;
        sSampleTime = time;
        long sample = dt <= 0 ? 0 : diff * 1000 / dt;
        sSmoothSpeed = sSmoothSpeed < 0 ? sample : (sSmoothSpeed + sample) / 2;
        return sSmoothSpeed;
    }
}
