package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;

/**
 * 下载任务抽象基类（4.6）：框架（调度/持久化/日志/清理）与"下载算法"分离。
 * <ul>
 *   <li>子类实现 {@link #doRun()}（核心下载算法）与 {@link #isResumableFromDisk()}（磁盘现场对账）；</li>
 *   <li>生命周期由 Scheduler 在独立线程调用，{@link #start()} 阻塞执行直到完成/失败/暂停；</li>
 *   <li>协作式中断：框架置 task.state（PAUSED/SYSTEM_PAUSED/NETWORK_PAUSED/CANCELLED），
 *       下载循环在检查点退出（本类不直接改状态）；</li>
 *   <li>状态归属铁律：子类只上报事件（{@link TaskListener}），不直接改状态/队列/并发。</li>
 * </ul>
 * 新增下载类型 = 新子类 + 注册特征（{@link DownloadTaskRegistry}），框架零改动。
 */
public abstract class BaseDownloadTask {

    /** 框架任务记录（含进度/状态/路径等，由 Recorder 建档） */
    protected final DownloadTask task;

    /** 上报通道（进度/状态/清理请求） */
    protected final TaskListener listener;

    protected BaseDownloadTask(DownloadTask task, TaskListener listener) {
        this.task = task;
        this.listener = listener;
    }

    // ── 生命周期（由 Scheduler 在独立线程调用; start 阻塞执行）──

    /** 执行直到 完成/失败/暂停/取消（子类 start 内调 doRun，循环检查 task.state 协作退出） */
    public void start() throws Exception {
        if (task.state == DownloadTask.STATE_CANCELLED) return;
        doRun();
    }

    /** 暂停（协作式）：框架已置 STATE_PAUSED，下载循环在检查点退出；子类可做现场收尾 */
    public void pause() {
    }

    /** 取消（协作式）：框架已置 STATE_CANCELLED，循环立即中止；不落最终文件 */
    public void cancel() {
    }

    /** 从磁盘现场续传（不重下；由框架在恢复前调用） */
    public void resume() throws Exception {
        if (task.state == DownloadTask.STATE_CANCELLED) return;
        doRun();
    }

    // ── 查询（门面/UI 读取）──

    /** 0-100 */
    public int getProgress() {
        return task.getProgressPercent();
    }

    public long getDownloadedBytes() {
        return task.downloadedBytes;
    }

    public long getTotalBytes() {
        return task.totalBytes;
    }

    // ── 子类核心 ──

    /** 下载算法本体（框架已保证：task.state 由外部置态控制协作退出） */
    protected abstract void doRun() throws Exception;

    /** 是否有可复用磁盘现场（对账用: .part / 有效分片） */
    protected abstract boolean isResumableFromDisk();
}
