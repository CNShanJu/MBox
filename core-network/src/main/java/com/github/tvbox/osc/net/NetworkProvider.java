package com.github.tvbox.osc.net;

import okhttp3.OkHttpClient;

/**
 * 客户端提供契约(改进.txt §2):业务模块经本接口取统一客户端,不再自行 new。
 * 客户端共享 ConnectionPool/DNS/DoH/TLS/UA/日志等策略(经 OkGoHelper.newBaseBuilder 派生)。
 */
public interface NetworkProvider {

    /** 常规客户端(跟随重定向) */
    OkHttpClient general();

    /** 免重定向客户端 */
    OkHttpClient noRedirect();

    /** 播放内核客户端(长连接/重试策略) */
    OkHttpClient playback();

    /** 组合根缺省实现(未注入时按 OkGoHelper 构建;一般由 App 组合根提供) */
    NetworkProvider DEFAULT = new NetworkProvider() {
        @Override
        public OkHttpClient general() {
            return com.github.tvbox.osc.util.OkGoHelper.getDefaultClient();
        }

        @Override
        public OkHttpClient noRedirect() {
            return com.github.tvbox.osc.util.OkGoHelper.getNoRedirectClient();
        }

        @Override
        public OkHttpClient playback() {
            return com.github.tvbox.osc.util.OkGoHelper.newBaseBuilder()
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
    };
}
