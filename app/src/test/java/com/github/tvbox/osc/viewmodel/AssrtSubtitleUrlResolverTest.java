package com.github.tvbox.osc.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class AssrtSubtitleUrlResolverTest {
    private static final String DOWNLOAD_URL = "https://assrt.net/download/123/-/1/movie.srt";

    @Test
    public void directFileResponseKeepsOriginalUrl() throws Exception {
        assertEquals(DOWNLOAD_URL, AssrtSubtitleUrlResolver.resolve(response(200, "text/plain", null)));
    }

    @Test
    public void relativeRedirectResolvesAgainstRequestUrl() throws Exception {
        assertEquals("https://assrt.net/files/movie.srt",
                AssrtSubtitleUrlResolver.resolve(response(302, null, "/files/movie.srt")));
    }

    @Test
    public void errorAndMissingRedirectAreRejected() {
        assertThrows(IOException.class, () -> AssrtSubtitleUrlResolver.resolve(response(403, null, null)));
        assertThrows(IOException.class, () -> AssrtSubtitleUrlResolver.resolve(response(302, null, null)));
        assertThrows(IOException.class, () -> AssrtSubtitleUrlResolver.resolve(response(204, null, null)));
        assertThrows(IOException.class, () -> AssrtSubtitleUrlResolver.resolve(response(200, "text/html", null)));
    }

    private static Response response(int code, String contentType, String location) {
        Response.Builder builder = new Response.Builder()
                .request(new Request.Builder().url(DOWNLOAD_URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test");
        if (contentType != null) builder.header("Content-Type", contentType);
        if (location != null) builder.header("Location", location);
        return builder.build();
    }
}
