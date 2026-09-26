package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.subtitle.format.FormatASS;
import com.github.tvbox.osc.subtitle.model.Style;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * ASS 解析回归(纯 JVM):
 * 覆盖"样式段整段被跳过 / 只解析第一条 Style / ScriptType 不识别 / EOF 处 NPE 中断整份解析"
 * 这几个曾经的缺陷,以及 &HAABBGGRR 色值转换(原来每通道只截 1 个字符 → 颜色全错)。
 */
public class FormatASSTest {

    private static final String ASS = String.join("\n",
            "[Script Info]",
            "Title: 测试",
            "ScriptType: v4.00+",
            "WrapStyle: 0",
            "",
            "[V4+ Styles]",
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
            "Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1",
            "Style: 中文,黑体,24,&H0000FF00,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,2,5,10,10,10,1",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,第一句",
            "Dialogue: 0,0:00:03.00,0:00:04.00,中文,,0,0,0,,第二句\\N换行",
            "");

    private static TimedTextObject parse(String content) throws Exception {
        // 字幕栈内部用 new InputStreamReader(is)(平台默认字符集;Android 上是 UTF-8)解码,
        // 这里按同一字符集取字节,保证测试在任何 JVM 默认编码下都只考察解析逻辑本身
        InputStream is = new ByteArrayInputStream(content.getBytes(java.nio.charset.Charset.defaultCharset()));
        return new FormatASS().parseFile("test.ass", is);
    }

    @Test
    public void parsesAllCaptions() throws Exception {
        TimedTextObject tto = parse(ASS);
        assertNotNull(tto);
        assertEquals(2, tto.captions.size());
        assertTrue(tto.built);
    }

    @Test
    public void parsesEveryStyleInSection() throws Exception {
        TimedTextObject tto = parse(ASS);
        // 回归点:原来 [V4+ Styles] 段只解析第一条 Style(且外层循环还会把段头吞掉)
        assertEquals("styles=" + tto.styling.keySet() + " captions=" + tto.captions.size(), 2, tto.styling.size()); // Default + 中文(两条都被字幕引用,cleanUnusedStyles 不会清掉)
        assertNotNull(tto.styling.get("Default"));
        assertNotNull(tto.styling.get("中文"));
    }

    @Test
    public void acceptsScriptTypeWithoutSpace() throws Exception {
        // [V4 Styles] 段头不带 "+",因此 isASS 只能来自 ScriptType: 行;
        // 用 &HFF000000 这种"ASS=全透明 / SSA=不透明"的值即可区分是否真的识别到了 ScriptType
        String content = String.join("\n",
                "[Script Info]",
                "ScriptType: v4.00+",
                "",
                "[V4 Styles]",
                "Format: Name, Fontname, Fontsize, PrimaryColour",
                "Style: Default,Arial,20,&HFF000000",
                "",
                "[Events]",
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,一句",
                "");
        TimedTextObject tto = parse(content);
        assertEquals(1, tto.captions.size());
        assertNotNull(tto.styling.get("Default"));
        // ASS 语义:AA=FF 全透明 → 翻转成 00;若 ScriptType 未被识别(按 SSA 6 位解析)会得到 000000ff
        assertEquals("00000000", tto.styling.get("Default").color);
    }

    @Test
    public void truncatedFileStillKeepsParsedCaptions() throws Exception {
        // 样式段声明了 Format 却没有任何 Style 行(或文件在此被截断):不能 NPE 掉整份解析
        String content = String.join("\n",
                "[Script Info]",
                "ScriptType: v4.00+",
                "",
                "[V4+ Styles]",
                "Format: Name, Fontname, Fontsize, PrimaryColour",
                "",
                "[Events]",
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,唯一一句",
                "");
        TimedTextObject tto = parse(content);
        assertNotNull(tto);
        assertEquals(1, tto.captions.size());
    }

    @Test
    public void colorConversionHandlesPrefixAndChannelOrder() {
        // &HAABBGGRR:AA=00(不透明,翻转后 ff)、BB=FF、GG=FF、RR=FF → RRGGBBAA
        assertEquals("ffffffff", Style.getRGBValue("&HAABBGGRR", "&H00FFFFFF"));
        // 纯红:AA=00、BB=00、GG=00、RR=FF
        assertEquals("ff0000ff", Style.getRGBValue("&HAABBGGRR", "&H000000FF"));
        // 行尾带 & 的写法同样要认
        assertEquals("ff0000ff", Style.getRGBValue("&HAABBGGRR", "&H000000FF&"));
        // SSA 的 6 位写法:BBGGRR → RRGGBBAA(无 alpha 补不透明)
        assertEquals("ff0000ff", Style.getRGBValue("&HBBGGRR", "&H0000FF"));
        // ASS 的 alpha 与 Android 相反:AA=FF 全透明 → 翻转后 00
        assertEquals("ffffff00", Style.getRGBValue("&HAABBGGRR", "&HFFFFFFFF"));
        // 非法值不再抛异常
        assertEquals(null, Style.getRGBValue("&HAABBGGRR", "&HZZZZZZ"));
    }
}
