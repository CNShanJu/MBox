package com.github.tvbox.osc.util.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端口占用提示只该为"写死 127.0.0.1:9978"的源亮。走注入端口的源在回退端口下能正常播,
 * 对它们提示端口被占就是误报(用户口径 2026-11-20)。
 */
class LocalServicePortDependencyTest {

    @Test
    fun loopbackAddressOnDefaultPortDependsOnIt() {
        assertTrue(LocalServicePortDependency.dependsOn(
            "http://127.0.0.1:9978/proxy?do=js&from=catvod&url=https%3A%2F%2Fcdn.example%2Fa.m3u8",
            9978))
        assertTrue(LocalServicePortDependency.dependsOn("http://localhost:9978/proxy?do=live", 9978))
        assertTrue(LocalServicePortDependency.dependsOn("http://[::1]:9978/purify.m3u8", 9978))
        assertTrue(LocalServicePortDependency.dependsOn(
            "http://[::ffff:127.0.0.1]:9978/proxy?do=js", 9978))
    }

    @Test
    fun fallbackPortIsInjectedSoItDoesNotDependOnDefault() {
        // 服务已回退到 9979:源拿到的就是注入端口,能正常播 → 不该提示
        assertFalse(LocalServicePortDependency.dependsOn(
            "http://127.0.0.1:9979/proxy?do=js&url=https%3A%2F%2Fcdn.example%2Fa.m3u8", 9978))
    }

    @Test
    fun externalAndNonHttpAddressesNeverDependOnLocalService() {
        assertFalse(LocalServicePortDependency.dependsOn("https://cdn.example/show/index.m3u8", 9978))
        assertFalse(LocalServicePortDependency.dependsOn("http://cdn.example:9978/live.m3u8", 9978))
        assertFalse(LocalServicePortDependency.dependsOn("rtmp://127.0.0.1:9978/live", 9978))
        assertFalse(LocalServicePortDependency.dependsOn("file:///sdcard/movie.mp4", 9978))
    }

    @Test
    fun missingPortOrHostIsNotTreatedAsDependency() {
        // 没写端口 = 走 80,不是本机服务
        assertFalse(LocalServicePortDependency.dependsOn("http://127.0.0.1/proxy.do", 9978))
        // userinfo 伪装成回环地址时以真实 host 为准
        assertFalse(LocalServicePortDependency.dependsOn("http://127.0.0.1:9978@evil.example/x", 9978))
        assertFalse(LocalServicePortDependency.dependsOn("not a url", 9978))
        assertFalse(LocalServicePortDependency.dependsOn(null, 9978))
        assertFalse(LocalServicePortDependency.dependsOn("", 9978))
        assertFalse(LocalServicePortDependency.dependsOn("http://127.0.0.1:9978/proxy", 0))
    }
}
