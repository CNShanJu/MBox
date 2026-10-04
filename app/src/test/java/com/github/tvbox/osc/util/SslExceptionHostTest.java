package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.config.SystemConfig;

import org.junit.Test;

public class SslExceptionHostTest {
    @Test
    public void exactHostNormalizationRejectsWildcardAndUrlSyntax() {
        assertEquals("example.com", SystemConfig.normalizeSslHost(" ExAmPlE.COM "));
        assertEquals("", SystemConfig.normalizeSslHost("*.example.com"));
        assertEquals("", SystemConfig.normalizeSslHost("example.com:443"));
        assertEquals("", SystemConfig.normalizeSslHost("https://example.com"));
        assertEquals("", SystemConfig.normalizeSslHost("sub..example.com"));
    }
}
