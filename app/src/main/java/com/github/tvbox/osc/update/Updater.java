package com.github.tvbox.osc.update;

import android.content.Context;

/**
 * 更新动作契约:抽象"检查 / 下载 / 安装"整个更新流程。
 * <p>
 * UI 只依赖本接口触发更新,具体实现(如 {@code GithubReleaseUpdater})通过
 * {@link UpdaterProvider} 按 {@link UpdaterConfig#getSource()} 配置切换,
 * 便于后续接入其他更新源(自定义服务端 JSON、应用市场等)而不改动 UI。
 */
public interface Updater {

    /** 实现标识(用于日志/调试,如 "github") */
    String name();

    /** 异步检查是否有新版本;所有回调均在主线程,newVersion 为 null 表示已是最新 */
    void checkUpdate(Context context, Callback callback);

    /**
     * 下载新版本 APK 并触发安装。
     *
     * @param info 由 {@link #checkUpdate} 返回的新版本信息
     */
    void downloadAndInstall(Context context, UpdateInfo info, Callback callback);

    /** 更新流程回调(主线程) */
    interface Callback {

        /** 开始检查 */
        void onCheckStart();

        /**
         * 检查完成。
         *
         * @param newVersion 有新版本时非空;为 null 表示当前已是最新
         */
        void onCheckResult(UpdateInfo newVersion);

        /** 下载进度(百分比语义由调用方换算;total<=0 表示未知) */
        void onDownloadProgress(long current, long total);

        /** 下载完成并已启动系统安装器 */
        void onDownloadReady(UpdateInfo info);

        /** 任一环节失败 */
        void onError(String message);
    }
}
