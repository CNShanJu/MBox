package com.github.catvod.crawler;

/**
 * JS 源运行时桥接（:spider 侧对 app 暴露的最小面）。
 * <p>
 * 页面/工具不再直连 {@link JsLoader}（AGENTS §二 红线），只经 spiderapi 的
 * {@code SourceLoaderApi#stopAllSourceTasks/resetSources}；:spider 内部的实现类即可回调本类，
 * 把"取消在跑任务/清空源实例"两个动作留在模块内。
 */
public final class JsRuntimeBridge {

    private JsRuntimeBridge() {
    }

    /** 取消所有 JS 源当前任务（原 JsLoader.stopAll） */
    public static void stopAllSourceTasks() {
        JsLoader.stopAll();
    }

    /** 销毁并清空所有 JS 源实例（原 JsLoader.load） */
    public static void resetSources() {
        JsLoader.load();
    }
}
