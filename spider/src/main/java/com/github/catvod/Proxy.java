package com.github.catvod;

/**
 * 本机 HTTP 服务端口(旧版 catvod 框架里的固定位置)。
 * <p>
 * 第三方爬虫 jar 里普遍引用 {@code com.github.catvod.Proxy}，用它拼
 * {@code http://127.0.0.1:<port>/file/...}、{@code /proxy?...} 这类**本机**地址：
 * 读自己写下的 cookie、把播放地址套一层本地代理等等。
 * <p>
 * MBox 此前既没有这个类、也没有任何注入点，于是这类 jar 只能拿到它自己编译期写死的
 * 兜底值——拼出来的地址是死的，它不会发出任何请求，直接返回空字符串。外部表现就是
 * "1 毫秒内失败、日志里连一条网络请求都没有"。
 * <p>
 * 端口由 App 侧在本地服务就绪后经
 * {@link com.github.tvbox.osc.api.ApiConfig#setLanBase(String)} 同步过来。
 */
public final class Proxy {

    private static volatile int port = 9978;

    private Proxy() {
    }

    /** 只接受合法端口，避免把 0/负数写进来把地址拼成 {@code http://127.0.0.1:0}。 */
    public static void set(int value) {
        if (value > 0 && value <= 65535) port = value;
    }

    public static int get() {
        return port;
    }

    /** 回环基址，带结尾斜杠。 */
    public static String getUrl() {
        return "http://127.0.0.1:" + port + "/";
    }
}
