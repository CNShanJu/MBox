package com.github.tvbox.osc.download.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 存储看门狗"全下载链路一套实现"的源码级绊线(纯 JVM;真机行为由用户人工验证)。
 *
 * <p>为什么用源码断言:看门狗的价值全在"<b>覆盖到每一条会写盘的路径</b>"与"<b>只有一套实现</b>"——
 * 少一处调用就是一个能把手机塞满的口子,而这类遗漏单看某次编译/单测都发现不了(本机也跑不了
 * StatFs/MediaMuxer 的集成场景)。这里把"哪些路径必须调用它""暂停用哪个状态""阈值只有一个来源"
 * 钉成断言,改动时一旦漏接就会红。
 *
 * <p>这些断言只在方法/字段级别(存在某段调用、某段文案),不锁具体写法;重构到别的类时按新位置改断言即可。
 */
public class StorageWatchdogContractTest {

    private static File source(String relative) {
        // 单测工作目录 = :app 模块目录(Gradle 默认),故实现在 ../<module>/... 下
        String[] candidates = { "../" + relative, relative };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        return new File(candidates[0]);
    }

    private static String read(String relative) throws Exception {
        File f = source(relative);
        assertTrue("找不到被测源文件(单测工作目录变了?): " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String executor() throws Exception {
        return read("download/src/main/java/com/github/tvbox/osc/download/internal/DirectDownloader.java")
                + read("download/src/main/java/com/github/tvbox/osc/download/internal/HlsDownloader.java")
                + read("download/src/main/java/com/github/tvbox/osc/download/internal/DownloadExecutor.java")
                + read("download/src/main/java/com/github/tvbox/osc/download/internal/MediaRemuxer.java");
    }

    private static String scheduler() throws Exception {
        return read("download/src/main/java/com/github/tvbox/osc/download/internal/DownloadScheduler.java");
    }

    private static String watchdog() throws Exception {
        return read("download/src/main/java/com/github/tvbox/osc/download/internal/StorageWatchdog.java");
    }

    private static int countOf(String src, String needle) {
        int n = 0;
        int i = src.indexOf(needle);
        while (i >= 0) {
            n++;
            i = src.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /** 取出某个方法的方法体(从起点后的第一个 '{' 起按花括号配对截取),避免把相邻方法一起算进来 */
    private static String methodBody(String src, String startMarker) {
        int i = src.indexOf(startMarker);
        assertTrue("找不到起点标记: " + startMarker, i >= 0);
        int open = src.indexOf('{', i);
        assertTrue("起点标记后没有方法体: " + startMarker, open > 0);
        int depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, k + 1);
            }
        }
        throw new AssertionError("方法体没有正常闭合: " + startMarker);
    }

    // ------------------------------------------------------------------
    // 一套实现
    // ------------------------------------------------------------------

    @Test
    public void thereIsExactlyOneWatchdogImplementation() throws Exception {
        File dir = source("download/src/main/java/com/github/tvbox/osc/download/internal");
        assertTrue("找不到下载模块内部实现目录: " + dir.getAbsolutePath(), dir.isDirectory());
        int impls = 0;
        File[] files = dir.listFiles();
        assertTrue(files != null);
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (src.contains("class StorageWatchdog")) impls++;
        }
        assertEquals("存储看门狗只能有一套实现(所有下载路径共用它)", 1, impls);
    }

    @Test
    public void everyPathThatWritesToDiskCallsTheSameWatchdog() throws Exception {
        String src = executor();
        // 直链写循环 / 分片读循环 / 每片循环 / 合并循环:四条会持续写盘的路径都要自检
        int calls = countOf(src, "dm.watchdog.checkWhileDownloading()");
        assertTrue("会写盘的下载路径都必须调用看门狗自检(当前只有 " + calls + " 处,应 >= 3): "
                + "直链写循环、分片读循环、分片列表循环、合并循环", calls >= 3);
    }

    @Test
    public void schedulerGateUsesTheSameWatchdog() throws Exception {
        String src = scheduler();
        assertTrue("调度闸门必须用看门狗问'现在能不能开新任务'(只停在跑的任务会留下"
                + "'刚入队的立刻又把空间吃回去'的口子)", src.contains("dm.watchdog.isLow()"));
        assertTrue("任务开跑时必须让巡检线程起来", src.contains("dm.watchdog.ensureMonitor()"));
        assertTrue("等待任务要给可读文案,不能只显示'等待中'",
                src.contains("DownloadFacade.MSG_WAIT_STORAGE"));
    }

    @Test
    public void watchdogIsTheOnlyEnforcementPoint() throws Exception {
        String src = watchdog();
        assertTrue("暂停动作只能由看门狗统一发起", src.contains("dm.scheduler.pauseAllStorage("));
        assertTrue("阈值判定只能来自 StorageGuardPolicy(别再写一个 1GB 字面量)",
                src.contains("StorageGuardPolicy.isLow("));
        assertTrue("空间测量只能问中控层 StorageSpace(别再自己 new StatFs,见 StorageSpaceContractTest)",
                src.contains("StorageSpace.freeBytes("));
        assertTrue("巡检线程必须有退出条件(不该常驻)", src.contains("if (!hasActiveDownload())"));
    }

    @Test
    public void storagePauseUsesManualPauseStateNotSchedulerPauseState() throws Exception {
        // 关键语义:必须置 STATE_PAUSED(用户暂停),不能置 STATE_SYSTEM_PAUSED ——
        // 后者是"调度让位",调度循环看到有空位会立刻把它拉起来接着下,空间不足的暂停会被瞬间撤销
        String body = methodBody(scheduler(), "boolean pauseAllStorage(String message)");
        assertTrue("空间不足的暂停必须把任务置为 STATE_PAUSED: " + body,
                body.contains("t.state = DownloadTask.STATE_PAUSED"));
        assertFalse("空间不足的暂停不能置 STATE_SYSTEM_PAUSED(调度看到空位会立刻把它拉起来接着下): " + body,
                body.contains("t.state = DownloadTask.STATE_SYSTEM_PAUSED"));
        assertTrue("暂停要写清原因(用户才知道去清空间)", body.contains("t.message = message"));
    }

    @Test
    public void remuxStopFailureIsSalvagedAfterVerification() throws Exception {
        String src = executor();
        assertTrue("stop() 失败后必须先校验产物再决定是否回退 .ts", src.contains("mp4Usable(outTmp"));
        assertTrue("stop() 的异常要单独接住(而不是让整个重封装失败)", src.contains("stopFailed = true"));
        assertTrue("重封装前要算空间(空间不够是 -1007 的常见根因)", src.contains("ensureRemuxSpace("));
        assertTrue("空间不够时要先释放本任务的分片目录", src.contains("deleteSegmentsDir(t)"));
    }

    @Test
    public void hlsSizeComesFromSamplingNotOnlyFromBandwidth() throws Exception {
        String src = executor();
        assertTrue("m3u8 大小必须走抽样探测+推算", src.contains("HlsSizeEstimator.sampleIndices(")
                && src.contains("HlsSizeEstimator.estimateTotalBytes("));
        assertTrue("抽样请求要有硬超时(不能把任务启动卡在预检上)",
                src.contains("SIZE_SAMPLE_CALL_TIMEOUT_SEC"));
        assertTrue("抽样要有整体时间预算", src.contains("SIZE_SAMPLE_BUDGET_MS"));
    }
}
