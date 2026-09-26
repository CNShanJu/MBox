package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;

import java.io.File;

/**
 * HLS(m3u8)分段下载任务对象：分片下载/校验/合并。
 * 4.6 任务对象化：doRun 委托框架侧执行器 {@link DownloadExecutor#downloadHls}，
 * 行为与拆分前一致；后续 4.7 重封装阶段将把执行器内部逻辑收敛进子类。
 */
public class M3u8DownloadTask extends BaseDownloadTask {

    private final DownloadExecutor executor;

    public M3u8DownloadTask(DownloadTask task, TaskListener listener, DownloadExecutor executor) {
        super(task, listener);
        this.executor = executor;
    }

    @Override
    protected void doRun() throws Exception {
        executor.downloadHls(task);
    }

    @Override
    protected boolean isResumableFromDisk() {
        // HLS 断点续传现场:分片目录存在且有分片信息文件(记录了已完成分片)
        if (task.tmpDir == null || task.tmpDir.isEmpty()) return false;
        File dir = new File(task.tmpDir);
        return dir.exists() && new File(dir, DownloadManager.SEGMENTS_INFO).exists();
    }
}
