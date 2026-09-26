package com.github.tvbox.osc.util;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 无网络时的"快速失败"拦截器：判定当前确实没有网络时，<b>不把请求发出去</b>，
 * 直接以明确原因失败（{@link #NO_NETWORK_MESSAGE}）。
 * <p>
 * 为什么需要：断网时请求照样发出去，最终报回来的是
 * {@code UnknownHostException: Unable to resolve host "xxx": No address associated with hostname} ——
 * 这句话看上去像"域名/服务器坏了"，实测让排查方向跑偏了两轮（先怀疑安全 DNS、再怀疑域名被 DNS 拦截，
 * 实际只是手机没网）。快速失败能让错误一眼看懂，也省掉一次白等的超时。
 * <p>
 * 为什么放在 :core-network 的公共 Builder：爬虫/API/下载/图片/播放客户端都从这里起手，
 * 一处装配全局受益，各业务模块不必各自判断网络状态。
 * <p>
 * <b>宁可漏拦，不可错杀</b>：判定口径宽松（有任何带 INTERNET 能力的网络就算有网）、
 * 对"网络切换瞬间"做一次短复检、回环地址一律放行、自身异常一律放行（见 {@link OkGoHelper#hasNetwork()}）。
 */
public final class NetworkGuardInterceptor implements Interceptor {

    /** 失败原因文案（会被各业务的错误日志/提示原样带出） */
    public static final String NO_NETWORK_MESSAGE = "当前无网络,请检查网络连接";

    /** 复检间隔：Wi-Fi↔移动数据 切换瞬间默认网络可能瞬时为 null，隔一小会儿再判一次 */
    private static final long RECHECK_DELAY_MS = 150L;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (isLocal(request)) return chain.proceed(request);
        if (OkGoHelper.hasNetwork()) return chain.proceed(request);
        if (hasNetworkAfterBriefWait()) return chain.proceed(request);
        throw new IOException(NO_NETWORK_MESSAGE);
    }

    /**
     * 回环地址放行：本机回环服务（RemoteServer 的代理播放/净化清单等）跟"有没有外网"无关，
     * 断网时也必须能用。只看字面量，不做域名解析（拦截器里解析域名等于自找麻烦）。
     */
    private static boolean isLocal(Request request) {
        String host = request.url().host();
        if (host == null) return true;
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || host.startsWith("127.")
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private boolean hasNetworkAfterBriefWait() {
        try {
            Thread.sleep(RECHECK_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        return OkGoHelper.hasNetwork();
    }
}
