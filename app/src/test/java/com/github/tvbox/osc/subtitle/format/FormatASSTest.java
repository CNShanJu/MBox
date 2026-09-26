package com.github.tvbox.osc.subtitle.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * FormatASS 解析样例单测(字幕装载纯解析链路,真机回归保护点)。
 * 注意:样例保持 ASCII(解析内部用平台默认字符集读取,中文断言在 JVM 测试环境不可移植)。
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
        InputStream is = new ByteArrayInputStream(ass.getBytes(StandardCharsets.UTF_8));
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
}
