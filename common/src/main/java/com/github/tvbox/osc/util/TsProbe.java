package com.github.tvbox.osc.util;

/**
 * 分片产物内容自检(纯逻辑,可 JVM 单测)。
 *
 * <p>为什么需要它:HLS 下载是"分片字节直接拼接",产物未必是标准 188 字节包的 MPEG-TS ——
 * 也可能是 <b>192 字节包(M2TS,每包前面多 4 字节 TP_extra_header)</b> 或 <b>204 字节包(188 + 尾部 16 字节 FEC)</b>。
 * 后两种 ExoPlayer/IJK 都能直接播(所以下载完的文件在 App 里能看),但 Android 的 MediaExtractor
 * 只按 188 字节对齐嗅探 —— 拿这种文件去 setDataSource 会直接建不出提取器:
 * <pre>
 *   NuMediaExtractor: initMediaExtractor: failed to create MediaExtractor
 *   java.io.IOException: Failed to instantiate extractor.
 * </pre>
 * 于是重封装失败 → 回退 .ts 后缀,表现就是"下载下来老是一个大 ts 而不是 mp4"。
 *
 * <p>本类只做判断,不改数据;192/204 → 188 的转换见 {@link #repackUnit}。
 */
public final class TsProbe {

    /** MPEG-TS 标准包长 */
    public static final int TS_PACKET_SIZE = 188;
    /** 同步字节 */
    public static final byte SYNC_BYTE = 0x47;
    /** 至少能验 3 个最大包长(204)的探测窗口 */
    public static final int MIN_HEAD = 3 * 204;

    public enum Kind {
        /** 标准 MPEG-TS:188 字节包,同步字节在包首 */
        TS_188,
        /** M2TS:192 字节包,同步字节通常在包头 +4(前面 4 字节是时间戳) */
        TS_192,
        /** 204 字节包:188 字节 TS + 尾部 16 字节 FEC */
        TS_204,
        /** MP4/fMP4:开头是 ftyp/styp/moof 盒子 */
        MP4,
        /** 都不是(未解密 / 伪装 / 错误响应 / 只有 fMP4 片段缺 init 段等) */
        UNKNOWN
    }

    public final Kind kind;
    /** 识别出的包长(0=未识别) */
    public final int packetSize;
    /** 同步字节在包内的偏移(188/204 为 0;M2TS 为 4) */
    public final int syncOffset;
    /** 校验窗口内同步字节命中比例(0~1) */
    public final float syncRatio;
    /** 前 16 字节 hex(定位伪装/未解密内容) */
    public final String headHex;
    /** MP4 时记下盒子名(ftyp/styp/moof) */
    public final String boxTag;
    /**
     * 认不出来的内容到底是什么(可识别的常见"假分片"):PNG/JPEG/GIF/WebP 图片、HTML 错误页、JSON 等。
     * 空串=没有识别出来。用于把失败原因写成用户看得懂的话(而不是只给一串 hex)。
     */
    public final String contentHint;

    private TsProbe(Kind kind, int packetSize, int syncOffset, float syncRatio, String headHex, String boxTag) {
        this(kind, packetSize, syncOffset, syncRatio, headHex, boxTag, "");
    }

    private TsProbe(Kind kind, int packetSize, int syncOffset, float syncRatio, String headHex, String boxTag,
                    String contentHint) {
        this.kind = kind;
        this.packetSize = packetSize;
        this.syncOffset = syncOffset;
        this.syncRatio = syncRatio;
        this.headHex = headHex;
        this.boxTag = boxTag == null ? "" : boxTag;
        this.contentHint = contentHint == null ? "" : contentHint;
    }

    public boolean isTs() {
        return kind == Kind.TS_188 || kind == Kind.TS_192 || kind == Kind.TS_204;
    }

    /** 192/204 字节包必须转成 188 才能交给 MediaExtractor(见类注释) */
    public boolean needsRepack() {
        return kind == Kind.TS_192 || kind == Kind.TS_204;
    }

