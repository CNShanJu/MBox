package com.github.tvbox.osc.subtitle.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Time(字幕时间模型,SRT/ASS/帧率三种格式共用)解析与格式化单测。 */
public class TimeTest {

    // ── 解析 → 毫秒 ──

    @Test
    public void parseSrtFormat_hhmmssMs() {
        assertEquals(1000, new Time("hh:mm:ss,ms", "00:00:01,000").mseconds);
        assertEquals(3723004, new Time("hh:mm:ss,ms", "01:02:03,004").mseconds); // 1h2m3s4ms
    }

    @Test
    public void parseAssFormat_hmmssCs() {
        assertEquals(6500, new Time("h:mm:ss.cs", "0:00:06.50").mseconds);   // 6s500ms
        assertEquals(3742510, new Time("h:mm:ss.cs", "1:02:22.51").mseconds); // 1h2m22s510ms
    }

    @Test
    public void parseFramesFormat_fps() {
        // 0s1s + 24 帧 @25fps = 960ms → 1960ms
        assertEquals(1960, new Time("h:m:s:f/fps", "0:00:01:24/25").mseconds);
    }

    // ── 格式化(往返一致性以 SRT 保证;ASS 小时位按现实现补零)──

    @Test
    public void formatSrtRoundTrip() {
        assertEquals("00:00:01,234",
                new Time("hh:mm:ss,ms", "00:00:01,234").getTime("hh:mm:ss,ms"));
        assertEquals("01:02:03,004",
                new Time("hh:mm:ss,ms", "01:02:03,004").getTime("hh:mm:ss,ms"));
    }

    @Test
    public void formatAss_padsFields() {
        // 现实现小时位补零为两位,与 .ass 文本样式一致保留毫秒 10ms 精度
        assertEquals("00:00:06.50", new Time("h:mm:ss.cs", "0:00:06.50").getTime("h:mm:ss.cs"));
    }
}
