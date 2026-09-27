package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 响应体上限读取单测(JVM,不碰 Android)。
 * <p>
 * 这块是"无界响应体 → OOM"的防线:既要证明超限确实会被拒绝(有 Content-Length 与分块两条路径),
 * 也要证明正常响应(带 BOM、非 UTF-8 字符集、正好卡在上限)不会被误伤 ——
 * 毕竟这两条一旦写反,表现分别是"大响应 OOM"和"某些源/订阅永远打不开",都很难在真机上归因。
 */
public class HttpBodyReaderTest {

    private static byte[] bytes(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    @Test
    public void readsNormalBody() throws Exception {
        ResponseBody body = ResponseBody.create(MediaType.parse("application/json"), "{\"a\":1}");
        assertEquals("{\"a\":1}", HttpBodyReader.readText(body));
    }

    @Test
    public void stripsUtf8Bom() throws Exception {
        // 带 BOM 的 JSON 直接交给 JSONObject 会解析失败,string() 原本会自动跳过
        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'};
        ResponseBody body = ResponseBody.create(MediaType.parse("application/json"), withBom);
        assertEquals("{}", HttpBodyReader.readText(body));
    }

    @Test
    public void honorsCharsetFromContentType() throws Exception {
        byte[] gbk = "中文".getBytes("GBK");
        ResponseBody body = ResponseBody.create(MediaType.parse("text/plain; charset=gbk"), gbk);
        assertEquals("中文", HttpBodyReader.readText(body));
    }

    @Test
    public void defaultCharsetIsUtf8() throws Exception {
        ResponseBody body = ResponseBody.create(MediaType.parse("text/plain"), "中文".getBytes(StandardCharsets.UTF_8));
        assertEquals("中文", HttpBodyReader.readText(body));
    }

    @Test
    public void rejectsOversizedDeclaredLength() {
        ResponseBody body = ResponseBody.create(MediaType.parse("text/plain"),
                bytes((int) HttpBodyReader.MAX_TEXT_BYTES + 1));
        try {
            HttpBodyReader.readText(body);
            fail("超过上限的响应体应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("过大"));
        }
    }

    @Test
    public void rejectsOversizedChunkedBody() {
        // 分块传输(Content-Length 未知)时只能按累计字节数兜底 —— 没有这道兜底就是"边读边涨到 OOM"
        final byte[] big = bytes((int) HttpBodyReader.MAX_TEXT_BYTES + 1024);
        ResponseBody body = new ResponseBody() {
            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public BufferedSource source() {
                return new Buffer().write(big);
            }
        };
        try {
            HttpBodyReader.readText(body);
            fail("Content-Length 未知但实际超限的响应体应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("上限"));
        }
    }

    @Test
    public void acceptsBodyExactlyAtLimit() throws Exception {
        // 边界:正好等于上限必须放行(是"上限",不是"必须小于")
        ResponseBody body = ResponseBody.create(MediaType.parse("text/plain"),
                bytes((int) HttpBodyReader.MAX_TEXT_BYTES));
        String text = HttpBodyReader.readText(body);
        assertNotNull(text);
        assertEquals(HttpBodyReader.MAX_TEXT_BYTES, text.length());
    }

    @Test
    public void emptyBodyIsEmptyString() throws Exception {
        ResponseBody body = ResponseBody.create(MediaType.parse("text/plain"), new byte[0]);
        assertEquals("", HttpBodyReader.readText(body));
        assertEquals("", HttpBodyReader.readText(null));
        assertEquals(0, HttpBodyReader.readBytes(null, 1024).length);
    }

    @Test
    public void binaryCapIsIndependentAndEnforced() throws Exception {
        // JS 源 buffer 模式走二进制上限(24MB),与文本上限分开
        ResponseBody small = ResponseBody.create(MediaType.parse("application/octet-stream"), bytes(64));
        assertEquals(64, HttpBodyReader.readBytes(small, HttpBodyReader.MAX_BINARY_BYTES).length);
        assertTrue(HttpBodyReader.MAX_BINARY_BYTES > HttpBodyReader.MAX_TEXT_BYTES);

        ResponseBody tooBig = ResponseBody.create(MediaType.parse("application/octet-stream"), bytes(2048));
        try {
            HttpBodyReader.readBytes(tooBig, 1024);
            fail("超过二进制上限的响应体应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("过大"));
        }
    }
}
