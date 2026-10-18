package com.github.tvbox.osc.server;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.share.ShareException;
import com.github.tvbox.osc.share.SharePackage;
import com.github.tvbox.osc.share.transport.lan.LanImportReceiver;
import com.github.tvbox.osc.share.transport.lan.LanShareHost;
import com.github.tvbox.osc.share.transport.lan.LanShareOptions;
import com.github.tvbox.osc.share.transport.lan.LanShareSession;

import java.util.List;

/**
 * {@link LanShareHost} 的 :app 实现:把 {@link ControlManager} 与 {@link RemoteServer}
 * 的局域网分享能力桥接给 :share 传输层。
 *
 * <p>:share 不得依赖 :app(门禁),本类正是这条依赖的出口 —— 复用唯一的 RemoteServer
 * 实例(端口/令牌/绑定方式都是一份),不在 :app 之外再起监听端口或暴露面(AGENTS §七)。
 */
public final class AppLanShareHost implements LanShareHost {

    @Override
    public boolean isLanServiceEnabled() {
        return SystemConfig.isLanServerEnabled();
    }

    @Override
    public boolean isLanBound() {
        return ControlManager.get().isLanBound();
    }

    @NonNull
    @Override
    public List<String> accessUrls() {
        return ControlManager.get().getLanAccessUrls();
    }

    @NonNull
    @Override
    public LanShareSession publish(@NonNull SharePackage pkg, @NonNull LanShareOptions opts) throws ShareException {
        RemoteServer server = ControlManager.get().getServer();
        if (server == null || !server.isStarting()) {
            throw ShareException.unavailable("局域网服务未启动");
        }
        return server.publishShare(pkg, opts);
    }

    @Override
    public void retract(@Nullable String sessionId) {
        RemoteServer server = ControlManager.get().getServer();
        if (server != null) server.retractShare(sessionId);
    }

    @NonNull
    @Override
    public String importUrl() {
        RemoteServer server = ControlManager.get().getServer();
        if (server == null) return "";
        return server.getShareImportUrl();
    }

    @Override
    public void setImportReceiver(@Nullable LanImportReceiver receiver) {
        RemoteServer server = ControlManager.get().getServer();
        if (server != null) server.setShareImportReceiver(receiver);
    }
}
