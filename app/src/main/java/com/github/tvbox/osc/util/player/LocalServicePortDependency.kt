package com.github.tvbox.osc.util.player

import java.net.URI
import java.util.Locale

/**
 * 判断一个播放地址是否"写死了本机服务的某个端口"。
 *
 * 为什么要单独一个判定:本机 HTTP 服务([com.github.tvbox.osc.server.RemoteServer])默认监听
 * 9978,被占时会回退到 9979…9999,并把**实际端口**注入源配置(`ApiConfig.loopbackBase`)与代理
 * 调用方 —— 走注入值的源在回退端口下照样能播。只有自己拼死 `127.0.0.1:9978` 的第三方 jar/JS 源
 * 才会真的取不到地址。
 *
 * 端口占用提示只该为后者亮;能正常播放却提示"端口被占用",对用户就是误报
 * (用户口径 2026-11-20)。
 *
 * 纯 URI 解析,不碰 Android API,JVM 单测可直接跑。
 */
object LocalServicePortDependency {

    /**
     * [url] 是否指向本机 [port] —— 即"这个地址把本机服务端口写死了"。
     *
     * @param port 被占用的默认端口(通常 9978);`<= 0` 时一律返回 false
     */
    @JvmStatic
    fun dependsOn(url: String?, port: Int): Boolean {
        if (url.isNullOrBlank() || port <= 0) return false
        val uri = try {
            URI.create(url.trim())
        } catch (e: IllegalArgumentException) {
            return false
        }
        val scheme = uri.scheme ?: return false
        if (!scheme.equals("http", true) && !scheme.equals("https", true)) return false
        // 只看 host + 显式端口:没有写端口的 `http://127.0.0.1/x` 走的是 80,不依赖本机服务
        if (!isLoopbackHost(uri.host)) return false
        return uri.port == port
    }

    /**
     * 回环主机名。IPv6 在 URI 里带方括号(`[::1]`),映射地址 `[::ffff:127.0.0.1]`
     * 也是本机(与 `CastMediaRules` 的口径保持一致)。
     */
    private fun isLoopbackHost(host: String?): Boolean {
        if (host == null) return false
        var h = host.lowercase(Locale.ROOT)
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        return h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "::ffff:127.0.0.1"
    }
}
