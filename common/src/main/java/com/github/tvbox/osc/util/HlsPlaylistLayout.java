package com.github.tvbox.osc.util;

import java.util.ArrayList;
import java.util.List;

/**
 * HLS 播放列表的"切分形态"(纯计算,可 JVM 单测):片数与每片的 {@code #EXTINF} 时长。
 *
 * <p>用途(跨线路补片 4.8③):一条线路缺了某几片时,去这条集的另一条线路把"同一片"取回来补上。
 * 但两条线路可能是<b>不同转码/不同切分</b>(比如 A 是 940 片×6s、B 是 470 片×12s),
 * 同样叫"第 500 片"覆盖的时间段根本不是同一段 —— 拼进去文件不报错,但时间轴错乱(花屏/音画不同步)。
 * 所以在取片之前必须先证明两份播放列表**切分一致**:片数相同,且逐片时长在容差内一致。
 *
 * <p>为什么用 EXTINF 而不是分片字节大小:字节大小要额外发请求才知道,而 EXTINF 只在播放列表里,
 * 零成本;时长一致是"同一段"的必要条件,再叠加取片后的 PTS 连续性校验(见 {@link TsPtsProbe})就够可靠了。
 */
public final class HlsPlaylistLayout {

    /** 逐片时长容差(秒):不同 CDN 的 EXTINF 可能只写到小数点后 2~3 位 */
    public static final double DURATION_TOLERANCE_SEC = 0.5;

    private HlsPlaylistLayout() {
    }

    /**
     * 按出现顺序取每个分片的 {@code #EXTINF} 时长(秒)。
     * 解析不出数字的记 0(由 {@link #compare} 判定为不可比,不猜)。
     */
    public static List<Double> durations(String playlistText) {
        List<Double> out = new ArrayList<>();
        if (playlistText == null || playlistText.isEmpty()) return out;
        for (String raw : playlistText.split("\r?\n")) {
            String line = raw.trim();
            if (!line.startsWith("#EXTINF:")) continue;
            String v = line.substring("#EXTINF:".length()).trim();
            int comma = v.indexOf(',');
            if (comma >= 0) v = v.substring(0, comma).trim();
            try {
                out.add(Double.parseDouble(v));
            } catch (NumberFormatException e) {
                out.add(0d);
            }
        }
        return out;
    }

    /** 比较结果:不一致时带上可读原因(直接进日志/任务信息,便于事后核对) */
    public static final class Comparison {
        public final boolean same;
        public final String reason;

        Comparison(boolean same, String reason) {
            this.same = same;
            this.reason = reason;
        }
    }

    private static final Comparison SAME = new Comparison(true, "");

    /**
     * 两份播放列表的切分是否一致:片数相同 + 每片时长逐个在容差内。
     * 任一条件不满足都判"不可比"(宁可不补,也不拼出时间轴错乱的文件)。
     */
    public static Comparison compare(List<Double> primary, List<Double> candidate) {
        return compare(primary, candidate, DURATION_TOLERANCE_SEC);
    }

    public static Comparison compare(List<Double> primary, List<Double> candidate, double toleranceSec) {
        if (primary == null || candidate == null || primary.isEmpty() || candidate.isEmpty()) {
            return new Comparison(false, "播放列表缺少 #EXTINF 时长,无法判定切分是否一致");
        }
        if (primary.size() != candidate.size()) {
            return new Comparison(false, "分片数不同(" + primary.size() + " vs " + candidate.size() + ")");
        }
        for (int i = 0; i < primary.size(); i++) {
            Double a = primary.get(i);
            Double b = candidate.get(i);
            if (a == null || b == null) return new Comparison(false, "第 " + i + " 片缺时长");
            if (a <= 0 || b <= 0) return new Comparison(false, "第 " + i + " 片时长非法(" + a + " vs " + b + ")");
            if (Math.abs(a - b) > toleranceSec) {
                return new Comparison(false, "第 " + i + " 片时长差 " + String.format(java.util.Locale.ROOT, "%.1f", Math.abs(a - b))
                        + "s(" + trim(a) + " vs " + trim(b) + ")");
            }
        }
        return SAME;
    }

    private static String trim(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
