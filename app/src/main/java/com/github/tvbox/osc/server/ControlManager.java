package com.github.tvbox.osc.server;

import android.content.Context;
import android.util.Log;

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
        final int preferredPort = RemoteServer.serverPort; // 首选端口(默认 9978);被占用时下面循环 +1 重试
        boolean started = false;
        do {
            int tryPort = RemoteServer.serverPort;
            mServer = new RemoteServer(lanEnabled ? null : "127.0.0.1", tryPort, mContext);
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
                started = true;
                // 端口回退可见性:9978 被占时这里静默 +1 重试,而第三方源里写死的
                // 127.0.0.1:9978 代理地址(do=js/do=m3u8 等)会因此连不上(ECONNREFUSED);
                // 应用侧以前不打任何日志,只能靠第三方 adjustPort 日志猜,这里补上实际端口。
                if (tryPort == preferredPort) {
                    Log.i("TVBox-Server", "本机服务已启动: " + mServer.getLoadAddress());
                } else {
                    Log.w("TVBox-Server", preferredPort + " 被占用,本机服务回退到 " + tryPort
                            + ";源里写死 127.0.0.1:" + preferredPort + " 的代理地址会连不上");
                }
                break;
            } catch (IOException ex) {
                RemoteServer.serverPort++;
                mServer.stop();
            }
        } while (RemoteServer.serverPort < 9999);
        if (!started) {
            Log.w("TVBox-Server", "本机服务启动失败:从 " + preferredPort + " 起连续端口都被占用");
        }
    }

    public void stopServer() {
        if (mServer != null && mServer.isStarting()) {
            mServer.stop();
        }
    }
}