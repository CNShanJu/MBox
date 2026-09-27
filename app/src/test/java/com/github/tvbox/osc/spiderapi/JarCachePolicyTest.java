package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 订阅爬虫 jar 的缓存取舍规则单测。
 * <p>
 * 对应线上故障:站点声明的类不在 jar 里(如 csp_XPathGuard)→ 整源空白;
 * 起因之一是"带缓存配置重启"这条最常走的路会跳过 md5 校验,把换订阅前的旧 jar 当可用缓存。
 */
public class JarCachePolicyTest {

    // ── cacheUsable:配置带了 md5 就必须校验通过,useCache 不得绕过 ──

    @Test
    public void cacheUsable_matchingMd5WinsWithoutCacheMode() {
        assertTrue(JarCachePolicy.cacheUsable(true, "abc123", "ABC123", false));
    }

    @Test
    public void cacheUsable_mismatchedMd5IsNeverUsable() {
        // 原实现的洞:useCache=true 时 `useCache || md5相符` 短路,换订阅后的旧 jar 被当可用缓存
        assertFalse(JarCachePolicy.cacheUsable(true, "new-md5", "old-md5", true));
        assertFalse(JarCachePolicy.cacheUsable(true, "new-md5", "old-md5", false));
    }

    @Test
    public void cacheUsable_missingFileIsNeverUsable() {
        assertFalse(JarCachePolicy.cacheUsable(false, "abc", "abc", true));
        assertFalse(JarCachePolicy.cacheUsable(false, "", "", true));
    }

    @Test
    public void cacheUsable_withoutMd5OnlyTrustsCacheMode() {
        // 配置没给凭据时无法校验,只能沿用"信任本地缓存"的快速启动语义
        assertTrue(JarCachePolicy.cacheUsable(true, "", "", true));
        assertFalse(JarCachePolicy.cacheUsable(true, "", "", false));
    }

    @Test
    public void cacheUsable_toleratesBlankAndNullMd5() {
        assertTrue(JarCachePolicy.cacheUsable(true, "  abc  ", "abc", false));
        assertFalse(JarCachePolicy.cacheUsable(true, "abc", null, false));
        assertFalse(JarCachePolicy.cacheUsable(true, null, "abc", false));
        assertTrue(JarCachePolicy.cacheUsable(true, null, null, true));
    }

    // ── jarUrlChanged:只认"地址换了";地址没变只是内容变了,那份旧 jar 仍属当前订阅,可作回退 ──

    @Test
    public void jarUrlChanged_detectsSubscriptionSwitch() {
        assertTrue(JarCachePolicy.jarUrlChanged("http://a.com/fan.txt", "https://b.com/fan.jpg"));
    }

    @Test
    public void jarUrlChanged_sameUrlIsNotASwitch() {
        assertFalse(JarCachePolicy.jarUrlChanged("http://a.com/fan.txt", "http://a.com/fan.txt"));
        assertFalse(JarCachePolicy.jarUrlChanged("  http://a.com/fan.txt  ", "http://a.com/fan.txt"));
    }

    @Test
    public void jarUrlChanged_withoutRecordNeverClaimsSwitch() {
        // 首启 / 旧版本升级上来:不能因为"没有记录"就把本地 jar 当别人的清掉
        assertFalse(JarCachePolicy.jarUrlChanged("", "http://a.com/fan.txt"));
        assertFalse(JarCachePolicy.jarUrlChanged(null, "http://a.com/fan.txt"));
        assertFalse(JarCachePolicy.jarUrlChanged("http://a.com/fan.txt", ""));
        assertFalse(JarCachePolicy.jarUrlChanged("  ", "  "));
    }
}
