package com.github.tvbox.osc.subtitle.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * FormatASS 解析样例单测(字幕装载纯解析链路,真机回归保护点)。
 * 测试流与解析器使用同一默认字符集，确保中文样例在 JVM 环境中一致。
 */
public class FormatASSTest {

    private static final String SAMPLE_ASS =
            "[Script Info]\n" +
                    "Script Type: V4.00+\n" +
                    "Title: sample\n" +
                    "\n" +
                    "[V4+ Styles]\n" +
                    "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
                    "Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1\n" +
                    "\n" +
                    "[Events]\n" +
                    "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                    "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Line one{\\i1}bold{\\i0} done\n" +
                    "Dialogue: 0,0:00:05.00,0:00:06.50,Default,,0,0,0,,Second line\n";

    private static TimedTextObject parse(String ass) throws IOException {
        InputStream is = new ByteArrayInputStream(ass.getBytes(Charset.defaultCharset()));
        return new FormatASS().parseFile("sample.ass", is);
    }

    private static Subtitle captionAt(TimedTextObject tto, int startMs) {
        for (Subtitle s : tto.captions.values()) {
            if (s.start.mseconds == startMs) return s;
        }
        return null;
    }

    @Test
    public void parseFile_readsTwoDialogueCaptions() throws IOException {
        TimedTextObject tto = parse(SAMPLE_ASS);
        assertNotNull(tto);
        assertNotNull(tto.captions);
        assertEquals(2, tto.captions.size());
        Subtitle first = captionAt(tto, 1000);
        assertNotNull(first);
        assertEquals(3000, first.end.mseconds);
        Subtitle second = captionAt(tto, 5000);
        assertNotNull(second);
        assertEquals(6500, second.end.mseconds); // 0:00:06.50
    }

    @Test
    public void parseFile_removesInlineOverrideTags() throws IOException {
        TimedTextObject tto = parse(SAMPLE_ASS);
        Subtitle first = captionAt(tto, 1000);
        assertNotNull(first);
        // {\i1}...{\i0} 覆盖标签被剥离,内容保留
        assertEquals("Line onebold done", first.content);
    }

    @Test
    public void parseFile_zeroTimerKeepsActualSampleTimeline() throws IOException {
        // 来自用户文件的最小片段：零 Timer、前两条及后续互相重叠的对白/标牌。
        String sample = String.join("\n",
                "[Script Info]",
                "ScriptType: v4.00+",
                "PlayResX: 1920",
                "PlayResY: 1080",
                "Timer: 0.0000",
                "WrapStyle: 0",
                "",
                "[V4+ Styles]",
                "Format: Name, Fontname, Fontsize, PrimaryColour",
                "Style: Default,Microsoft YaHei,60,&H00FFFFFF",
                "Style: Signs,Microsoft YaHei,54,&H00FFFFFF",
                "",
                "[Events]",
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
                "Dialogue: 0,0:00:15.80,0:00:17.30,Signs,,0,0,0,,{\\an3}《想守护你》\\N可爱CHAN 第七张单曲",
                "Dialogue: 0,0:00:20.43,0:00:23.56,Default,,0,0,0,,上学又要迟到了……",
                "Dialogue: 0,0:01:56.19,0:01:57.79,Default,,0,0,0,,自从你入学以来，",
                "Dialogue: 0,0:01:56.99,0:01:59.32,Signs,,0,0,0,,{\\an8}教职员室",
                "Dialogue: 0,0:01:57.79,0:02:01.41,Default,,0,0,0,,你的国语模拟考试\\N就一直是满分。",
                "");

        TimedTextObject tto = parse(sample);
        assertEquals(5, tto.captions.size());
        assertEquals(15800, (int) tto.captions.firstKey());
        Subtitle first = captionAt(tto, 15800);
        assertNotNull(first);
        assertEquals(17300, first.end.mseconds);
        assertEquals("《想守护你》<br />可爱CHAN 第七张单曲", first.content);
        assertEquals(23560, captionAt(tto, 20430).end.mseconds);
        assertEquals(117790, captionAt(tto, 116190).end.mseconds);
        assertEquals(119320, captionAt(tto, 116990).end.mseconds);
        assertEquals(121410, captionAt(tto, 117790).end.mseconds);
    }

    @Test
    public void parseFile_invalidTimersUseNormalSpeed() throws IOException {
        String[] invalidTimers = {"0", "0.0000", "-100", "NaN", "Infinity",
                "-Infinity", "1e100", "", "not a number"};
        for (String timer : invalidTimers) {
            TimedTextObject tto = parse(SAMPLE_ASS.replace("Title: sample\n",
                    "Title: sample\nTimer: " + timer + "\n"));
            assertEquals("Timer=" + timer, 2, tto.captions.size());
            Subtitle first = captionAt(tto, 1000);
            assertNotNull("Timer=" + timer, first);
            assertEquals("Timer=" + timer, 3000, first.end.mseconds);
            Subtitle second = captionAt(tto, 5000);
            assertNotNull("Timer=" + timer, second);
            assertEquals("Timer=" + timer, 6500, second.end.mseconds);
        }
    }

    @Test
    public void parseFile_validTimerStillScalesTimeline() throws IOException {
        TimedTextObject tto = parse(SAMPLE_ASS.replace("Title: sample\n",
                "Title: sample\nTimer: 200,0000\n"));
        assertEquals(2, tto.captions.size());
        Subtitle first = captionAt(tto, 500);
        assertNotNull(first);
        assertEquals(1500, first.end.mseconds);
        Subtitle second = captionAt(tto, 2500);
        assertNotNull(second);
        assertEquals(3250, second.end.mseconds);
    }
}
