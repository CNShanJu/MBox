package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;

/**
 * 分享/导入导出统一回调。
 *
 * <p>为什么用回调而不是 {@code LiveData}/Flow:本模块是<b>纯 Java 库,不依赖 Android 生命周期
 * 组件也不依赖 Kotlin 协程栈</b>(:share 未接 Kotlin 插件,与 :download/:core-storage 一致)。
 * 契约保持"平台无关 + 框架无关",由 :app 侧决定怎么接进 ViewModel/LiveData。
 *
 * <p>线程约定(实现者必须遵守,调用方可以依赖):
 * <ul>
 *   <li><b>所有回调都在主线程</b>(Looper.getMainLooper())。界面不用自己 post 回主线程,
 *       也不用担心在回调里碰 View 崩掉;</li>
 *   <li>每次操作<b>最终恰好一次</b> {@code onSuccess} 或 {@code onError}(取消走
 *       {@code onError(CANCELLED)}),{@code onProgress} 期间 0~N 次;</li>
 *   <li>终态之后不再有任何回调(取消后迟到的网络结果必须被丢弃,不能"取消完又报成功")。</li>
 * </ul>
 *
 * @param <T> 成功载荷:导出为 {@link ShareLink},导入为 {@link SharePackage}
 */
public interface ShareCallback<T> {

    /** 进度(可能多次;实现可只发关键阶段) */
    void onProgress(@NonNull ShareProgress progress);

    /** 成功(终态) */
    void onSuccess(@NonNull T result);

    /** 失败/取消(终态) */
    void onError(@NonNull ShareException error);
}
