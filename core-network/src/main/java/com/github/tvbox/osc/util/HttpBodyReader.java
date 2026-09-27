package com.github.tvbox.osc.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.ResponseBody;

/**
 * 响应体读取(带大小上限)——纯逻辑,无 Android 依赖,便于 JVM 单测(见 HttpBodyReaderTest)。
 * <p>
 * 存在的理由:{@code ResponseBody.string()}/{@code bytes()} 都没有任何上限。本 App 里这些响应体
 * 全是"取回文本再解析"(站点接口 JSON、直播源列表、m3u8 清单、订阅 JSON、JS 源请求的接口数据),
 * 地址又完全由订阅里的规则决定 —— 一个被重定向到大文件、或者干脆是无限流的响应,就能让
 * {@code string()} 边读边涨直到 OOM。这里统一:有 Content-Length 时先快速拒绝,没有(分块传输)时
 * 按累计字节数兜底。
 */
public final class HttpBodyReader {

    /** 文本响应上限(16MB):正常最大也就几 MB(上万频道的直播源 txt、上万分片的 m3u8),留足余量 */
    public static final long MAX_TEXT_BYTES = 16L * 1024 * 1024;
    /** 二进制(JS 源 buffer 模式)响应上限(24MB) */
    public static final long MAX_BINARY_BYTES = 24L * 1024 * 1024;

    private HttpBodyReader() {
    }

    /**
     * 读响应体文本,上限 {@link #MAX_TEXT_BYTES}。
     * <p>
     * 字符集口径与 {@code ResponseBody.string()} 一致:Content-Type 的 charset,缺省 UTF-8;
     * 另外跳过 UTF-8 BOM —— 带 BOM 的 JSON 直接交给 JSONObject 会解析失败,而 string() 原本会跳过。
     */
    public static String readText(ResponseBody body) throws IOException {
        byte[] data = readBytes(body, MAX_TEXT_BYTES);
        return decodeText(data, body == null ? null : body.contentType());
    }

    /** 读响应体字节,上限由调用方给(不同用途量级不同) */
    public static byte[] readBytes(ResponseBody body, long maxBytes) throws IOException {
        if (body == null) return new byte[0];
        long declared = body.contentLength();
        if (declared > maxBytes) {
            throw new IOException("响应体过大(" + declared + " 字节,上限 " + maxBytes + ")");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        try (InputStream in = body.byteStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > maxBytes) {
                    throw new IOException("响应体超过上限 " + maxBytes + " 字节");
                }
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    /** 按 Content-Type 的 charset 解码(缺省 UTF-8),并跳过 UTF-8 BOM */
    public static String decodeText(byte[] data, MediaType type) {
        if (data == null || data.length == 0) return "";
        Charset charset = StandardCharsets.UTF_8;
        if (type != null) {
            Charset fromHeader = type.charset();
            if (fromHeader != null) charset = fromHeader;
        }
        int start = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        return new String(data, start, data.length - start, charset);
    }
}
