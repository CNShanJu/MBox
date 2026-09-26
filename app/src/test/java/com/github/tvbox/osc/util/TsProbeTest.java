package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link TsProbe} 纯逻辑单测:下载产物是 188/192/204 字节包、MP4 盒子还是认不出来的内容,
 * 直接决定重封装能不能建出 MediaExtractor(见类注释)。
 */
public class TsProbeTest {

    /** 造 n 个指定包长的 TS 包:syncOffset 处放同步字节,其余填 0x00 */
    private static byte[] tsStream(int packetSize, int syncOffset, int packets) {
        byte[] buf = new byte[packetSize * packets];
        for (int p = 0; p < packets; p++) {
            buf[p * packetSize + syncOffset] = 0x47;
        }
        return buf;
    }

    @Test
    public void standardTs188_isRecognized_andNeedsNoRepack() {
        TsProbe probe = TsProbe.of(tsStream(188, 0, 8));
        assertEquals(TsProbe.Kind.TS_188, probe.kind);
        assertEquals(188, probe.packetSize);
        assertEquals(0, probe.syncOffset);
        assertEquals(1.0f, probe.syncRatio, 0.001f);
        assertTrue(probe.isTs());
        assertFalse("188 是标准包长,不该重打包", probe.needsRepack());
        assertNull(TsProbe.repackUnit(new byte[188 * 2], 188 * 2, probe));
    }

    @Test
    public void m2ts192_needsRepack_andDropsTimestampPrefix() {
        TsProbe probe = TsProbe.of(tsStream(192, 4, 8));
        assertEquals(TsProbe.Kind.TS_192, probe.kind);
        assertEquals(192, probe.packetSize);
        assertEquals(4, probe.syncOffset);
        assertTrue(probe.needsRepack());
        assertEquals(4, probe.keepFrom());

        // 重打包:每 192 字节丢掉前 4 字节(时间戳),保留 188 字节 TS 包,同步字节仍在包首
        byte[] src = new byte[192 * 3];
        for (int p = 0; p < 3; p++) {
            src[p * 192] = (byte) 0xAA;        // 时间戳(应被丢掉)
            src[p * 192 + 4] = 0x47;           // 同步字节
            src[p * 192 + 5] = (byte) p;       // 包内负载,便于校验顺序
        }
        byte[] out = TsProbe.repackUnit(src, src.length, probe);
        assertNotNull(out);
        assertEquals(188 * 3, out.length);
        for (int p = 0; p < 3; p++) {
            assertEquals(0x47, out[p * 188] & 0xFF);
            assertEquals(p, out[p * 188 + 1] & 0xFF);
        }
        TsProbe after = TsProbe.of(out);
        assertEquals("重打包后应被识别为标准 188 字节包", TsProbe.Kind.TS_188, after.kind);
    }

    @Test
    public void fec204_needsRepack_andDropsTrailingFec() {
        TsProbe probe = TsProbe.of(tsStream(204, 0, 8));
        assertEquals(TsProbe.Kind.TS_204, probe.kind);
        assertEquals(204, probe.packetSize);
        assertEquals(0, probe.keepFrom());
        assertTrue(probe.needsRepack());

        byte[] src = new byte[204 * 2];
        src[0] = 0x47;
        src[10] = 0x11;      // 负载
        src[188] = (byte) 0xEE; // FEC 起始(应被丢掉)
        src[204] = 0x47;
        byte[] out = TsProbe.repackUnit(src, src.length, probe);
        assertNotNull(out);
        assertEquals(188 * 2, out.length);
        assertEquals(0x11, out[10] & 0xFF);
        assertEquals(0x47, out[188] & 0xFF);
    }

    @Test
    public void mp4Boxes_areReportedWithTag() {
        byte[] ftyp = new byte[64];
        System.arraycopy(new byte[]{0, 0, 0, 0x18}, 0, ftyp, 0, 4);
        System.arraycopy("ftyp".getBytes(), 0, ftyp, 4, 4);
        TsProbe p1 = TsProbe.of(ftyp);
        assertEquals(TsProbe.Kind.MP4, p1.kind);
        assertEquals("ftyp", p1.boxTag);

        // fMP4 片段:开头是 moof/styp(缺 init 段时不可播、也认不出 TS)
        byte[] moof = new byte[64];
        System.arraycopy("moof".getBytes(), 0, moof, 4, 4);
        TsProbe p2 = TsProbe.of(moof);
        assertEquals(TsProbe.Kind.MP4, p2.kind);
        assertEquals("moof", p2.boxTag);
        assertTrue(p2.describe().contains("fMP4"));
    }

