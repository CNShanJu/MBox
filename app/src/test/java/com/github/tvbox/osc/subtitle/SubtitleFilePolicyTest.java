package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

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
}
