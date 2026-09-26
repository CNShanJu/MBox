package com.github.tvbox.osc.util;

import java.util.List;

/**
 * 分片列表指纹(纯计算,无 Android 依赖,可 JVM 单测)。
 * <p>
 * 用途:下载任务"重新解析地址(换线路)"后,同一个 episode 可能拿到<b>另一份播放列表</b> ——
 * 分片 URL 与切分方式都可能不同。此时磁盘上旧地址下好的碎片再复用,就会把两份视频的碎片
 * 拼成一个放不了的文件,所以进下载前按指纹比对:不一致就把碎片目录清空重下。
 * <p>
 * 指纹按"分片数量 + URL 顺序"算:数量、顺序、任一 URL 变化都会得到不同指纹。
 * 列表为空返回空串,调用方按"无法判定"处理(保留现有碎片,不误删)。
 */
public final class SegmentListSignature {

    private SegmentListSignature() {
    }

    /** 分片列表指纹;{@code segments} 为空/为 null 时返回空串(表示无法判定) */
    public static String of(List<String> segments) {
        if (segments == null || segments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(segments.size() * 64);
        sb.append(segments.size()).append('\n');
        for (String s : segments) {
            sb.append(s == null ? "" : s).append('\n');
        }
        return MD5.encode(sb.toString());
    }
}
