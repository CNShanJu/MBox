package com.github.tvbox.osc.util.player;

import android.util.Log;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.DiagnosticLogLimiter;
import xyz.doikki.videoplayer.player.PlaybackErrorReporter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.LinkedHashSet;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Protocol;

/** 播放取流连接失败时的短诊断；只记连接信息，不记录响应正文或请求头。 */
public final class PlaybackConnectionDiagnostics extends EventListener {
    private static final int MAX_ENDPOINTS = 8;
    private final String url;
    private final LinkedHashSet<String> endpoints = new LinkedHashSet<>();
    private boolean handshakeStarted;
    private boolean handshakeFailed;
    private boolean responseStarted;

    public PlaybackConnectionDiagnostics(String url) {
        this.url = url == null ? "" : url;
    }

    @Override
    public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
        responseStarted = false;
        remember(address, proxy);
    }

    @Override
    public void secureConnectStart(Call call) {
        handshakeStarted = true;
    }

    @Override
    public void secureConnectEnd(Call call, Handshake handshake) {
        handshakeStarted = false;
        handshakeFailed = false;
    }

    @Override
    public void connectFailed(Call call, InetSocketAddress address, Proxy proxy,
                              Protocol protocol, IOException error) {
        remember(address, proxy);
        if (handshakeStarted) handshakeFailed = true;
        handshakeStarted = false;
    }

    @Override
    public void responseHeadersStart(Call call) {
        responseStarted = true;
    }

    @Override
    public void callFailed(Call call, IOException error) {
        if (!url.startsWith("https://") || responseStarted) return;
        String doh = OkGoHelper.currentDohUrl();
        String detail = "播放 HTTPS 连接失败: 地址=" + PlaybackErrorReporter.source(url)
                + "，阶段=" + (handshakeFailed ? "TLS握手" : handshakeStarted ? "TLS握手" : "建连")
                + "，连接=" + (endpoints.isEmpty() ? "未知" : endpoints)
                + "，DNS=" + (doh == null || doh.isEmpty() ? "系统" : PlaybackErrorReporter.source(doh))
                + "，环境=" + PlaybackErrorReporter.safeDiagnosticText(OkGoHelper.dnsEnvHint())
                + "，原因=" + PlaybackErrorReporter.cause(error);
        if (!DiagnosticLogLimiter.SHARED.allow("playback_network:" + detail,
                android.os.SystemClock.elapsedRealtime())) return;
        LogStore.fail(Category.PLAYER, detail);
        Log.e("MBoxNetwork", detail);
    }

    private void remember(InetSocketAddress address, Proxy proxy) {
        if (address == null || endpoints.size() >= MAX_ENDPOINTS) return;
        InetAddress ip = address.getAddress();
        String endpoint = (ip == null ? address.getHostString() : ip.getHostAddress())
                + ':' + address.getPort();
        if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
            endpoint += "(代理:" + proxy.type() + ')';
        }
        endpoints.add(endpoint);
    }

}
