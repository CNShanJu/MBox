package com.github.tvbox.osc.cast;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the real relay responses with a local development HTTP source, without a device. */
public class CastMediaRelayPlaylistTest {
    @Test public void castKeepsTheWholeEpisodeAndEveryAuthorizedSegment() throws Exception {
        byte[] segment = new byte[376];
        Arrays.fill(segment, (byte) 0x47);
        AtomicInteger authorizedReads = new AtomicInteger();
        NanoHTTPD source = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                if (!"session=test".equals(request.getHeaders().get("cookie")))
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "");
                authorizedReads.incrementAndGet();
                String rawRange = request.getHeaders().get("range");
                CastMediaRules.ByteRange range = CastMediaRules.range(rawRange, segment.length);
                int start = (int) range.start;
                int count = (int) (range.end - range.start + 1);
                Response response = newFixedLengthResponse(range.partial
                                ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                        "video/mp2t", new ByteArrayInputStream(segment, start, count), count);
                response.addHeader("Accept-Ranges", "bytes");
                if (range.partial) response.addHeader("Content-Range",
                        "bytes " + range.start + "-" + range.end + "/" + segment.length);
                return response;
            }
        };
        source.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        OkHttpClient client = new OkHttpClient();
        CastMediaRelay relay = new CastMediaRelay();
        CastMediaRelay.Session session = null;
        try {
            String origin = "http://127.0.0.1:" + source.getListeningPort() + "/purify.m3u8";
            String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:10\n"
                    + "#EXTINF:6,\n/proxy?id=10\n#EXTINF:6,\n/proxy?id=11\n"
                    + "#EXTINF:6,\n/proxy?id=12\n#EXT-X-ENDLIST\n";
            session = new CastMediaRelay.Session(null, "http://127.0.0.1:12345",
                    repeat('a', 64), source.getListeningPort(), 1, client);
            String path = session.register(origin, Collections.singletonMap("Cookie", "session=test"),
                    playlist, origin, CastMediaRules.MediaKind.HLS, null);
            Field current = CastMediaRelay.class.getDeclaredField("current");
            current.setAccessible(true);
            current.set(relay, session);

            // The cast URL must be the full HLS root, including when the renderer probes with HEAD/Range.
            assertTrue(path.endsWith("/stream.m3u8"));
            try (NanoHTTPD.Response head = serve(relay, path, NanoHTTPD.Method.HEAD, "bytes=0-1023")) {
                assertEquals(NanoHTTPD.Response.Status.OK, head.getStatus());
                assertEquals("application/vnd.apple.mpegurl", head.getMimeType());
            }
            String rewritten;
            try (NanoHTTPD.Response response = serve(relay, path, NanoHTTPD.Method.GET, "bytes=0-1023")) {
                assertEquals(NanoHTTPD.Response.Status.OK, response.getStatus());
                assertEquals("application/vnd.apple.mpegurl", response.getMimeType());
                rewritten = new String(read(response.getData()), StandardCharsets.UTF_8);
            }
            assertTrue(rewritten.startsWith("#EXTM3U\n"));
            assertTrue(rewritten.contains("#EXT-X-MEDIA-SEQUENCE:10\n"));
            assertTrue(rewritten.endsWith("#EXT-X-ENDLIST\n"));
            assertFalse(rewritten.contains("about:blank"));
            List<String> segments = new ArrayList<>();
            for (String line : rewritten.split("\n"))
                if (!line.isEmpty() && !line.startsWith("#")) segments.add(line);
            assertEquals(3, segments.size());
            assertEquals(0, authorizedReads.get());

            for (String child : segments) {
                assertTrue(child.startsWith(session.baseUrl + "/media/" + session.token + "/"));
                assertTrue(child.endsWith("/stream.ts"));
                try (NanoHTTPD.Response response = serve(relay, URI.create(child).getPath(),
                        NanoHTTPD.Method.GET, null)) {
                    assertEquals(NanoHTTPD.Response.Status.OK, response.getStatus());
                    assertArrayEquals(segment, read(response.getData()));
                }
            }
            assertEquals(3, authorizedReads.get());
            try (NanoHTTPD.Response partial = serve(relay, URI.create(segments.get(2)).getPath(),
                    NanoHTTPD.Method.GET, "bytes=188-375")) {
                assertEquals(NanoHTTPD.Response.Status.PARTIAL_CONTENT, partial.getStatus());
                assertEquals("bytes 188-375/376", partial.getHeader("content-range"));
                assertArrayEquals(Arrays.copyOfRange(segment, 188, 376), read(partial.getData()));
            }
            try (NanoHTTPD.Response denied = serve(relay, path.replace(session.token, repeat('b', 64)),
                    NanoHTTPD.Method.GET, null)) {
                assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, denied.getStatus());
            }
        } finally {
            if (session != null) session.close();
            client.dispatcher().executorService().shutdownNow();
            source.stop();
        }
    }

    private static NanoHTTPD.Response serve(CastMediaRelay relay, String path,
                                            NanoHTTPD.Method method, String range) throws Exception {
        NanoHTTPD.IHTTPSession request = (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(
                NanoHTTPD.IHTTPSession.class.getClassLoader(), new Class<?>[] {NanoHTTPD.IHTTPSession.class},
                (proxy, called, args) -> {
                    switch (called.getName()) {
                        case "getUri": return path;
                        case "getMethod": return method;
                        case "getHeaders": return range == null ? Collections.emptyMap()
                                : Collections.singletonMap("range", range);
                        default: throw new UnsupportedOperationException(called.getName());
                    }
                });
        Method serve = CastMediaRelay.class.getDeclaredMethod("serve", NanoHTTPD.IHTTPSession.class);
        serve.setAccessible(true);
        return (NanoHTTPD.Response) serve.invoke(relay, request);
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
