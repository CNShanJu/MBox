package com.github.tvbox.osc.util;

/**
 * MPEG-TS 分片的 PTS 探测(纯计算,可 JVM 单测):取出分片里首个/末个 PES 的呈现时间戳。
 *
 * <p>用途(跨线路补片 4.8③ 的第二道校验):从别的线路补下来的那一片,除了"播放列表切分一致"之外,
 * 还要能证明它接在<b>本线路相邻分片的时间轴上</b> —— 比对本片首 PTS 与前一片末 PTS、
 * 本片末 PTS 与后一片首 PTS 的差值。差值是几分钟或负数,说明拿到的根本不是这一段(补错更糟),
 * 直接放弃补片,回到"缺片完成/失败"的既有路径,绝不拼出时间轴错乱的文件。
 *
 * <p>只处理标准 188 字节包(下载落盘的分片已由下载侧统一对齐/剥壳成 188 包);不是干净 188 包时返回 -1,
 * 由调用方按"无法校验"处理(不补)。
 *
 * <p>实现取舍:不解析 PAT/PMT 去找视频 PID —— 一段 TS 里音视频 PTS 本来就在同一时间轴上
 * (相差远小于容差),取任一 PES 的 PTS 足以判定"是不是同一段";省掉 PSI 解析,逻辑更小也更好测。
 */
public final class TsPtsProbe {

    private static final int PACKET_SIZE = 188;
    private static final byte SYNC = 0x47;
    /** 90kHz 时钟 */
    private static final double PTS_CLOCK = 90000.0;

    private TsPtsProbe() {
    }

    /** 该分片是否是可解析的 188 包 TS(否则 PTS 无从谈起) */
    public static boolean looksLikeTs(byte[] data, int length) {
        int len = Math.min(length, data == null ? 0 : data.length);
        if (len < PACKET_SIZE * 2) return false;
        if (data[0] != SYNC || data[PACKET_SIZE] != SYNC) return false;
        // 多验几个包,避免"恰好撞上 0x47"
        for (int off = PACKET_SIZE * 2; off + 1 <= len && off < PACKET_SIZE * 5; off += PACKET_SIZE) {
            if (data[off] != SYNC) return false;
        }
        return true;
    }

    /** 首个 PES 的 PTS(秒);找不到返回 -1 */
    public static double firstPtsSeconds(byte[] data, int length) {
        return scan(data, length, true);
    }

    /** 末个 PES 的 PTS(秒);找不到返回 -1 */
    public static double lastPtsSeconds(byte[] data, int length) {
        return scan(data, length, false);
    }

    private static double scan(byte[] data, int length, boolean firstOnly) {
        int len = Math.min(length, data == null ? 0 : data.length);
        if (len < PACKET_SIZE) return -1;
        double found = -1;
        for (int off = 0; off + PACKET_SIZE <= len; off += PACKET_SIZE) {
            if (data[off] != SYNC) return -1; // 不是干净 188 包:整段不可信
            int b1 = data[off + 1] & 0xFF;
            int b3 = data[off + 3] & 0xFF;
            if ((b1 & 0x40) == 0) continue; // 非负载起始包,没有 PES 头
            int afc = (b3 >> 4) & 0x03;     // 01=仅负载 10=仅适配域 11=适配域+负载
            if (afc == 0 || afc == 2) continue; // 没有负载
            int p = off + 4;
            if (afc == 3) {
                if (p >= len) break;
                int afLen = data[p] & 0xFF;
                p += 1 + afLen;
            }
            if (p + 14 > off + PACKET_SIZE || p + 14 > len) continue; // 放不下 PES 头
            if (data[p] != 0x00 || data[p + 1] != 0x00 || data[p + 2] != 0x01) continue; // PES 起始码
            int flags = data[p + 7] & 0xFF;
            int ptsFlag = (flags >> 6) & 0x03; // 10=PTS 11=PTS+DTS
            if (ptsFlag != 0x02 && ptsFlag != 0x03) continue;
            long pts = readPts(data, p + 9);
            if (pts < 0) continue;
            double sec = pts / PTS_CLOCK;
            if (firstOnly) return sec;
            found = sec;
        }
        return found;
    }

    /** 33 位 PTS:5 字节,每字节里有 1 个标志位要丢掉 */
    private static long readPts(byte[] d, int i) {
        if (i + 5 > d.length) return -1;
        long p0 = d[i] & 0xFFL, p1 = d[i + 1] & 0xFFL, p2 = d[i + 2] & 0xFFL,
                p3 = d[i + 3] & 0xFFL, p4 = d[i + 4] & 0xFFL;
        return ((p0 & 0x0EL) << 29) | (p1 << 22) | ((p2 & 0xFEL) << 14) | (p3 << 7) | ((p4 & 0xFEL) >> 1);
    }

    // ------------------------------------------------------------------
    // 接缝连续性判定(补片用)
    // ------------------------------------------------------------------

    /** 允许的前向间隙(秒):相邻分片本就该首尾相接,留一点抖动余量 */
    public static final double MAX_GAP_SEC = 2.0;
    /** 允许的反向重叠(秒):少量重叠(编码器 B 帧/GOP 对齐)属正常 */
    public static final double MAX_OVERLAP_SEC = 0.5;

    /**
     * 判定替补分片是否接在本线路相邻分片的时间轴上。
     *
     * @param prevLastPts  前一片(on disk,本线路)的末 PTS(秒);未知传 -1
     * @param subFirstPts  替补片的首 PTS(秒)
     * @param subLastPts   替补片的末 PTS(秒)
     * @param nextFirstPts 后一片(on disk,本线路)的首 PTS(秒);未知传 -1
     * @return null=通过;非 null=不可用的可读原因
     */
    public static String continuityProblem(double prevLastPts, double subFirstPts,
            double subLastPts, double nextFirstPts) {
        boolean hasPrev = prevLastPts >= 0;
        boolean hasNext = nextFirstPts >= 0;
        if (!hasPrev && !hasNext) return "相邻分片都不在本地,无法判定时间轴是否接得上";
        if (subFirstPts < 0 || subLastPts < 0) return "替补分片取不到 PTS(不是可解析的 TS)";
        if (subLastPts < subFirstPts) return "替补分片 PTS 倒挂";
        if (hasPrev) {
            double gap = subFirstPts - prevLastPts;
            if (gap > MAX_GAP_SEC) {
                return String.format(java.util.Locale.ROOT,
                        "与前一片接不上(首 PTS 比前片末 PTS 晚 %.1fs)", gap);
            }
            if (gap < -MAX_OVERLAP_SEC) {
                return String.format(java.util.Locale.ROOT,
                        "与前一片重叠过多(比前片末 PTS 早 %.1fs)", -gap);
            }
        }
        if (hasNext) {
            double gap = nextFirstPts - subLastPts;
            if (gap > MAX_GAP_SEC) {
                return String.format(java.util.Locale.ROOT,
                        "与后一片接不上(末 PTS 比后片首 PTS 早 %.1fs)", gap);
            }
            if (gap < -MAX_OVERLAP_SEC) {
                return String.format(java.util.Locale.ROOT,
                        "与后一片重叠过多(末 PTS 比后片首 PTS 晚 %.1fs)", -gap);
            }
        }
        return null;
    }
}
