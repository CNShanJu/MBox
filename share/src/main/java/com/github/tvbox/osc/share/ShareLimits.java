package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 平台限额(导出前本地预检,别等传到一半被服务端拒)。
 *
 * <p>值来自各平台的公开接口约束,<b>会变</b>:storage.to 匿名上传是
 * 每日 20 次 / 单文件 25 GB / 有效期 3 天(CLI 侧 1~7 天),这些数字写死在服务端策略里,
 * 我们只能取"文档当前值"做预检,真超了以后端返回为准(见 {@link ShareErrorCode#RATE_LIMITED}、
 * {@link ShareErrorCode#TOO_LARGE})。因此 {@link #note()} 要能说明"以服务端为准",
 * 界面别把这些数字当承诺展示成"保证可用"。
 */
public final class ShareLimits {

    /** 不知道上限时的通用值(不预检,交给服务端判) */
    public static final ShareLimits UNKNOWN =
            new ShareLimits(-1L, -1, -1, -1, 0, "未声明限额,以服务端为准");

    private final long maxFileBytes;
    private final int maxUploadsPerDay;
    private final int minExpiryDays;
    private final int maxExpiryDays;
    private final int defaultExpiryDays;
    private final String note;

    public ShareLimits(long maxFileBytes, int maxUploadsPerDay, int minExpiryDays,
                       int maxExpiryDays, int defaultExpiryDays, @Nullable String note) {
        this.maxFileBytes = maxFileBytes;
        this.maxUploadsPerDay = maxUploadsPerDay;
        this.minExpiryDays = minExpiryDays;
        this.maxExpiryDays = maxExpiryDays;
        this.defaultExpiryDays = defaultExpiryDays;
        this.note = note == null ? "" : note;
    }

    /** 单文件上限(字节);&lt;=0 表示未声明 */
    public long maxFileBytes() {
        return maxFileBytes;
    }

    /** 每日导出次数上限;&lt;=0 表示未声明 */
    public int maxUploadsPerDay() {
        return maxUploadsPerDay;
    }

    /** 有效期下限(天);&lt;=0 表示未声明 */
    public int minExpiryDays() {
        return minExpiryDays;
    }

    /** 有效期上限(天);&lt;=0 表示未声明 */
    public int maxExpiryDays() {
        return maxExpiryDays;
    }

    /** 不给有效期时平台用的默认天数;0 表示由服务端决定 */
    public int defaultExpiryDays() {
        return defaultExpiryDays;
    }

    /** 补充说明(给界面/日志,如"以服务端为准") */
    @NonNull
    public String note() {
        return note;
    }

    /** 该大小是否在限额内(未声明上限时一律放行) */
    public boolean accepts(long sizeBytes) {
        return maxFileBytes <= 0 || sizeBytes <= maxFileBytes;
    }

    /** 把用户/业务给的天数钳到本平台合法区间;<=0 表示"用平台默认" */
    public int clampExpiryDays(int days) {
        if (days <= 0) return defaultExpiryDays;
        if (minExpiryDays > 0 && days < minExpiryDays) return minExpiryDays;
        if (maxExpiryDays > 0 && days > maxExpiryDays) return maxExpiryDays;
        return days;
    }

    @NonNull
    @Override
    public String toString() {
        return "ShareLimits{maxFileBytes=" + maxFileBytes + ", maxUploadsPerDay=" + maxUploadsPerDay
                + ", expiry=" + minExpiryDays + "~" + maxExpiryDays + "d, default="
                + defaultExpiryDays + "d}";
    }
}
