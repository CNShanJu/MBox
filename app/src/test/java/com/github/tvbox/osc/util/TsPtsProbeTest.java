package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 跨线路补片的两道纯逻辑单测:播放列表切分一致性 + 替补分片的 PTS 接缝。
 * <p>
 * 事实前提:两条线路可能是不同转码/不同切分,同样叫"第 500 片"覆盖的时间段不同 ——
 * 拿错片段拼进去,文件不报错但时间轴错乱,所以宁可"不补"(退回缺片完成/失败),也不能拼错。
 */
public class TsPtsProbeTest {

    // ------------------------------------------------------------------
    // 造数据:标准 188 包 + 带 PTS 的 PES 头
    // ------------------------------------------------------------------

    private static void writePts(byte[] d, int off, long pts) {
        d[off] = (byte) (0x21 | ((pts >> 29) & 0x0E));
        d[off + 1] = (byte) ((pts >> 22) & 0xFF);
        d[off + 2] = (byte) (((pts >> 14) & 0xFE) | 0x01);
        d[off + 3] = (byte) ((pts >> 7) & 0xFF);
        d[off + 4] = (byte) (((pts << 1) & 0xFE) | 0x01);
    }

    /** 一个 188 字节包:负载起始 + PES 头(仅 PTS) */
    private static void packet(byte[] buf, int off, double seconds) {
        long pts = (long) Math.round(seconds * 90000.0);
        buf[off] = 0x47;
        buf[off + 1] = 0x40; // payload_unit_start_indicator=1
        buf[off + 2] = 0x00;
        buf[off + 3] = 0x10; // 仅负载
        buf[off + 4] = 0x00;
        buf[off + 5] = 0x00;
        buf[off + 6] = 0x01; // PES 起始码
        buf[off + 7] = (byte) 0xE0; // stream_id
        buf[off + 8] = 0x00;
        buf[off + 9] = 0x00; // PES 长度
        buf[off + 10] = (byte) 0x80; // '10' 标记
        buf[off + 11] = (byte) 0x80; // PTS only
        buf[off + 12] = 0x05;        // 头长度
        writePts(buf, off + 13, pts);
    }

    /** 造一个分段:开头 PTS=from、结尾 PTS=to 的一串包 */
    private static byte[] segment(double from, double to) {
        byte[] buf = new byte[188 * 3];
        packet(buf, 0, from);
        packet(buf, 188, (from + to) / 2);
        packet(buf, 376, to);
        return buf;
    }

    @Test
    public void readsFirstAndLastPts() {
        byte[] seg = segment(0.0, 6.0);
        assertTrue(TsPtsProbe.looksLikeTs(seg, seg.length));
        assertEquals(0.0, TsPtsProbe.firstPtsSeconds(seg, seg.length), 0.001);
        assertEquals(6.0, TsPtsProbe.lastPtsSeconds(seg, seg.length), 0.001);
        // 大时间戳(长视频)也要能还原:26 小时内的 33 位 PTS
        byte[] late = segment(90000.0, 90006.0);
        assertEquals(90000.0, TsPtsProbe.firstPtsSeconds(late, late.length), 0.001);
        assertEquals(90006.0, TsPtsProbe.lastPtsSeconds(late, late.length), 0.001);
    }

    @Test
    public void rejectsNonTsData() {
        byte[] junk = new byte[188 * 3]; // 全 0:同步字节不对
        assertFalse(TsPtsProbe.looksLikeTs(junk, junk.length));
        assertEquals(-1.0, TsPtsProbe.firstPtsSeconds(junk, junk.length), 0.001);
        byte[] empty = new byte[0];
        assertEquals(-1.0, TsPtsProbe.firstPtsSeconds(empty, 0), 0.001);
    }

