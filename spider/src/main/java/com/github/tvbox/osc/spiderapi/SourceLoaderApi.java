package com.github.tvbox.osc.spiderapi;

import android.app.Activity;

/**
 * 源/订阅加载器契约（改进.txt 收口：订阅配置加载与 jar 源加载流程由首页启动触发，
 * 具体实现留在 :spider ApiConfig，页面只依赖本接口触发并按回调推进状态机）。
 */
public interface SourceLoaderApi {

    /**
     * 加载订阅配置（含 jar 源字符串解析）；useCache 命中本地缓存时直接成功回调。
     * 未配置订阅/失败时回调 error（msg="-1" 表示"未配置订阅"，由页面决定提示或跳过）。
     */
    void loadConfig(boolean useCache, Callback callback, Activity activity);

    /** 加载 jar 源（spider 配置串）；成功/失败走回调推进页面状态 */
    void loadJar(boolean useCache, String spider, Callback callback);

    /** 当前订阅携带的 spider 配置串（未配置/加载前可能为空串） */
    String getSpider();

    /**
     * 取消所有 JS 源当前正在执行的任务（原 JsLoader.stopAll）：会话结束/暂停/新一轮开始时调用，
     * 让在跑的源请求尽快自弃；已创建的源实例保留，下次取源无需重建。
     */
    void stopAllSourceTasks();

    /**
     * 销毁并清空所有 JS 源实例（原 JsLoader.load）：需要让源彻底重建时调用
     * （如快速搜索整轮重来），下次取源会重新编译 JS 模块。
     */
    void resetSources();

    /** 加载结果回调（与原 ApiConfig.LoadConfigCallback 三方法对齐） */
    interface Callback {
        void success();

        void retry();

        void error(String msg);
    }
}
