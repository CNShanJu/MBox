package com.github.tvbox.osc.download.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.github.tvbox.osc.bean.DownloadTask;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载进度指纹单测(JVM,不碰 Android)。
 * <p>
 * 这块决定"窗口到了要不要把整张任务表序列化成 JSON 重写磁盘"(见 DownloadManager.persistProgressIfChanged)。
 * 两种情况都难在真机上定位:
 * <ul>
 *   <li>指纹漏了某个进度字段 → 那个字段永远不落盘(杀进程后进度回退,表现为"续传重下已经下过的部分");</li>
 *   <li>指纹把"无变化"算成变化 → 又回到每 450ms 重写整表(发热/耗电的原始问题)。</li>
 * </ul>
 * 所以把"哪些变化算变化、哪些不算"固定住。
 */
public class DownloadProgressSignatureTest {

    private static DownloadTask task(String id, int state, long downloaded, long total) {
        DownloadTask t = new DownloadTask();
        t.id = id;
        t.state = state;
        t.downloadedBytes = downloaded;
        t.totalBytes = total;
        return t;
    }

    private static List<DownloadTask> list(DownloadTask... items) {
        List<DownloadTask> l = new ArrayList<>();
        for (DownloadTask t : items) l.add(t);
        return l;
    }

    @Test
    public void unchangedProgressKeepsSameSignature() {
        DownloadTask a = task("a", DownloadTask.STATE_DOWNLOADING, 1000, 5000);
        DownloadTask b = task("b", DownloadTask.STATE_WAITING, 0, 800);
        long first = DownloadManager.progressSignature(list(a, b));
        // 新建同样内容的对象(真实场景是"快照重建"),指纹必须一致 —— 这才叫"没有值得落盘的变化"
        long second = DownloadManager.progressSignature(
                list(task("a", DownloadTask.STATE_DOWNLOADING, 1000, 5000),
                        task("b", DownloadTask.STATE_WAITING, 0, 800)));
        assertEquals(first, second);
    }

    @Test
    public void downloadedBytesChangeIsDetected() {
        long before = DownloadManager.progressSignature(list(task("a", DownloadTask.STATE_DOWNLOADING, 1000, 5000)));
        long after = DownloadManager.progressSignature(list(task("a", DownloadTask.STATE_DOWNLOADING, 1001, 5000)));
        assertNotEquals(before, after);
    }

    @Test
    public void segmentProgressChangeIsDetected() {
        DownloadTask a = task("h", DownloadTask.STATE_DOWNLOADING, 0, 0);
        a.totalSegments = 10;
        a.doneSegments = 3;
        a.segmentBytes = 100;
        long before = DownloadManager.progressSignature(list(a));

        a.doneSegments = 4; // HLS 每完成一个分片
        long afterSegments = DownloadManager.progressSignature(list(a));
        assertNotEquals("完成分片数变了必须落盘", before, afterSegments);

        a.segmentBytes = 200; // 段内断点续传字节
        long afterBytes = DownloadManager.progressSignature(list(a));
        assertNotEquals("段内已下载字节变了必须落盘", afterSegments, afterBytes);
    }

    @Test
    public void stateAndMessageChangesAreDetected() {
        DownloadTask a = task("a", DownloadTask.STATE_DOWNLOADING, 10, 100);
        long running = DownloadManager.progressSignature(list(a));
        a.state = DownloadTask.STATE_PAUSED;
        long paused = DownloadManager.progressSignature(list(a));
        assertNotEquals("状态变了必须落盘", running, paused);

        a.message = "合并中 30%";
        long merging = DownloadManager.progressSignature(list(a));
        assertNotEquals("合并进度文案变了必须落盘", paused, merging);
    }

    @Test
    public void listStructureChangesAreDetected() {
        DownloadTask a = task("a", DownloadTask.STATE_DOWNLOADING, 10, 100);
        long one = DownloadManager.progressSignature(list(a));
        long two = DownloadManager.progressSignature(list(a, task("b", DownloadTask.STATE_WAITING, 0, 100)));
        assertNotEquals(one, two);
    }

    @Test
    public void nullEntriesAreSkippedInsteadOfCrashing() {
        // 任务表里出现 null(坏文件/并发窗口)不能让指纹计算抛异常,否则整条落盘路径都断了
        List<DownloadTask> withNull = list(task("a", DownloadTask.STATE_DOWNLOADING, 10, 100), null);
        long h = DownloadManager.progressSignature(withNull);
        assertEquals(h, DownloadManager.progressSignature(list(task("a", DownloadTask.STATE_DOWNLOADING, 10, 100))));
    }
}
