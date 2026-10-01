package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

public class SubscriptionImportRulesTest {
    @Test
    public void addressesRejectMalformedAndUnsafeEntries() {
        assertTrue(SubscriptionImportRules.isSupportedAddress("https://example.com/config.json"));
        assertTrue(SubscriptionImportRules.isSupportedAddress("http://www.饭太硬.net/tv"));
        assertTrue(SubscriptionImportRules.isSupportedAddress("clan://localhost/Android/data/mbox/a.json"));
        assertTrue(SubscriptionImportRules.isSupportedAddress("clan://localhost/Android/data/本地订阅.json"));
        assertFalse(SubscriptionImportRules.isSupportedAddress("httpx://example.com"));
        assertFalse(SubscriptionImportRules.isSupportedAddress("https://"));
        assertFalse(SubscriptionImportRules.isSupportedAddress("javascript:alert(1)"));
        assertFalse(SubscriptionImportRules.isSupportedAddress("clan://localhost/a/%2e%2e/b.json"));
    }

    @Test
    public void bookSourceDoesNotBecomeSubscriptionListEntry() {
        assertTrue(SubscriptionImportRules.mayBeListEntry(JsonParser.parseString(
                "{\"sourceName\":\"订阅\",\"sourceUrl\":\"https://example.com/config.json\"}").getAsJsonObject()));
        assertFalse(SubscriptionImportRules.mayBeListEntry(JsonParser.parseString(
                "{\"sourceName\":\"书源\",\"sourceUrl\":\"https://example.com\",\"ruleSearch\":\"xxx\"}").getAsJsonObject()));
        assertFalse(SubscriptionImportRules.mayBeListEntry(JsonParser.parseString(
                "{\"name\":\"配置\",\"url\":\"https://example.com\",\"sites\":[]}").getAsJsonObject()));
    }

    @Test
    public void embeddedContentUsesSameShapeRulesAsLocalImport() {
        assertNotNull(SubscriptionImportRules.normalizedContent("{\"sites\":[]}"));
        assertNotNull(SubscriptionImportRules.normalizedContent(
                "{\"key\":\"site\",\"name\":\"站点\",\"type\":1,\"api\":\"https://example.com/api\"}"));
        assertNull(SubscriptionImportRules.normalizedContent(
                "{\"sourceName\":\"书源\",\"sourceUrl\":\"https://example.com\",\"ruleSearch\":\"xxx\"}"));
        assertNull(SubscriptionImportRules.normalizedContent("{\"lives\":[]}"));
    }

    @Test
    public void accessDeniedBodyIsNotTreatedAsSitePage() {
        assertTrue(SubscriptionImportRules.isAccessDeniedResponse("Request Forbidden"));
        assertTrue(SubscriptionImportRules.isAccessDeniedResponse("<html><title>403 Forbidden</title></html>"));
        assertFalse(SubscriptionImportRules.isAccessDeniedResponse(
                "<html><title>首页</title><body>欢迎访问</body></html>"));
        assertFalse(SubscriptionImportRules.isAccessDeniedResponse("{\"sites\":[]}"));
    }
}
