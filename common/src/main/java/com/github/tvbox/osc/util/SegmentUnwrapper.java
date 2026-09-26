package com.github.tvbox.osc.util;

/**
 * "包裹型分片"剥壳(纯计算,可 JVM 单测)。
 *
 * <p>真实故障现场:某些源/网盘把 MPEG-TS 塞进 PNG(或 JPEG)容器里发给客户端,用来绕开 CDN 与风控 ——
 * 文件头是合法的 1×1 PNG,真正的视频数据在后面的 {@code tEXt} 块里(实测关键字 {@code TS_RAW},
 * 取出来就是一条标准 TS:188 字节包同步字节全中)。直接当分片存下来,合并产物就是"PNG 图片",
 * 重封装必然失败(而且旧判据"首 8 字节里有 0x47"会被 PNG 签名 {@code 89 50 4E 47} 里的 {@code 'G'} 骗过)。
 *
 * <p>这里只做一件事:在分片头部里找出"188 字节对齐的 TS 载荷"从哪开始 —— <b>不依赖具体容器格式</b>
 * (PNG/JPEG/未知壳都行),判据是"从某偏移起连续多个 188 字节包的同步字节都是 0x47"。
 * 找不到就返回 null(调用方按"不是包裹型"走原来的失败/校验路径)。
 *
 * <p>安全边界(避免把真图片误判成包裹分片):至少连续 {@link #MIN_VERIFY_PACKETS} 个包同步命中,
 * 且载荷必须占文件绝大部分(容器头不超过 {@link #MAX_WRAPPER_HEAD_BYTES} 字节)。
 */
public final class SegmentUnwrapper {

    /** MPEG-TS 包长 */
    public static final int PACKET_SIZE = 188;
    /** 同步字节 */
    public static final byte SYNC_BYTE = 0x47;
    /** 头部里至少连续命中多少个包才算包裹型(误判概率 ~(1/256)^7,可忽略) */
    public static final int MIN_VERIFY_PACKETS = 8;
    /** 容器头最大长度:超过这个长度就不再认为"载荷占了绝大部分" */
    public static final int MAX_WRAPPER_HEAD_BYTES = 4096;

    private SegmentUnwrapper() {
    }

    /** 剥壳结果:载荷起始偏移 + 可按整包落盘的字节数 */
    public static final class Plan {
        /** 载荷在原始分片中的起始偏移(前面的字节是容器头,丢弃) */
        public final int offset;
        /**
         * 可按整 188 包落盘的载荷字节数;
         * {@code -1} = 文件总长未知(边下边判),调用方按"读到流结束、只落整包"处理
         */
        public final int length;

        Plan(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }

        public int endOffset() {
            return length < 0 ? -1 : offset + length;
        }
    }

    /**
     * 在分片头部里定位包裹型载荷。
     *
     * @param head       分片起始若干字节(建议 ≥ {@link #MIN_VERIFY_PACKETS}×188+4096)
     * @param fileLength 分片总长度(未知传 ≤0)
     * @return 需要剥壳时返回载荷位置;不是包裹型(普通 TS/MP4/真图片)返回 null
     */
    public static Plan plan(byte[] head, long fileLength) {
        if (head == null) return null;
        int max = Math.min(head.length - MIN_VERIFY_PACKETS * PACKET_SIZE, MAX_WRAPPER_HEAD_BYTES);
        for (int off = 0; off <= max; off++) {
            if (head[off] != SYNC_BYTE) continue;
            // 头部内能验证的整包必须全部命中同步字节
            boolean ok = true;
            int verified = 0;
            for (int p = 0; off + (p + 1) * PACKET_SIZE <= head.length; p++) {
                verified++;
                if (head[off + p * PACKET_SIZE] != SYNC_BYTE) {
                    ok = false;
                    break;
                }
            }
            if (!ok || verified < MIN_VERIFY_PACKETS) continue;
            if (fileLength > 0) {
                long payload = fileLength - off;
                if (payload < (long) MIN_VERIFY_PACKETS * PACKET_SIZE) continue;
                int length = (int) (payload / PACKET_SIZE * PACKET_SIZE);
                return new Plan(off, length);
            }
            return new Plan(off, -1);
        }
        return null;
    }
}
