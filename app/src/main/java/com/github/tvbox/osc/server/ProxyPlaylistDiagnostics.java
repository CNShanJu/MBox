package com.github.tvbox.osc.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 检查本机代理清单响应的开头，不消耗播放器要读取的正文，也不记录正文原文。 */
final class ProxyPlaylistDiagnostics {
    private static final int PREFIX_BYTES = 64;

    private ProxyPlaylistDiagnostics() {
    }

    static String bodyKind(InputStream body) throws IOException {
        byte[] prefix = new byte[PREFIX_BYTES];
        body.mark(PREFIX_BYTES);
        int count = 0;
        try {
            // 网络流允许短读；至少取到 BOM + #EXTM3U 所需字节，再按已有缓冲补采样。
            while (count < 10) {
                int read = body.read(prefix, count, 10 - count);
                if (read <= 0) break;
                count += read;
            }
            int available = Math.min(PREFIX_BYTES - count, body.available());
            if (available > 0) {
                int read = body.read(prefix, count, available);
                if (read > 0) count += read;
            }
        } finally {
            body.reset();
        }
        if (count <= 0) return "empty";
        if (startsWith(prefix, count, 0, "#EXTM3U")) return "m3u8";
        if (count >= 3 && (prefix[0] & 0xff) == 0xef
                && (prefix[1] & 0xff) == 0xbb && (prefix[2] & 0xff) == 0xbf
                && startsWith(prefix, count, 3, "#EXTM3U")) return "utf8_bom";
        if (count >= 2 && (prefix[0] & 0xff) == 0x1f && (prefix[1] & 0xff) == 0x8b) return "gzip";
        String text = new String(prefix, 0, count, StandardCharsets.US_ASCII)
                .trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("#extm3u")) return "leading_whitespace";
        if (text.startsWith("<")) return "html_or_xml";
        if (text.startsWith("{") || text.startsWith("[")) return "json";
        if (text.contains("forbidden") || text.contains("not found")
                || text.contains("denied") || text.contains("error")) return "error_text";
        return "other";
    }

    static String bodyKind(ByteArrayInputStream body) {
        try {
            return bodyKind((InputStream) body);
        } catch (IOException impossible) {
            return "unreadable";
        }
    }

    private static boolean startsWith(byte[] bytes, int count, int offset, String expected) {
        if (count - offset < expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != expected.charAt(i)) return false;
        }
        return true;
    }
}
