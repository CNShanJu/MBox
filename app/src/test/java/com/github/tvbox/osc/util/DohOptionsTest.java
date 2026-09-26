package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * 安全 DNS(DoH)选项表的纯逻辑单测(纯 JVM)。
 * <p>
 * 回归背景:老备份/降级安装里的 {@code doh_url} 可能是 4~6(那时列表有 7 项),
 * 直接 {@code dnsHttpsList[getDohUrl()]} 越界崩在设置页;而"设置改了要重启才生效"
 * 要求下标→url 的映射被单独测住 —— 设置页与网络侧读的必须是同一份表。
 */
public class DohOptionsTest {

    @Test
    public void labelsKeepStoredIndexOrder() {
        // 下标 = SystemConfig 里存的值,顺序变了会让所有人的设置串位
        assertEquals(Arrays.asList("关闭", "腾讯", "阿里", "360"), DohOptions.LABELS);
        assertEquals(4, DohOptions.count());
    }

    @Test
    public void labelsAreImmutable() {
        // 共享表被外部原地改坏会让 UI 与 DoH 客户端解析不一致
        assertThrows(UnsupportedOperationException.class, () -> DohOptions.LABELS.add("Google"));
    }

    @Test
    public void urlMapsKnownOptions() {
        assertEquals("", DohOptions.url(0));
        assertEquals("https://doh.pub/dns-query", DohOptions.url(1));
        assertEquals("https://dns.alidns.com/dns-query", DohOptions.url(2));
        assertEquals("https://doh.360.cn/dns-query", DohOptions.url(3));
    }

    @Test
    public void unknownIndexDisablesDohInsteadOfThrowing() {
        // 老备份的 4/5/6(Google/AdGuard/Quad9 已下线)与任何越界值都只能"关闭",绝不能抛
        assertTrue(DohOptions.url(4).isEmpty());
        assertTrue(DohOptions.url(5).isEmpty());
        assertTrue(DohOptions.url(6).isEmpty());
        assertTrue(DohOptions.url(-1).isEmpty());
        assertTrue(DohOptions.url(Integer.MAX_VALUE).isEmpty());
        assertTrue(DohOptions.url(Integer.MIN_VALUE).isEmpty());
    }

    @Test
    public void labelClampsOutOfRangeIndexIntoList() {
        assertEquals("关闭", DohOptions.label(0));
        assertEquals("360", DohOptions.label(3));
        // 越界夹到最后一个有效项(保住"我要用 DoH"的意图,而不是显示成"关闭")
        assertEquals("360", DohOptions.label(4));
        assertEquals("360", DohOptions.label(6));
        assertEquals("360", DohOptions.label(Integer.MAX_VALUE));
        assertEquals("关闭", DohOptions.label(-1));
        assertEquals("关闭", DohOptions.label(Integer.MIN_VALUE));
    }

    @Test
    public void everyLabelHasUsableIndex() {
        // 设置页按 label 下标写回配置:任意 label 下标都必须能取到 url(空串=关闭),不允许崩
        for (int i = 0; i < DohOptions.count(); i++) {
            String label = DohOptions.label(i);
            assertFalse(label.isEmpty());
            assertEquals(i == 0, DohOptions.url(i).isEmpty());
        }
    }
}
