package com.github.tvbox.osc.util;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

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
        if (OkGoHelper.hasNetwork()) return proceed(chain, request);
        if (hasNetworkAfterBriefWait()) return proceed(chain, request);
        // 断网 + 真的发起了请求:通知上层弹"网络不可用"页(节流在 notifyNetworkIssue 里)
        OkGoHelper.notifyNetworkIssue("无网络");
        throw new IOException(NO_NETWORK_MESSAGE);
    }

    /**
     * 放行请求,并把"发到一半断网"也归到同一类通知上。
     * <p>
     * 这正是"请求发出时还有网、中途断了"的那种情况:表现形式不是快速失败,而是
     * UnknownHost(切网/掉线后重解析)、ConnectException/NoRouteToHost(新连接建不起来)、
     * SocketException(连接被重置/中断)。读超时只在"确认没网"时才算(否则可能只是源站慢)。
     */
    private Response proceed(Chain chain, Request request) throws IOException {
        try {
            return chain.proceed(request);
        } catch (IOException e) {
            if (isNetworkDrop(e)) {
                OkGoHelper.notifyNetworkIssue(request.url().host() + " " + e.getClass().getSimpleName());
            }
            throw e;
        }
    }

    private static boolean isNetworkDrop(IOException e) {
        if (e instanceof UnknownHostException
                || e instanceof ConnectException
                || e instanceof NoRouteToHostException
                || e instanceof SocketException) {
            return true;
        }
        return e instanceof SocketTimeoutException && !OkGoHelper.hasNetwork();
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
