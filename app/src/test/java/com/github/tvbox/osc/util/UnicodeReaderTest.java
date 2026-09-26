package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * UnicodeReader(BOM 探测 + 字符集解码,字幕文件装载链路使用)单测:
 * UTF-8/UTF-16LE/UTF-16BE BOM 识别、无 BOM 回退默认编码、空输入安全。
 */
public class UnicodeReaderTest {

    private static String readAll(Reader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[64];
        int n;
        while ((n = reader.read(buf, 0, buf.length)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    @Test
    public void utf8Bom_detectedAndDecoded() throws Exception {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String text = "中文ABC字幕";
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = concat(bom, payload);

        UnicodeReader reader = new UnicodeReader(new ByteArrayInputStream(bytes), null);
        assertEquals("UTF-8", reader.getEncoding());
        assertEquals(text, readAll(reader));
        reader.close();
    }

    @Test
    public void utf16LeBom_detectedAndDecoded() throws Exception {
        byte[] bom = new byte[]{(byte) 0xFF, (byte) 0xFE};
        String text = "字幕测试Sub";
        byte[] payload = text.getBytes(Charset.forName("UTF-16LE"));
        byte[] bytes = concat(bom, payload);

        UnicodeReader reader = new UnicodeReader(new ByteArrayInputStream(bytes), null);
        assertEquals("UTF-16LE", reader.getEncoding());
        assertEquals(text, readAll(reader));
        reader.close();
    }

    @Test
    public void utf16BeBom_detectedAndDecoded() throws Exception {
        byte[] bom = new byte[]{(byte) 0xFE, (byte) 0xFF};
        String text = "字幕Sub";
        byte[] payload = text.getBytes(Charset.forName("UTF-16BE"));
        byte[] bytes = concat(bom, payload);

        UnicodeReader reader = new UnicodeReader(new ByteArrayInputStream(bytes), null);
        assertEquals("UTF-16BE", reader.getEncoding());
        assertEquals(text, readAll(reader));
        reader.close();
    }

    @Test
    public void noBom_fallsBackToDefaultEncoding() throws Exception {
        // 无 BOM 的 GBK 字幕文件:显式 defaultEncoding=GBK 时按 GBK 解码
        String text = "简体中文字幕测试";
        byte[] bytes = text.getBytes(Charset.forName("GBK"));

        UnicodeReader reader = new UnicodeReader(new ByteArrayInputStream(bytes), "GBK");
        assertEquals("GBK", reader.getEncoding());
        assertEquals(text, readAll(reader));
        reader.close();
    }

    @Test
    public void emptyInput_noThrow_andEmptyContent() throws Exception {
        UnicodeReader reader = new UnicodeReader(new ByteArrayInputStream(new byte[0]), "UTF-8");
        assertEquals("UTF-8", reader.getEncoding());
        assertEquals("", readAll(reader));
        reader.close();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