    @Test
    public void unknownContent_reportsHeadHex() {
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) (i * 7 + 3); // 无 0x47 同步字节规律
        }
        TsProbe probe = TsProbe.of(junk);
        assertEquals(TsProbe.Kind.UNKNOWN, probe.kind);
        assertFalse(probe.isTs());
        assertFalse(probe.needsRepack());
        assertEquals("前 16 字节要落进日志,便于判断是不是未解密/伪装内容",
                32, probe.headHex.length());
        assertTrue(probe.describe().contains("未解密"));
    }

    @Test
    public void shortOrNullInput_doesNotCrash() {
        assertEquals(TsProbe.Kind.UNKNOWN, TsProbe.of(null).kind);
        assertEquals(TsProbe.Kind.UNKNOWN, TsProbe.of(new byte[0]).kind);
        assertEquals(TsProbe.Kind.UNKNOWN, TsProbe.of(new byte[]{0x47, 0x40}).kind);
        assertNull(TsProbe.repackUnit(null, 0, TsProbe.of(tsStream(192, 4, 4))));
    }

    /**
     * 真实故障现场:"分片"其实是图片 —— 报过的一次下载,940 个 .jpg 分片全是 PNG
     * (整条线路返回的是超星图床的图片),下载"成功"后拿到一个打不开的文件。
     * 自检必须把这类内容认出来并说清楚,才能判失败而不是当成功。
     */
    @Test
    public void imagePayload_isUnknown_andHintsContentType() {
        byte[] png = new byte[512];
        // PNG 签名 89 50 4E 47 0D 0A 1A 0A + 之后的 IHDR
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52};
        System.arraycopy(sig, 0, png, 0, sig.length);
        TsProbe p = TsProbe.of(png);
        assertEquals(TsProbe.Kind.UNKNOWN, p.kind);
        assertFalse(p.isTs());
        assertEquals("PNG 图片", p.contentHint);
        assertTrue(p.describe().contains("PNG 图片"));

        // JPEG:FF D8 FF
        byte[] jpeg = new byte[64];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        assertEquals("JPEG 图片", TsProbe.of(jpeg).contentHint);

        // GIF / WebP
        byte[] gif = new byte[32];
        System.arraycopy("GIF89a".getBytes(), 0, gif, 0, 6);
        assertEquals("GIF 图片", TsProbe.of(gif).contentHint);
        byte[] webp = new byte[32];
        System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, webp, 8, 4);
        assertEquals("WebP 图片", TsProbe.of(webp).contentHint);
    }

    /** 防盗链/错误响应的两种常见文本形态:HTML 错误页与 JSON 报错 */
    @Test
    public void textPayload_isUnknown_andHintsHtmlOrJson() {
        byte[] html = new byte[256];
        byte[] htmlSrc = "<!DOCTYPE html><html>403 Forbidden".getBytes();
        System.arraycopy(htmlSrc, 0, html, 0, htmlSrc.length);
        TsProbe h = TsProbe.of(html);
        assertEquals(TsProbe.Kind.UNKNOWN, h.kind);
        assertEquals("HTML/XML 页面", h.contentHint);

        byte[] json = new byte[64];
        byte[] jsonSrc = "{\"code\":403,\"msg\":\"expired\"}".getBytes();
        System.arraycopy(jsonSrc, 0, json, 0, jsonSrc.length);
        assertEquals("JSON 文本", TsProbe.of(json).contentHint);

        // 纯随机字节:识别不出来,但不能崩(仍给 hex 供排查)
        byte[] junk = new byte[64];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 31 + 5);
        TsProbe j = TsProbe.of(junk);
        assertEquals("", j.contentHint);
        assertFalse(j.describe().isEmpty());
    }

    @Test
    public void repackUnit_handlesPartialUnitAndBadArgs() {
        TsProbe probe = TsProbe.of(tsStream(192, 4, 4));
        // 不足一个包的尾巴:只处理整包部分
        byte[] src = new byte[192 * 2 + 50];
        src[4] = 0x47;
        src[192 + 4] = 0x47;
        byte[] out = TsProbe.repackUnit(src, 192 * 2, probe);
        assertNotNull(out);
        assertEquals(188 * 2, out.length);
        // len 超过数组长度属于调用错误:返回 null,不抛
        assertNull(TsProbe.repackUnit(src, src.length + 1, probe));
    }
}
