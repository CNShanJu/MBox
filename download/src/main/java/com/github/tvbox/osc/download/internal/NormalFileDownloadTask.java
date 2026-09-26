package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;

import java.io.File;

/**
 * 直链(mp4/mkv/ts 等单文件)下载任务对象：断点续传 + 限速 + 协作式中断。
 * 4.6 任务对象化：doRun 委托框架侧执行器 {@link DownloadExecutor#downloadDirect}，
 * 行为与拆分前一致；后续 4.7 重封装阶段将把执行器内部逻辑收敛进子类。
 */
public class NormalFileDownloadTask extends BaseDownloadTask {

    private final DownloadExecutor executor;

    public NormalFileDownloadTask(DownloadTask task, TaskListener listener, DownloadExecutor executor) {
        super(task, listener);
        this.executor = executor;
    }

    @Override
    protected void doRun() throws Exception {
        executor.downloadDirect(task);
    }

    @Override
    protected boolean isResumableFromDisk() {
        // 直链断点续传现场:.part 文件已存在且已写字节>0
        return task.downloadedBytes > 0
                && task.partPath != null
                && new File(task.partPath).exists()
                && new File(task.partPath).length() > 0;
    }
}
