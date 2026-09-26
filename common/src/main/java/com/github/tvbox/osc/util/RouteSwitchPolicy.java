package com.github.tvbox.osc.util;

/**
 * 换线路的"止损"判定(纯计算,可 JVM 单测):换线路=整集重下,已下的分片全废,
 * 所以只在损失很小的时候才自动换;已经下了不少就不自动换,保留下载进度让用户自己决定。
 *
 * <p>为什么不能无损续传:两条线路是<b>两份不同的播放列表</b>(切分点/编码参数/总片数都可能不同),
 * 同一序号的分片覆盖的时间区间不一样,把 B 线路的片填进 A 线路的空洞,拼出来是时间轴错乱的文件 ——
 * 只能整集重下。能保住进度的只有"同一条线路、只是地址续期"这种情况(靠 {@code segments.sig} 指纹判定)。
 *
 * <p>阈值口径:
 * <ul>
 *   <li>HLS:已完成片数 ≤ {@link #MAX_LOSS_SEGMENTS} 片 <b>且</b> ≤ {@link #MAX_LOSS_PERCENT}% 总片数;</li>
 *   <li>直链:已下字节 ≤ {@link #MAX_LOSS_BYTES} <b>且</b> ≤ {@link #MAX_LOSS_PERCENT}% 总大小
 *       (总大小未知时只看绝对上限)。</li>
 * </ul>
 * 按"片数/字节"两条一起卡:四分之一的比例对长剧可能仍是几百 MB,绝对上限兜住;
 * 只卡比例则短视频会太松(20 片里下 5 片就换线路,重下也快,反而无所谓)。
 */
public final class RouteSwitchPolicy {

    /** 允许自动换线路的最大损失比例(%) */
    public static final int MAX_LOSS_PERCENT = 25;
    /** 允许自动换线路的最大已完成片数(约几分钟视频量级) */
    public static final int MAX_LOSS_SEGMENTS = 200;
    /** 允许自动换线路的最大已下字节(直链用;总量未知时的唯一依据) */
    public static final long MAX_LOSS_BYTES = 200L * 1024 * 1024;

    private RouteSwitchPolicy() {
    }

    /** HLS:按已完成分片数判断"损失是否可以接受"(总片数未知按不可接受处理,不猜) */
    public static boolean lossAcceptable(int doneSegments, int totalSegments) {
        if (totalSegments <= 0) return false;
        if (doneSegments <= 0) return true;
        if (doneSegments > MAX_LOSS_SEGMENTS) return false;
        return (long) doneSegments * 100L <= (long) totalSegments * MAX_LOSS_PERCENT;
    }

    /**
     * 直链:按已下字节判断。
     *
     * @param totalBytes 服务器给出的总大小;≤0 表示未知(此时只看绝对上限)
     */
    public static boolean lossAcceptableBytes(long doneBytes, long totalBytes) {
        if (doneBytes <= 0) return true;
        if (doneBytes > MAX_LOSS_BYTES) return false;
        if (totalBytes <= 0) return true; // 总量未知:绝对上限内允许
        return doneBytes * 100L <= totalBytes * MAX_LOSS_PERCENT;
    }
}
