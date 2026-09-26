package com.github.tvbox.osc.subtitle.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** FormatSRT 解析样例单测(字幕装载纯解析链路,真机回归保护点) */
public class FormatSRTTest {

    private static final String SAMPLE_SRT =
            "1\n" +
                    "00:00:01,000 --> 00:00:04,000\n" +
                    "Hello\n" +
                    "\n" +
                    "2\n" +
                    "00:00:05,000 --> 00:00:08,000\n" +
                    "second line\n" +
                    "line two\n" +
                    "\n";

    private static TimedTextObject parse(String srt) throws IOException {
        InputStream is = new ByteArrayInputStream(srt.getBytes(StandardCharsets.UTF_8));
        return new FormatSRT().parseFile("sample.srt", is);
    }

    @Test
    public void parseFile_readsTwoCaptionsInOrder() throws IOException {
        TimedTextObject tto = parse(SAMPLE_SRT);
        assertNotNull(tto);
        assertNotNull(tto.captions);
        assertEquals(2, tto.captions.size());
        Subtitle first = tto.captions.values().iterator().next();
        // 起始时间毫秒:00:00:01,000 = 1000ms
        assertEquals(1000, first.start.mseconds);
        assertEquals(4000, first.end.mseconds);
        assertTrue(first.content, !first.content.trim().isEmpty());
    }

    @Test
    public void parseFile_multilineContentJoinsWithBreakTag() throws IOException {
        TimedTextObject tto = parse(SAMPLE_SRT);
        Subtitle last = null;
        for (Subtitle s : tto.captions.values()) last = s;
        assertNotNull(last);
        // 多行内容以 <br /> 连接
        assertTrue(last.content, last.content.contains("<br />"));
        assertTrue(last.content, last.content.startsWith("second line"));
    }

    @Test
    public void parseFile_emptyInputProducesNoCaptions() throws IOException {
        TimedTextObject tto = parse("");
        assertNotNull(tto.captions);
        assertTrue(tto.captions.isEmpty());
    }
}
