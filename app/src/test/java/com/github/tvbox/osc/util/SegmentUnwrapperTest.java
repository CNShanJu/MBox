package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 包裹型分片剥壳单测(纯 JVM)。
 * <p>
 * 真实现场:源给的 m3u8 里 879 个分片全指向 `p.ananas.chaoxing.com/.../origin.jpg`,
 * 直接抓下来看是"1×1 PNG 头 + tEXt 块(关键字 TS_RAW) + 完整 MPEG-TS" —— 剥壳后
 * 从偏移 107 起 1614/1614 个 188 字节包同步字节全中。这里用合成数据把这个判据钉住。
 */
public class SegmentUnwrapperTest {

    /** 造 n 个 188 字节 TS 包(包首放同步字节 0x47) */
    private static byte[] tsPackets(int n) {
        byte[] b = new byte[n * SegmentUnwrapper.PACKET_SIZE];
        for (int i = 0; i < n; i++) b[i * SegmentUnwrapper.PACKET_SIZE] = SegmentUnwrapper.SYNC_BYTE;
        return b;
    }

    /** 把 TS 包用"容器头 + 载荷 + 容器尾"包起来,模拟 PNG/JPEG 壳 */
    private static byte[] wrap(int headLen, int packets, int tailLen) {
        byte[] ts = tsPackets(packets);
        byte[] out = new byte[headLen + ts.length + tailLen];
        // 容器头:放点像 PNG/JPEG 的字节(不能出现 188 对齐的同步序列)
        out[0] = (byte) 0x89;
        out[1] = 0x50;
        out[2] = 0x4E;
        out[3] = 0x47;
        for (int i = headLen; i < headLen + ts.length; i++) out[i] = ts[i - headLen];
        for (int i = headLen + ts.length; i < out.length; i++) out[i] = (byte) 0xB5; // 容器尾巴
        return out;
    }

    private static byte[] headOf(byte[] data, int n) {
        int len = Math.min(n, data.length);
        byte[] head = new byte[len];
        System.arraycopy(data, 0, head, 0, len);
        return head;
    }

    @Test
    public void wrappedTs_likeRealCase_isLocated() {
        // 实测容器头 107 字节、载荷 1614 包、尾巴 20 字节
        byte[] data = wrap(107, 64, 20);
        SegmentUnwrapper.Plan plan = SegmentUnwrapper.plan(headOf(data, 8192), data.length);
        assertNotNull("包裹型分片必须被识别出来", plan);
        assertEquals(107, plan.offset);
        // 尾部 20 字节不足一包:只按整包取,保证后续分片不会错位
        assertEquals(64 * SegmentUnwrapper.PACKET_SIZE, plan.length);
        assertEquals(data.length - 20, plan.endOffset());
    }

    @Test
    public void plainTs_hasNothingToStrip() {
        // 纯 TS:plan 会给出 offset=0(没有容器头要丢) —— 调用方是先经 TsProbe 判"已是 TS/MP4"
        // 直接走正常路径、根本不会问 plan;这里把这个契约钉住,避免有人误以为 plan!=null 就要剥壳
        byte[] plain = tsPackets(32);
        SegmentUnwrapper.Plan plan = SegmentUnwrapper.plan(headOf(plain, 8192), plain.length);
        assertNotNull(plan);
        assertEquals("纯 TS 没有容器头,偏移必须是 0", 0, plan.offset);
        assertEquals(plain.length, plan.length);
    }

    @Test
    public void realImageWithoutTsRun_isNotUnwrapped() {
        // 真图片:没有 188 对齐的同步序列 → 不剥壳(交给"内容不是视频"的失败路径)
        byte[] img = wrap(0, 64, 0);
        for (int i = 0; i < img.length; i++) {
            if (i % SegmentUnwrapper.PACKET_SIZE == 0) img[i] = (byte) 0x11; // 打掉同步字节
        }
        assertNull(SegmentUnwrapper.plan(headOf(img, 8192), img.length));
    }

    @Test
    public void tooShortOrUnknownLength_handled() {
        // 载荷太短(不足 8 包):不剥壳,避免把碰巧的字节序列当真
        byte[] tiny = wrap(64, 4, 0);
        assertNull(SegmentUnwrapper.plan(headOf(tiny, 8192), tiny.length));
        // 总长未知:仍能定位起点,长度为 -1(边下边判,只落整包)
        byte[] data = wrap(200, 64, 9);
        SegmentUnwrapper.Plan plan = SegmentUnwrapper.plan(headOf(data, 8192), -1);
        assertNotNull(plan);
        assertEquals(200, plan.offset);
        assertEquals(-1, plan.length);
        assertEquals(-1, plan.endOffset());
        // 头部太短:直接看不出后缀 → 不剥壳
        assertNull(SegmentUnwrapper.plan(new byte[16], 4096));
        assertNull(SegmentUnwrapper.plan(null, 4096));
    }

    @Test
    public void wrapperHeadBeyondLimit_isRejected() {
        // 容器头超过 4KB 上限:不再认为"载荷占绝大部分",不剥壳(宁可判失败也不乱拆)
        byte[] data = wrap(SegmentUnwrapper.MAX_WRAPPER_HEAD_BYTES + 200, 64, 0);
        assertNull(SegmentUnwrapper.plan(headOf(data, 20000), data.length));
        assertTrue(SegmentUnwrapper.MAX_WRAPPER_HEAD_BYTES < 8192);
    }
}
