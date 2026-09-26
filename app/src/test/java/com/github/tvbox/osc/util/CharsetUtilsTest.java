package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * CharsetUtils.detect 单测:以"检测出的字符集可无损还原原文"为准(锁定可读性回归,
 * 不锁具体实现名);UTF-8 BOM 与 ASCII 走探测器,GBK 走中文常用字回退。
 */
public class CharsetUtilsTest {

    @Test
    public void utf8Bom_roundTrip() {
        String text = "中文ABC";
        // 带 \uFEFF BOM 的 UTF-8:检测出的字符集解码应无损还原(含 BOM 字符)
        byte[] bytes = ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8);
        Charset detected = CharsetUtils.detect(bytes);
        assertNotNull(detected);
        assertEquals("\uFEFF" + text, new String(bytes, detected));
    }

    @Test
    public void plainAscii_detectNeverThrows_andRoundTrip() {
        String text = "Hello, MBox!";
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        Charset detected = CharsetUtils.detect(bytes);
        assertNotNull(detected);
        assertEquals(text, new String(bytes, detected));
    }

    @Test
    public void gbkBytes_roundTrip() {
        // 中文内容让 chardet 探测失败时走"中文常用字"逐字符集回退,最终编码必须可无损还原
        String text = "这是一个用来验证字符集检测的中文长句子,包含了很多常用字。";
        byte[] bytes = text.getBytes(Charset.forName("GBK"));
        Charset detected = CharsetUtils.detect(bytes);
        assertNotNull(detected);
        assertEquals(text, new String(bytes, detected));
    }

    @Test
    public void utf8NoBom_roundTrip() {
        String text = "UTF-8 无 BOM 的中文内容验证";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Charset detected = CharsetUtils.detect(bytes);
        assertNotNull(detected);
        assertEquals(text, new String(bytes, detected));
    }
}
