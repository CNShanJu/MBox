package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SubtitleFilePolicyTest {
    @Test
    public void hostileDispositionCannotBecomeCachePath() {
        String url = "https://sub.example/movie/part1.srt?token=secret";
        String name = SubtitleFilePolicy.suggestedName(
                "attachment; filename*=UTF-8''..%2F..%2Fpreferences.xml", url);
        assertEquals("preferences.xml", name);
        String cacheName = SubtitleFilePolicy.cacheName(url, name);
        assertFalse(cacheName.contains("/"));
        assertFalse(cacheName.contains("\\"));
        assertFalse(cacheName.contains(".."));
        assertEquals(".xml", cacheName.substring(cacheName.lastIndexOf('.')));
    }

    @Test
    public void sameSuggestedNameFromDifferentUrlsDoesNotShareCacheFile() {
        String name = SubtitleFilePolicy.suggestedName("attachment; filename=movie.srt", "https://a.example/a");
        assertNotEquals(SubtitleFilePolicy.cacheName("https://a.example/a", name),
                SubtitleFilePolicy.cacheName("https://b.example/b", name));
    }

    @Test
    public void missingOrInvalidFilenameFallsBackToUrlPath() {
        assertEquals("show.ass", SubtitleFilePolicy.suggestedName(
                "attachment; filename=..", "https://sub.example/show.ass?token=1"));
        assertEquals("caption.srt", SubtitleFilePolicy.suggestedName(
                "attachment; filename=..\\caption.srt", "https://sub.example/sub"));
    }

    @Test
    public void remoteBodyIsBoundedEvenWhenContentLengthIsUnknown() throws Exception {
        byte[] data = {1, 2, 3, 4};
        assertArrayEquals(data, SubtitleLoader.readLimited(new ByteArrayInputStream(data), -1, 4));
        try {
            SubtitleLoader.readLimited(new ByteArrayInputStream(data), -1, 3);
            throw new AssertionError("Expected size limit to reject the response");
        } catch (IOException expected) {
            // The stream, rather than a possibly missing Content-Length, enforces the limit.
        }
        try {
            SubtitleLoader.readLimited(new ByteArrayInputStream(data), 5, 4);
            throw new AssertionError("Expected declared size to reject the response");
        } catch (IOException expected) {
        }
    }

    @Test
    public void bitmapBodyIsRecognizedBeforeDeclaredSizeLimit() throws Exception {
        byte[] header = {'P', 'G', 0, 0x2b, 0x70, 0x31, 0, 0, 0, 0, 0x16, 0, 0x13};
        try {
            SubtitleLoader.readLimited(new ByteArrayInputStream(header), 25984428, 8 * 1024 * 1024);
            throw new AssertionError("Expected SUP to be rejected as an unsupported format");
        } catch (SubtitleInputPolicy.UnsupportedSubtitleFormatException expected) {
            assertEquals("当前不支持 SUP 图片字幕，请选择 SRT 或 ASS 字幕", expected.getMessage());
        }
    }

    @Test
    public void headerInspectionKeepsTheFullTextBody() throws Exception {
        byte[] data = "\uFEFF1\n00:00:05,480 --> 00:00:06,570\n测试字幕\n".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data, SubtitleLoader.readLimited(new ByteArrayInputStream(data), data.length, data.length));
    }
}