    /**
     * 探测一段字节流的结构。同步字节判定标准:按候选包长在窗口内逐包校验,
     * 命中率 ≥ (总包数-1)/总包数 且至少能验 3 个包 —— 随机数据蒙混过关的概率在 1e-5 量级以下。
     */
    public static TsProbe of(byte[] head) {
        String hex = hex(head, 16);
        if (head == null || head.length < 8) {
            return new TsProbe(Kind.UNKNOWN, 0, 0, 0f, hex, "");
        }
        String tag = ascii(head, 4, 4);
        if (head.length >= 12
                && ("ftyp".equals(tag) || "styp".equals(tag) || "moof".equals(tag))) {
            return new TsProbe(Kind.MP4, 0, 0, 1f, hex, tag);
        }
        int[] strides = {188, 192, 204};
        for (int stride : strides) {
            // 188/204:同步字节在包首;192(M2TS):通常在第 5 字节,个别变体在包首(多余 4 字节在尾部)
            int[] offsets = stride == 192 ? new int[]{4, 0} : new int[]{0};
            for (int off : offsets) {
                int total = 0;
                int hit = 0;
                for (int p = 0; off + (p + 1) * stride <= head.length; p++) {
                    total++;
                    if (head[off + p * stride] == SYNC_BYTE) {
                        hit++;
                    }
                }
                if (total >= 3 && hit >= total - 1) {
                    Kind k = stride == 188 ? Kind.TS_188 : (stride == 192 ? Kind.TS_192 : Kind.TS_204);
                    return new TsProbe(k, stride, off, (float) hit / (float) total, hex, "");
                }
            }
        }
        return new TsProbe(Kind.UNKNOWN, 0, 0, 0f, hex, "", hintOf(head));
    }

    /**
     * 识别常见"假分片"的内容类型:图片(PNG/JPEG/GIF/WebP/BMP)、HTML 错误页、JSON。
     * 这些是"源把图片当分片返回/防盗链错误响应"的典型特征,识别出来才能把失败原因说清楚。
     */
    private static String hintOf(byte[] head) {
        if (head == null || head.length < 4) return "";
        int b0 = head[0] & 0xFF, b1 = head[1] & 0xFF, b2 = head[2] & 0xFF, b3 = head[3] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "PNG 图片";
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "JPEG 图片";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "GIF 图片";
        if (b0 == 0x42 && b1 == 0x4D) return "BMP 图片";
        if (head.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && "WEBP".equals(ascii(head, 8, 4))) {
            return "WebP 图片";
        }
        String text = ascii(head, 0, Math.min(16, head.length)).trim().toLowerCase(java.util.Locale.ROOT);
        if (text.startsWith("<html") || text.startsWith("<!doctype") || text.startsWith("<?xml")) return "HTML/XML 页面";
        if (text.startsWith("{") || text.startsWith("[")) return "JSON 文本";
        return "";
    }

    /** 每个源包单元里,要保留的 188 字节从哪开始(192 且同步在 +4 时,前面 4 字节时间戳要丢掉) */
    public int keepFrom() {
        return packetSize == 192 && syncOffset == 4 ? 4 : 0;
    }

    /**
     * 把一段"按 {@link #packetSize} 对齐"的字节流重打包成标准 188 字节包:
     * 192 为每单元丢前 4 字节(同步在包首时改为丢尾部 4 字节),204 为每单元丢尾部 16 字节。
     *
     * @param len 有效字节数;不足一个包的尾巴由调用方先裁掉
     * @return 重打包后的新数组;探测结果不需要重打包/入参非法时返回 null
     */
    public static byte[] repackUnit(byte[] buf, int len, TsProbe probe) {
        if (buf == null || probe == null || !probe.needsRepack()) {
            return null;
        }
        int ps = probe.packetSize;
        int units = len / ps;
        if (units <= 0 || len > buf.length) {
            return null;
        }
        int from = probe.keepFrom();
        byte[] out = new byte[units * TS_PACKET_SIZE];
        for (int u = 0; u < units; u++) {
            System.arraycopy(buf, u * ps + from, out, u * TS_PACKET_SIZE, TS_PACKET_SIZE);
        }
        return out;
    }

    /** 一行中文结论(给日志用) */
    public String describe() {
        switch (kind) {
            case TS_188:
                return "标准 MPEG-TS(188 字节包,同步命中 " + pct() + ")";
            case TS_192:
                return "M2TS(192 字节包,同步在 +" + syncOffset + ",命中 " + pct()
                        + ")—— MediaExtractor 只认 188,已重打包";
            case TS_204:
                return "204 字节包(188+FEC16,命中 " + pct() + ")—— MediaExtractor 只认 188,已重打包";
            case MP4:
                boolean fragment = "moof".equals(boxTag) || "styp".equals(boxTag);
                return "MP4/" + boxTag + (fragment ? "(疑似 fMP4 片段,缺 init 段)" : " 盒子");
            default:
                return "既不是 TS 也不是 MP4(前 16 字节 " + headHex + ";"
                        + (contentHint.isEmpty() ? "" : "识别为" + contentHint + ",")
                        + "可能未解密/伪装/错误响应)";
        }
    }

    private String pct() {
        return Math.round(syncRatio * 100f) + "%";
    }

    private static String hex(byte[] buf, int n) {
        if (buf == null || buf.length == 0) {
            return "-";
        }
        int len = Math.min(n, buf.length);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit((buf[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(buf[i] & 0xF, 16));
        }
        return sb.toString();
    }

    private static String ascii(byte[] buf, int off, int len) {
        if (buf == null || off + len > buf.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (buf[off + i] & 0xFF));
        }
        return sb.toString();
    }
}
