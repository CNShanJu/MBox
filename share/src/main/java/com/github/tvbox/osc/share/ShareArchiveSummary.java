package com.github.tvbox.osc.share;

/**
 * 归档内「数据条目」(不含 {@code manifest.json})的合并摘要。
 *
 * <p>为什么不含清单:清单本身要记录这份摘要,若把清单也算进摘要就形成"先有摘要才能写清单、
 * 清单又影响摘要"的死结。数据条目(设置/库/主题等)在打包前就已确定,可以先算摘要再写清单,
 * 导入侧按同一算法重算即可校验传输完整性。
 */
public final class ShareArchiveSummary {

    /** 数据条目解压后字节总和 */
    public final long sizeBytes;
    /** 数据条目合并 SHA-256(小写十六进制) */
    public final String sha256Hex;

    public ShareArchiveSummary(long sizeBytes, String sha256Hex) {
        this.sizeBytes = sizeBytes;
        this.sha256Hex = sha256Hex == null ? "" : sha256Hex;
    }
}
