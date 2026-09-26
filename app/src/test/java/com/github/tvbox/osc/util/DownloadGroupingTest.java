package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.ArchiveItem;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** DownloadGrouping(下载聚合分组纯逻辑)单测 */
public class DownloadGroupingTest {

    private static DownloadTask task(String vodName, String source, int state, long createTime) {
        DownloadTask t = new DownloadTask();
        t.id = vodName + "-" + source;
        t.vodName = vodName;
        t.sourceName = source;
        t.state = state;
        t.createTime = createTime;
        return t;
    }

    private static ArchiveItem done(String vodName, String source, String savePath) {
        ArchiveItem it = new ArchiveItem();
        it.vodName = vodName;
        it.sourceName = source;
        it.savePath = savePath;
        return it;
    }

    @Test
    public void group_mergesRunningAndDoneBySourceVod() {
        List<DownloadTask> tasks = new ArrayList<>();
        tasks.add(task("剧A", "源1", DownloadTask.STATE_DOWNLOADING, 100));
        tasks.add(task("剧A", "源2", DownloadTask.STATE_PAUSED, 200));
        tasks.add(task("剧B", "源1", DownloadTask.STATE_COMPLETED, 300)); // 完成走档案,不在这里
        List<ArchiveItem> archive = new ArrayList<>();
        archive.add(done("剧A", "源1", "/tmp/a1.mp4")); // 文件需存在才纳入
        archive.add(done("剧C", "源1", null));            // savePath 空:忽略

        List<DownloadGrouping.Group> groups = DownloadGrouping.group(tasks, archive);
        // 剧A/源1(任务+档案 合一组)、剧A/源2、剧B 完成态任务被跳过
        assertEquals(2, groups.size());
        DownloadGrouping.Group g = groups.get(0);
        assertEquals("剧A", g.name);
        assertEquals("源1", g.sourceName);
        assertEquals(1, g.tasks.size());
        assertTrue(g.doneItems.isEmpty()); // /tmp 不存在
    }

    @Test
    public void group_existingFileIncludedInDoneItems() throws Exception {
        File f = java.nio.file.Files.createTempFile("grp-test", ".mp4").toFile();
        List<DownloadTask> tasks = new ArrayList<>();
        List<ArchiveItem> archive = new ArrayList<>();
        archive.add(done("剧X", "源1", f.getAbsolutePath()));
        List<DownloadGrouping.Group> groups = DownloadGrouping.group(tasks, archive);
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).doneItems.size());
        f.delete();
    }

    @Test
    public void group_sortsByFirstCreateTime() {
        List<DownloadTask> tasks = new ArrayList<>();
        tasks.add(task("剧B", "源1", DownloadTask.STATE_WAITING, 300));
        tasks.add(task("剧A", "源1", DownloadTask.STATE_WAITING, 100));
        List<DownloadGrouping.Group> groups = DownloadGrouping.group(tasks, null);
        assertEquals(2, groups.size());
        assertEquals("剧A", groups.get(0).name); // 最早创建在前
        assertEquals("剧B", groups.get(1).name);
    }

    @Test
    public void aggregateNote_countsRunningAndDone() throws Exception {
        File f = java.nio.file.Files.createTempFile("note-test", ".mp4").toFile();
        List<DownloadTask> tasks = new ArrayList<>();
        tasks.add(task("剧A", "源1", DownloadTask.STATE_DOWNLOADING, 1));
        tasks.add(task("剧A", "源1", DownloadTask.STATE_COMPLETED, 2)); // 不计
        List<ArchiveItem> done = new ArrayList<>();
        done.add(done("剧A", "源1", f.getAbsolutePath()));
        done.add(done("剧A", "源1", null)); // savePath 空不计
        done.add(done("剧A", "源1", "/no/such/file.mp4")); // 文件不存在不计
        assertEquals("1 个任务 · 已完成 1 集", DownloadGrouping.aggregateNote(tasks, done));
        f.delete();
        // 仅任务 / 空档案
        assertEquals("1 个任务", DownloadGrouping.aggregateNote(tasks, null));
        assertEquals("", DownloadGrouping.aggregateNote(null, null));
    }

    @Test
    public void inGroup_and_vodNameOf_matchLegacyGroupName() {
        DownloadTask t = new DownloadTask();
        t.vodName = null;
        t.groupName = "老剧";
        t.sourceName = "源";
        assertEquals("老剧", DownloadGrouping.vodNameOf(t));
        assertTrue(DownloadGrouping.inGroup(t, "老剧", "源"));
        assertFalse(DownloadGrouping.inGroup(t, "老剧", "其它源"));
    }

    @Test
    public void tasksInGroup_filtersNonCompletedAndSorts() {
        List<DownloadTask> tasks = new ArrayList<>();
        tasks.add(task("剧A", "源1", DownloadTask.STATE_PAUSED, 200));
        tasks.add(task("剧A", "源1", DownloadTask.STATE_DOWNLOADING, 100));
        tasks.add(task("剧A", "源1", DownloadTask.STATE_COMPLETED, 50)); // 排除
        tasks.add(task("剧B", "源1", DownloadTask.STATE_DOWNLOADING, 150)); // 别的剧
        List<DownloadTask> in = DownloadGrouping.tasksInGroup(tasks, "剧A", "源1");
        assertEquals(2, in.size());
        assertEquals(100, in.get(0).createTime);
        assertNotNull(in);
    }
}
