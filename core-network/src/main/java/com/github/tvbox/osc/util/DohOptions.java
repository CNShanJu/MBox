package com.github.tvbox.osc.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 安全 DNS 选项的<b>纯逻辑</b>事实源(无 Android / 无配置依赖,可 JVM 单测)。
 * <p>
 * 为什么把下标映射与夹取抽出来:设置页取下标、DoH 客户端解析 url、备份恢复写回的旧下标
 * 分散在多处时极易漂移(历史事故:{@code doh_url} 为 4~6 的备份直接
 * {@code dnsHttpsList[getDohUrl()]} 越界崩在设置页)。这里给出<b>一份</b>选项表 +
 * 一份映射规则,UI 展示与网络侧解析都从这里取。
 * <p>
 * 之所以放在 :core-network 而不是 :common:门禁里 {@code :core-network} 禁止依赖 {@code :common}
 * (基础模块之间不得互相牵连),而这份表正是"网络侧自己要用"的 —— 跟随使用方落位,
 * 不为此破依赖方向。
 */
public final class DohOptions {

    /** 选项文案(下标即 {@code SystemConfig.getDohUrl()} 的存储值) */
    public static final List<String> LABELS = Collections.unmodifiableList(
            Arrays.asList("关闭", "腾讯", "阿里", "360"));

    private DohOptions() {
    }

    public static int count() {
        return LABELS.size();
    }

    /**
     * 下标 → 选项文案,越界自动收进 [0, count-1]。
     * <p>
     * 为什么夹到最后一个有效项而不是"关闭":老备份里的 4/5/6 表示"用户当初选了 DoH",
     * 夹成"关闭"会让设置页显示与实际意图相反;而且夹取只影响<b>显示</b>,
     * 真正生效与否由 {@link #url(int)} 决定(4/5/6 一律不启用 DoH,因当前列表已无这些项)。
     */
    public static String label(int index) {
        if (LABELS.isEmpty()) return "";
        return LABELS.get(Math.max(0, Math.min(LABELS.size() - 1, index)));
    }

    /**
     * 下标 → DoH 接口地址;空串表示不启用安全 DNS。
     * <p>
     * 语义与历史实现保持一致(只认 1/2/3):<b>越界/未知下标 = 关闭</b>,绝不抛异常 ——
     * 备份恢复、降级安装都可能把老版本的 4/5/6 写回来。
     */
    public static String url(int index) {
        switch (index) {
            case 1:
                return "https://doh.pub/dns-query";
            case 2:
                return "https://dns.alidns.com/dns-query";
            case 3:
                return "https://doh.360.cn/dns-query";
            default:
                return "";
        }
    }
}
