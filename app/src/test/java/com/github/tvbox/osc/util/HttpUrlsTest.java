package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** HttpUrls(URL 拼装/归一,自 HttpClient 抽出)与旧内联语义等值的 JVM 回归 */
public class HttpUrlsTest {

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void buildUrl_nullOrEmptyParamsReturnsUrlAsIs() {
        String url = "https://api.example.com/api.php";
        assertEquals(url, HttpUrls.buildUrl(url, null));
        assertEquals(url, HttpUrls.buildUrl(url, new LinkedHashMap<String, String>()));
    }

    @Test
    public void buildUrl_appendsQueryWithEncoding() throws Exception {
        String url = "https://api.example.com/api.php";
        String out = HttpUrls.buildUrl(url, params("wd", "你好 world", "ac", "detail"));
        // OkHttp HttpUrl 严格拼接:参数按 UTF-8 百分号编码;取 query 解码回原文验证
        assertTrue(out.startsWith("https://api.example.com/api.php?"));
        assertTrue(out.indexOf('?') < out.indexOf('&'));
        int q = out.indexOf('?');
        String query = out.substring(q + 1);
        java.util.Map<String, String> decoded = new java.util.HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                decoded.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        assertEquals("你好 world", decoded.get("wd"));
        assertEquals("detail", decoded.get("ac"));
    }

    @Test
    public void buildUrl_keepsExistingQueryWithAmpersand() {
        String out = HttpUrls.buildUrl("https://x.example.com/a.php?k=1", params("pg", "2"));
        assertTrue(out.startsWith("https://x.example.com/a.php?k=1"));
        assertTrue(out.contains("&pg=2"));
    }

    @Test
    public void buildUrl_skipsNullKeyOrValue() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(null, "v");
        m.put("k", null);
        m.put("ok", "1");
        String out = HttpUrls.buildUrl("https://x.example.com/a.php", m);
        assertTrue(out.contains("ok=1"));
    }

    @Test
    public void buildUrl_invalidUrlFallsBackToLooseConcat() {
        // HttpUrl 解析失败的宽松降级(旧 OkGo 语义):不抛异常、参数追加
        String bad = "not-a-valid-url";
        String out = HttpUrls.buildUrl(bad, params("a", "1"));
        assertTrue(out.contains("not-a-valid-url"));
        assertTrue(out.contains("a=1"));
    }

    @Test
    public void normalizeUrl_keepsAsciiUrl() {
        assertEquals("https://a.example.com/p?a=1", HttpUrls.normalizeUrl("https://a.example.com/p?a=1"));
        assertNull(HttpUrls.normalizeUrl(null));
    }

    @Test
    public void normalizeUrl_convertsChineseDomainToPunycode() {
        String out = HttpUrls.normalizeUrl("https://中文.example.com/path?q=1");
        // OkHttp 不接受非 ASCII 域名,中文域名应转 punycode
        assertTrue(out.startsWith("https://"));
        assertTrue(out.contains("xn--"));
        assertTrue(out.endsWith("/path?q=1"));
    }
}
