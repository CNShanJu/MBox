package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;

/**
 * 任务上报协议（任务对象 ↔ 框架解耦）：
 * 子类只上报事件，状态/队列/并发由框架（Scheduler/Recorder）统一管理。
 * 注意: 日志不走本接口——任务对象内部经 DownloadLog(LogStore 注册制) 直接写日志。
 */
public interface TaskListener {

    /** 进度变化（框架负责 800ms 合并持久化 + 广播） */
    void onProgress(DownloadTask t);

    /** 状态跳变（框架落库 + 广播 + 驱动调度） */
    void onState(DownloadTask t, int state, String message);

    /** 请求清理碎片（框架仲裁顺序: 落盘→档案→清理 铁律） */
    void onCleanupRequest(DownloadTask t);
}
