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
 * <b>字节范围</b>({@code #EXT-X-BYTERANGE}:分片是同一个文件的不同区间)同样是"是不是同一片"的
 * 判据 —— 同一个 URL 的不同范围是完全不同的内容,漏掉范围就会把两份内容的碎片拼在一起,
 * 因此带范围的清单用 {@link #of(List, List)} 把范围一并算进指纹。
 * 列表为空返回空串,调用方按"无法判定"处理(保留现有碎片,不误删)。
 */
public final class SegmentListSignature {

    private SegmentListSignature() {
    }

    /**
     * 分片列表指纹(无字节范围的清单);
     * {@code segments} 为空/为 null 时返回空串(表示无法判定)。
     */
    public static String of(List<String> segments) {
        return of(segments, null);
    }

    /**
     * 分片列表指纹(含字节范围)。
     *
     * @param segments 分片 URL(顺序即播放顺序)
     * @param ranges   与 {@code segments} 一一对应的字节范围;整文件分片对应的项为 null
     *                 (整表无范围时与 {@link #of(List)} 得到<b>同一枚指纹</b>,保证升级不误清已有碎片)
     */
    public static String of(List<String> segments, List<HlsMediaPlaylist.ByteRange> ranges) {
        if (segments == null || segments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(segments.size() * 64);
        sb.append(segments.size()).append('\n');
        for (int i = 0; i < segments.size(); i++) {
            sb.append(segments.get(i) == null ? "" : segments.get(i)).append('\n');
            HlsMediaPlaylist.ByteRange r = ranges == null || i >= ranges.size() ? null : ranges.get(i);
            // 只对有范围的片追加,保证"整文件分片清单"的指纹与旧格式完全一致(升级不清在下的碎片)
            if (r != null) sb.append('@').append(r.offset).append('+').append(r.length).append('\n');
        }
        return MD5.encode(sb.toString());
    }
}
