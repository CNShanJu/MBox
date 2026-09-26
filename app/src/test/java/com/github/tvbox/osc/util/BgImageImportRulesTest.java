package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 背景图导入规则单测(纯 JVM,不碰真机):格式识别 / GIF 拒绝 / 体积上限 / 解码采样 / WebP 策略。
 */
public class BgImageImportRulesTest {

    private static byte[] header(int... bytes) {
        byte[] h = new byte[16];
        for (int i = 0; i < bytes.length; i++) h[i] = (byte) bytes[i];
        return h;
    }

    private static final byte[] JPEG = header(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F');
    private static final byte[] PNG = header(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    private static final byte[] GIF = header('G', 'I', 'F', '8', '9', 'a');
    private static final byte[] WEBP = header('R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P');
    private static final byte[] HEIC = header(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c');

    @Test
    public void formatDetection() {
        assertEquals("jpeg", BgImageImportRules.formatOf(JPEG));
        assertEquals("png", BgImageImportRules.formatOf(PNG));
        assertEquals("gif", BgImageImportRules.formatOf(GIF));
        assertEquals("webp", BgImageImportRules.formatOf(WEBP));
        assertEquals("heif", BgImageImportRules.formatOf(HEIC));
        assertNull(BgImageImportRules.formatOf(header('M', 'Z', 0x90)));
        assertNull(BgImageImportRules.formatOf(new byte[0]));
    }

    /** 改了扩展名的 GIF 也拦得住(按文件头判定) */
    @Test
    public void gifRejectedEvenWithWrongMime() {
        assertEquals("不支持 GIF,请用静态图片", BgImageImportRules.rejectReason(1024, "image/jpeg", GIF));
        assertEquals("不支持 GIF,请用静态图片", BgImageImportRules.rejectReason(1024, "image/gif", JPEG));
        assertNull(BgImageImportRules.rejectReason(1024, "image/jpeg", JPEG));
    }

    @Test
    public void sizeLimit() {
        assertNull(BgImageImportRules.rejectReason(BgImageImportRules.MAX_BYTES, "image/png", PNG));
        assertEquals("图片超过 30MB,请换一张小一点的",
                BgImageImportRules.rejectReason(BgImageImportRules.MAX_BYTES + 1, "image/png", PNG));
        assertEquals(30L * 1024 * 1024, BgImageImportRules.MAX_BYTES);
    }

    /** 采样率:长边落到 (上限/2, 上限],只缩不放 */
    @Test
    public void sampleSize() {
        assertEquals(1, BgImageImportRules.sampleSizeFor(2560, 1440, 2560));
        assertEquals(1, BgImageImportRules.sampleSizeFor(1000, 1000, 2560));
        assertEquals(2, BgImageImportRules.sampleSizeFor(5120, 2880, 2560));
        assertEquals(4, BgImageImportRules.sampleSizeFor(8000, 6000, 2560));
        assertEquals(1, BgImageImportRules.sampleSizeFor(0, 0, 2560));
        // 缩完长边必须 <= 上限,且不会缩过头(>= 上限/2;2 的幂采样,略超一点就会减半)
        for (int longSide : new int[]{2561, 3000, 4096, 9000, 12000}) {
            int s = BgImageImportRules.sampleSizeFor(longSide, longSide / 2, 2560);
            int out = longSide / s;
            assertTrue("out=" + out, out <= 2560);
            assertTrue("out=" + out, out >= 2560 / 2);
        }
    }

    /** 照片走有损,带透明通道的走无损 */
    @Test
    public void webpPolicy() {
        assertFalse(BgImageImportRules.useLosslessWebp(false));
        assertTrue(BgImageImportRules.useLosslessWebp(true));
        assertTrue(BgImageImportRules.WEBP_QUALITY >= 85 && BgImageImportRules.WEBP_QUALITY <= 100);
    }
}
