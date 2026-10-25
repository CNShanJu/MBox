package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class LanPairQrTest {
    @Test public void generatedCodeRoundTrips() {
        String encoded = LanPairQr.encode("http://192.168.1.23:9978/", "123456");
        LanPairQr.Pair parsed = LanPairQr.decode(encoded);
        assertNotNull(parsed);
        assertEquals("http://192.168.1.23:9978/", parsed.address);
        assertEquals("123456", parsed.code);
    }

    @Test public void ignoresUnrelatedAndTamperedQrCodes() {
        assertNull(LanPairQr.decode("https://example.com/"));
        assertNull(LanPairQr.decode("mbox://lan-pair?address=http%3A%2F%2F8.8.8.8%3A9978%2F&code=123456"));
        assertNull(LanPairQr.decode("mbox://lan-pair?address=http%3A%2F%2F192.168.1.23%3A9978%2F&code=123456&code=654321"));
        assertNull(LanPairQr.decode("mbox://lan-pair?address=http%3A%2F%2F192.168.1.23%3A9978%2F&code=1234"));
    }
}
