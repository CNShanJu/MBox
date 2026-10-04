package com.github.tvbox.osc.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ProxyPlaylistDiagnosticsTest {
    @Test
    public void validManifestIsRecognizedWithoutConsumingResponse() {
        byte[] playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nnext.m3u8"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream body = new ByteArrayInputStream(playlist);

        assertEquals("m3u8", ProxyPlaylistDiagnostics.bodyKind(body));
        byte[] afterProbe = new byte[playlist.length];
        assertEquals(playlist.length, body.read(afterProbe, 0, afterProbe.length));
        assertArrayEquals(playlist, afterProbe);
    }

    @Test
    public void errorPageAndBomAreDistinguishedFromValidManifest() {
        assertEquals("html_or_xml", ProxyPlaylistDiagnostics.bodyKind(
                new ByteArrayInputStream("<html>403 Forbidden</html>".getBytes(StandardCharsets.UTF_8))));
        assertEquals("utf8_bom", ProxyPlaylistDiagnostics.bodyKind(
                new ByteArrayInputStream("\uFEFF#EXTM3U\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals("empty", ProxyPlaylistDiagnostics.bodyKind(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void shortReadingProxyStreamIsProbedAndReplayed() throws Exception {
        byte[] playlist = "#EXTM3U\n#EXTINF:3,\nsegment.ts\n".getBytes(StandardCharsets.UTF_8);
        InputStream oneByteAtATime = new FilterInputStream(new ByteArrayInputStream(playlist)) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        BufferedInputStream body = new BufferedInputStream(oneByteAtATime, 128);

        assertEquals("m3u8", ProxyPlaylistDiagnostics.bodyKind(body));
        byte[] replayed = new byte[playlist.length];
        assertEquals(playlist.length, body.read(replayed, 0, replayed.length));
        assertArrayEquals(playlist, replayed);
    }
}
