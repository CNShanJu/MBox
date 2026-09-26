package com.github.tvbox.osc.download.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * 下载失败分类单测(纯 JVM):"分片在源侧永久失效(HTTP 404/410)"与"地址过期/网络抖动"必须分清 ——
 * 前者重试、换线路、重新解析地址拿到的都是同一个 404(实测 8 片死片被白请求 144 次),后者才值得换线路重试。
 */
public class DownloadErrorsTest {

    @Test
    public void segmentGone_isSingleSegmentFailure() {
        DownloadErrors.SegmentGoneException e = new DownloadErrors.SegmentGoneException(237,
                "分片下载失败(HTTP 404,该分片在" + DownloadErrors.SEGMENT_GONE_TEXT + ",重试与换线路均无法补齐)");
        assertTrue(DownloadErrors.isSegmentGone(e));
        assertTrue("序号要带出来,上层才能记住这片是死片", e.getSegmentIndex() == 237);
        assertTrue("状态码要留在文案里,用户能看懂是源的片没了", e.getMessage().contains("HTTP 404"));
    }

    @Test
    public void allGoneFailure_isPermanentlyGone() {
        IOException all = new IOException(DownloadErrors.ALL_SEGMENTS_GONE
                + "(HTTP 404):共 12 片,超过可容忍范围(总计 940 片),重试与换线路均无法补齐,建议换源重下");
        assertTrue(DownloadErrors.isPermanentlyGone(all));
        assertFalse(DownloadErrors.isSegmentGone(all));
        assertFalse(DownloadErrors.isPermanentlyGone(null));
    }

    @Test
    public void mixedFailure_mustNotLookPermanentlyGone() {
        // 混合缺失(还有可补救的临时失败)的整集失败信息里会拼上"最后错误: …源侧已失效…",
        // 但绝不能被判成"缺片全是永久失效":否则调度器会跳过换线路与重试,把本来还能救的任务直接判死。
        // 这正是两个文案必须不同字面量的原因(见 DownloadErrors 常量注释)。
        IOException mixed = new IOException("碎片校验不一致,自动补下3轮后仍缺失(缺 12 片,如第237片),最后错误: "
                + new DownloadErrors.SegmentGoneException(237, "分片下载失败(HTTP 404,该分片在"
                + DownloadErrors.SEGMENT_GONE_TEXT + ",重试与换线路均无法补齐)").getMessage());
        assertFalse("混合失败必须仍可重试/换线路", DownloadErrors.isPermanentlyGone(mixed));
        assertNotEquals(DownloadErrors.ALL_SEGMENTS_GONE, DownloadErrors.SEGMENT_GONE_TEXT);
    }

    @Test
    public void otherFailures_stayRetryable() {
        // 403/超时/5xx 等仍按"可补救"处理,不被永久失效分类吞掉(该换线路换线路、该重试重试)
        assertFalse(DownloadErrors.isPermanentlyGone(new IOException("分片下载失败(HTTP 403)")));
        assertFalse(DownloadErrors.isPermanentlyGone(new IOException("分片下载失败(HTTP 500)")));
        assertFalse(DownloadErrors.isPermanentlyGone(new java.net.SocketTimeoutException("timeout")));
        assertFalse(DownloadErrors.isSegmentGone(new IOException("分片下载失败(HTTP 403)")));
    }

    @Test
    public void routeSuspect_marksLineWideFailures() {
        // 连续多片失败=本线路整体不可用(调度器据此换线路);整集缺片全是永久失效同样算线路没救
        IOException consecutive = new IOException("连续 8 片下载失败(" + DownloadErrors.ROUTE_SUSPECT_TEXT
                + "): 最后错误 分片下载失败(HTTP 403)");
        assertTrue(DownloadErrors.isRouteSuspect(consecutive));
        assertTrue(DownloadErrors.isRouteSuspect(new IOException(DownloadErrors.ALL_SEGMENTS_GONE + "(HTTP 404):共 12 片")));
        // 单片 404(偶尔一个死片,靠补片/放宽档吸收)与普通超时都算不上"线路整体失效",不该换线路
        assertFalse(DownloadErrors.isRouteSuspect(new IOException(
                "分片下载失败(HTTP 404,该分片在" + DownloadErrors.SEGMENT_GONE_TEXT + ",重试与换线路均无法补齐)")));
        assertFalse(DownloadErrors.isRouteSuspect(new java.net.SocketTimeoutException("timeout")));
        assertFalse(DownloadErrors.isRouteSuspect(null));
        // 执行器拼这条信息时必须用常量,否则分类静默失效
        assertTrue(consecutive.getMessage().contains(DownloadErrors.ROUTE_SUSPECT_TEXT));
    }
}
