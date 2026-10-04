package com.github.tvbox.osc.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 只探测媒体地址的 DNS/TCP/TLS 连接，不发送目标 HTTP 请求。
 * 第一个 network interceptor 在建连及 TLS 校验完成后立即中断，Call 的失败回调将其识别为成功。
 */
public final class PlaybackConnectionProbe {
    private static final long DEFAULT_TIMEOUT_MS = 8000L;

    public enum Result {
        CONNECTED,
        CONNECTION_FAILED,
        INCONCLUSIVE
    }

    public enum Mode {
        /** 与 Media3 一样使用播放客户端的 DNS/TLS；明确握手失败也算连接失败。 */
        MEDIA3,
        /** IJK 使用原生取流；系统 DNS 的明确解析/建连失败才可拦截。 */
        IJK_NATIVE
    }

    /** 回调在 OkHttp 后台线程执行；调用方自行切回 UI 线程。 */
    public interface Callback {
        void onResult(Result result, IOException error);
    }

    private PlaybackConnectionProbe() {
    }

    /** 返回可取消的 Call。参数无效时返回 null 并同步回调 INCONCLUSIVE。 */
    public static Call probe(OkHttpClient playbackClient, String realUrl, Callback callback) {
        return probe(playbackClient, realUrl, Mode.MEDIA3, callback, DEFAULT_TIMEOUT_MS);
    }

    /** IJK 使用系统 DNS；Java TLS 与 IJK 原生 TLS 不一致时不据此拦截播放。 */
    public static Call probe(OkHttpClient playbackClient, String realUrl, Mode mode,
                             Callback callback) {
        return probe(playbackClient, realUrl, mode, callback, DEFAULT_TIMEOUT_MS);
    }

    /** 测试入口使用较短时限；正式入口固定为 8 秒。 */
    static Call probe(OkHttpClient playbackClient, String realUrl, Callback callback,
                      long timeoutMs) {
        return probe(playbackClient, realUrl, Mode.MEDIA3, callback, timeoutMs);
    }

    static Call probe(OkHttpClient playbackClient, String realUrl, Mode mode,
                      Callback callback, long timeoutMs) {
        if (playbackClient == null || realUrl == null || mode == null || callback == null
                || timeoutMs <= 0) {
            if (callback != null) callback.onResult(Result.INCONCLUSIVE,
                    new IOException("connection probe arguments invalid"));
            return null;
        }

        final Request request;
        try {
            request = new Request.Builder().url(realUrl).build();
        } catch (RuntimeException error) {
            callback.onResult(Result.INCONCLUSIVE, new IOException("connection probe URL invalid", error));
            return null;
        }

        // 新池保证本次确实重新建连，不把已有但可能失效的空闲连接当作可达证据。
        ProbeEvents events = new ProbeEvents();
        OkHttpClient.Builder probeBuilder = playbackClient.newBuilder()
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                .eventListener(events)
                .cache(null);
        if (mode == Mode.IJK_NATIVE) {
            // IJK 原生取流不走 Java ProxySelector/DoH；沿用系统路由与系统 DNS。
            probeBuilder.proxy(Proxy.NO_PROXY);
            probeBuilder.dns(Dns.SYSTEM);
        }
        // 沿用 DNS/代理/TLS 配置，去掉可能短路、打 BODY 日志或自行发请求的原有拦截器。
        probeBuilder.interceptors().clear();
        probeBuilder.networkInterceptors().clear();
        // network interceptor 只在 DNS/TCP/TLS 连接完成后执行，且绝不调用 proceed。
        probeBuilder.addNetworkInterceptor(chain -> {
            throw new ConnectionEstablished();
        });
        Call call = probeBuilder.build().newCall(request);
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
        call.enqueue(new CallbackAdapter(callback, mode, events));
        return call;
    }

    private static final class ProbeEvents extends EventListener {
        private volatile boolean tcpStarted;
        private volatile boolean tlsStarted;
        private volatile boolean proxied;

        @Override
        public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
            tcpStarted = true;
            if (proxy != null && proxy.type() != Proxy.Type.DIRECT) proxied = true;
        }

        @Override
        public void secureConnectStart(Call call) {
            // 一次 Call 可尝试多个 IP；只要有一次进入 TLS，就不能把最后的错误
            // 当作所有原生 IJK 连接都不可达的证据。
            tlsStarted = true;
        }
    }

    private static final class CallbackAdapter implements okhttp3.Callback {
        private final Callback callback;
        private final Mode mode;
        private final ProbeEvents events;

        CallbackAdapter(Callback callback, Mode mode, ProbeEvents events) {
            this.callback = callback;
            this.mode = mode;
            this.events = events;
        }

        @Override
        public void onFailure(Call call, IOException error) {
            if (call.isCanceled()) {
                callback.onResult(Result.INCONCLUSIVE, error);
            } else if (reachedNetworkInterceptor(error)) {
                callback.onResult(Result.CONNECTED, null);
            } else {
                callback.onResult(isClearConnectionFailure(error, mode, events)
                        ? Result.CONNECTION_FAILED : Result.INCONCLUSIVE, error);
            }
        }

        @Override
        public void onResponse(Call call, Response response) {
            // 不应发生：探针拦截器永远不会调用 proceed，也就不会产生目标 HTTP 响应。
            response.close();
            callback.onResult(Result.INCONCLUSIVE,
                    new IOException("connection probe unexpectedly received HTTP response"));
        }
    }

    private static boolean reachedNetworkInterceptor(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectionEstablished) return true;
        }
        return false;
    }

    private static boolean isClearConnectionFailure(Throwable error, Mode mode, ProbeEvents events) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (mode == Mode.MEDIA3
                    && NetworkGuardInterceptor.NO_NETWORK_MESSAGE.equals(cause.getMessage())) return true;
            if (cause instanceof InterruptedIOException) return false; // 总时限/取消可能发生在任意阶段。
        }
        // OkHttp 的 DoH 已换成系统 DNS，但它的代理和 TLS 栈仍可能与 IJK 不同。
        if (mode == Mode.IJK_NATIVE && (events.tlsStarted || events.proxied)) return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (mode == Mode.IJK_NATIVE) {
                if (cause instanceof UnknownHostException || cause instanceof ConnectException
                        || cause instanceof NoRouteToHostException) return true;
                if (events.tcpStarted && cause instanceof SocketException) return true;
            } else if (cause instanceof UnknownHostException || cause instanceof ConnectException
                    || cause instanceof NoRouteToHostException || cause instanceof SSLException
                    || cause instanceof SocketException) return true;
        }
        return false;
    }

    /** ProtocolException 在 OkHttp 中不可重试，避免已连通后又尝试下一个 IP。 */
    private static final class ConnectionEstablished extends ProtocolException {
        ConnectionEstablished() {
            super("connection established; HTTP request intentionally skipped");
        }
    }
}
