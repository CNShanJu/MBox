package com.github.tvbox.osc.util;

/**
 * "缺片能否算完成"的判定(纯计算,可 JVM 单测)。
 *
 * <p>背景:源侧个别分片<strong>永久失效</strong>是常事(CDN 上那个文件就是没了,重试/换线路都拿不到)。
 * 实测过一集 879 片里只有第 486 片 404,补片 3 轮 + 自动重新解析地址 3 次 × 任务重试 2 次全部无效 ——
 * 为几秒钟画面把整集判死,对用户是净损失(他宁可要一个缺几秒、能看的文件)。
 *
 * <p>但"缺片也算完成"必须收紧,且必须让用户知道:
 * <ul>
 *   <li>缺失数量极少({@link #MAX_MISSING_SEGMENTS} 片以内)<b>且</b>占比极小({@link #MAX_MISSING_PERMILLE}‰ 以内);</li>
 *   <li>超过阈值一律判失败(缺太多就是文件不可用,不能假装成功);</li>
 *   <li>调用方必须把"缺了几片"写进任务信息/日志/通知,不允许静默完成。</li>
 * </ul>
 */
public final class MissingSegmentPolicy {

    /** 允许缺片完成的绝对上限(片) */
    public static final int MAX_MISSING_SEGMENTS = 3;
    /** 允许缺片完成的占比上限(千分比;0.3% ≈ 44 分钟剧集里的 8 秒) */
    public static final int MAX_MISSING_PERMILLE = 3;

    /**
     * 缺片<strong>全部</strong>已确认源侧永久失效(HTTP 404/410)时的绝对上限(片)。
     * <p>为什么单独放宽:这类缺片不是"网络抖动,再试就好",而是 CDN 上那个文件就是没了 ——
     * 重试/换线路/重新解析地址全都是同一个 404(实测 8 片死片被反复请求 144 次)。
     * 此时严格档(3 片)会让一集 900+ 片的长剧因为 8 秒画面直接判死,对用户是净损失。
     */
    public static final int MAX_MISSING_SEGMENTS_GONE = 8;
    /** 全部永久失效时的占比上限(千分比;1% ≈ 45 分钟剧集里的 27 秒) */
    public static final int MAX_MISSING_PERMILLE_GONE = 10;

    private MissingSegmentPolicy() {
    }

    /**
     * 缺 {@code missing} 片、总计 {@code total} 片时,能否按"缺片完成"处理(严格档,见
     * {@link #allowGapCompletion(int, int, boolean)})。
     * <p>老调用点无法区分"缺片是否已确认永久失效",一律按严格档处理,避免放宽被误用。
     */
    public static boolean allowGapCompletion(int missing, int total) {
        return allowGapCompletion(missing, total, false);
    }

    /**
     * 缺 {@code missing} 片、总计 {@code total} 片时,能否按"缺片完成"处理。
     * 数量与占比两条都要满足;总数非法(≤0)或没有缺片时返回 false(分别交给失败/正常完成路径)。
     *
     * @param allPermanentlyGone 缺的片是否<strong>全部</strong>已确认源侧永久失效(HTTP 404/410)。
     *                           为 true 时走放宽档(绝对上限 {@link #MAX_MISSING_SEGMENTS_GONE} 片与总数的 1%
     *                           取大,占比上限 {@link #MAX_MISSING_PERMILLE_GONE}‰);为 false 时走严格档。
     *                           只要还混着"可补救的临时失败",就必须按严格档 —— 那说明还能救,不该提前放弃。
     */
    public static boolean allowGapCompletion(int missing, int total, boolean allPermanentlyGone) {
        if (missing <= 0 || total <= 0) return false;
        if (!allPermanentlyGone) {
            return missing <= MAX_MISSING_SEGMENTS
                    && (long) missing * 1000L <= (long) total * MAX_MISSING_PERMILLE;
        }
        int countCap = Math.max(MAX_MISSING_SEGMENTS_GONE, total / 100);
        return missing <= countCap
                && (long) missing * 1000L <= (long) total * MAX_MISSING_PERMILLE_GONE;
    }
}