    @Test
    public void continuity_acceptsProperlyStitchedSegment() {
        // 前一片 0~6s,替补片 6~12s,后一片 12~18s:首尾相接 → 通过
        assertNull(TsPtsProbe.continuityProblem(6.0, 6.0, 12.0, 12.0));
        // 允许少量抖动/重叠
        assertNull(TsPtsProbe.continuityProblem(6.0, 6.3, 12.3, 12.0));
        assertNull(TsPtsProbe.continuityProblem(6.0, 5.9, 11.9, 12.0));
    }

    @Test
    public void continuity_rejectsWrongSegment() {
        // 拿到的是下一片(+6s):与前片接不上
        assertNotNull(TsPtsProbe.continuityProblem(6.0, 12.0, 18.0, 12.0));
        // 拿到的是前一片(-6s):重叠过多
        assertNotNull(TsPtsProbe.continuityProblem(6.0, 0.0, 6.0, 12.0));
        // 完全另一条时间轴(不同起点)
        assertNotNull(TsPtsProbe.continuityProblem(6.0, 3600.0, 3606.0, 12.0));
        // 片内 PTS 倒挂
        assertNotNull(TsPtsProbe.continuityProblem(6.0, 12.0, 6.0, 12.0));
    }

    @Test
    public void continuity_needsAtLeastOneNeighbour() {
        // 前后都没有本地分片(例如整段都缺):无法判定,不补
        assertNotNull(TsPtsProbe.continuityProblem(-1, 6.0, 12.0, -1));
        // 只有前一片也够判定
        assertNull(TsPtsProbe.continuityProblem(6.0, 6.0, 12.0, -1));
        assertNull(TsPtsProbe.continuityProblem(-1, 6.0, 12.0, 12.0));
    }

    // ------------------------------------------------------------------
    // 播放列表切分一致性
    // ------------------------------------------------------------------

    private static String playlist(double... durations) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:7\n");
        for (int i = 0; i < durations.length; i++) {
            sb.append("#EXTINF:").append(durations[i]).append(",\nseg").append(i).append(".ts\n");
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    @Test
    public void parsesExtinfDurations() {
        List<Double> d = HlsPlaylistLayout.durations(playlist(6.0, 6.0, 5.5, 0.5));
        assertEquals(4, d.size());
        assertEquals(6.0, d.get(0), 0.001);
        assertEquals(5.5, d.get(2), 0.001);
        assertEquals(0.5, d.get(3), 0.001);
    }

    @Test
    public void sameLayoutWhenCountsAndDurationsMatch() {
        List<Double> a = HlsPlaylistLayout.durations(playlist(6.0, 6.0, 6.0));
        List<Double> b = HlsPlaylistLayout.durations(playlist(6.0, 6.01, 5.99));
        assertTrue(HlsPlaylistLayout.compare(a, b).same);
    }

    @Test
    public void rejectsDifferentSegmentCountOrDuration() {
        List<Double> a = HlsPlaylistLayout.durations(playlist(6.0, 6.0, 6.0, 6.0));
        // 不同切分:片数不同(470 片 vs 940 片那种情形)
        List<Double> b = HlsPlaylistLayout.durations(playlist(12.0, 12.0));
        HlsPlaylistLayout.Comparison c = HlsPlaylistLayout.compare(a, b);
        assertFalse(c.same);
        assertTrue(c.reason.contains("分片数不同"));
        // 片数相同但时长不同(切分点不一样)
        List<Double> c2 = HlsPlaylistLayout.durations(playlist(6.0, 6.0, 6.0, 12.0));
        HlsPlaylistLayout.Comparison cmp = HlsPlaylistLayout.compare(a, c2);
        assertFalse(cmp.same);
        assertTrue(cmp.reason.contains("第 3 片"));
    }

    @Test
    public void refusesWhenNoExtinf() {
        assertTrue(HlsPlaylistLayout.durations("#EXTM3U\nseg0.ts\n").isEmpty());
        assertFalse(HlsPlaylistLayout.compare(java.util.Collections.<Double>emptyList(),
                Arrays.asList(6.0)).same);
    }
}
