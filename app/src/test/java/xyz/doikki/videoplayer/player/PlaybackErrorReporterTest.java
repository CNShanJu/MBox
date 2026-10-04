package xyz.doikki.videoplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackErrorReporterTest {
    @Test
    public void proxyFailureShowsTargetHostAndLocalRouteWithoutCredentials() {
        String proxy = "http://127.0.0.1:9978/proxy?do=js&header=%7B%22Cookie%22%3A%22secret%22%7D"
                + "&url=https%3A%2F%2Fuser%3Apassword%40video.example%2Fprivate%2Fmovie.m3u8%3Fsign%3Da%252Bb";
        String source = PlaybackErrorReporter.source(proxy);

        assertEquals("真实地址=https://video.example，代理=http://127.0.0.1:9978/proxy", source);
        assertFalse(source.contains("password"));
        assertFalse(source.contains("secret"));
        assertFalse(source.contains("movie.m3u8"));
        assertFalse(source.contains("sign="));
    }

    @Test
    public void directFailureKeepsOriginWithoutPrivatePathOrQuery() {
        assertEquals("https://video.example:8443", PlaybackErrorReporter.source(
                "https://user:password@video.example:8443/private/movie.m3u8?sign=abc&part=1"));
    }

    @Test
    public void proxyWithoutTargetKeepsOnlyKnownRoute() {
        assertEquals("http://127.0.0.1:9978/proxy", PlaybackErrorReporter.source(
                "http://127.0.0.1:9978/proxy?do=live&id=channel1"));
    }

    @Test
    public void unknownOrLocalPathsAreNeverWrittenVerbatim() {
        assertEquals("非标准地址", PlaybackErrorReporter.source("not-a-url?token=secret"));
        assertEquals("file:本地资源", PlaybackErrorReporter.source("file:///storage/private/movie.mp4"));
    }

    @Test
    public void exceptionReasonRemovesUrlsAndCredentialValues() {
        String reason = PlaybackErrorReporter.cause(new IllegalArgumentException(
                "failed https://user:password@video.example/playlist.m3u8?sign=abc\n"
                        + "Authorization: Bearer topsecret"));
        assertTrue(reason.contains("https://video.example"));
        assertFalse(reason.contains("password"));
        assertFalse(reason.contains("playlist.m3u8"));
        assertFalse(reason.contains("sign="));
        assertFalse(reason.contains("topsecret"));
        assertFalse(reason.contains("\n"));
    }

    @Test
    public void rawQueryFragmentsAreRedactedAndLongMessagesAreBounded() {
        StringBuilder reason = new StringBuilder("request failed ?sign=");
        for (int i = 0; i < 300; i++) reason.append('x');
        String safe = PlaybackErrorReporter.safeDiagnosticText(reason.toString());
        assertFalse(safe.contains("xxxxxxxx"));
        assertTrue(safe.length() <= 241);
    }

    @Test
    public void urlWithParenthesesDoesNotLeaveSignedPathSuffix() {
        String safe = PlaybackErrorReporter.safeDiagnosticText(
                "failed https://video.example/private/(access-secret)/playlist.m3u8?signature=abc");
        assertEquals("failed https://video.example", safe);
    }

    @Test
    public void urlWithSemicolonSessionIdIsFullyRedacted() {
        String safe = PlaybackErrorReporter.safeDiagnosticText(
                "failed https://video.example/private;jsessionid=topsecret/playlist.m3u8");
        assertEquals("failed https://video.example", safe);
    }
}
