package com.github.tvbox.osc.server;

import android.content.Context;

import com.github.tvbox.osc.config.SystemConfig;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * @author pj567
 * @date :2021/1/4
 * @description:
 */
public class ControlManager {
    private static ControlManager instance;
    private RemoteServer mServer = null;
    public static Context mContext;

    private ControlManager() {

    }

    public static ControlManager get() {
        if (instance == null) {
            synchronized (ControlManager.class) {
                if (instance == null) {
                    instance = new ControlManager();
                }
            }
        }
        return instance;
    }

    public static void init(Context context) {
        mContext = context;
    }

    public String getAddress(boolean local) {
        return local ? mServer.getLoadAddress() : mServer.getServerAddress();
    }

    public void startServer() {
        if (mServer != null) {
            return;
        }
        // 默认仅绑定本机回环:本 App 的订阅/本地播放/代理全部走 127.0.0.1,无需对局域网开放端口。
        // 需要局域网文件共享/远程管理(web 控制台)时,显式开启 HawkConfig.LAN_SERVER_ENABLE 后重启生效。
        boolean lanEnabled = SystemConfig.isLanServerEnabled();
        do {
            mServer = new RemoteServer(lanEnabled ? null : "127.0.0.1", RemoteServer.serverPort, mContext);
            mServer.setDataReceiver(new DataReceiver() {
                @Override
                public void onTextReceived(String text) {
                    // 历史遗留:曾广播 SearchReceiver 触发局域网推送搜索,但 SearchReceiver.onReceive
                    // 早已是空实现(SERVER_SEARCH 从无发送方),整条链路已死,删除。
                }

                @Override
                public void onApiReceived(String url) {
                    // 历史遗留:曾以 TYPE_API_URL_CHANGE 广播,全仓零订阅(未接线),删除
                }

                @Override
                public void onPushReceived(String url) {
                    // 历史遗留:曾以 TYPE_PUSH_URL 广播,全仓零订阅(InputRequestProcess 亦标注"暂未实现"),删除
                }
            });
            try {
                mServer.start();
                IjkMediaPlayer.setDotPort(SystemConfig.getDohUrl() > 0, RemoteServer.serverPort);
                // server 就绪后注入局域网地址(:spider 模块 ApiConfig 用,替代直接依赖本类)
                try {
                    com.github.tvbox.osc.api.ApiConfig.setLanBase(mServer.getLoadAddress());
                } catch (Throwable ignored) {
                }
                break;
            } catch (IOException ex) {
                RemoteServer.serverPort++;
                mServer.stop();
            }
        } while (RemoteServer.serverPort < 9999);
    }

    public void stopServer() {
        if (mServer != null && mServer.isStarting()) {
            mServer.stop();
        }
    }
}