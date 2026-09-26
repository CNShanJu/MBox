package com.github.tvbox.osc.ui.kit;

import androidx.annotation.Nullable;

/**
 * 页面为"下拉刷新/到底了"套件注入的环境依赖(避免 kit 直连全局配置/业务单例):
 *
 * <ul>
 *   <li>加载动画资产路径与尺寸 —— 由 app 侧用 {@code LoadingAnim}(读 SystemConfig)组装后传入;</li>
 *   <li>toast 提示、业务日志 —— 由 app 侧接 AppBubble/LogStore,kit 只发消息不持有单例。</li>
 * </ul>
 *
 * 实现建议放 app 层(如静态工厂),kit 内只保留该契约。
 */
public interface PullRefreshEnv {

    /** 全局加载动画 assets 文件路径(如 loading/anim_loading/anim_loading.json);null=不显示动画 */
    @Nullable
    String loadingAnimFilePath();

    /** 下拉刷新指示器的显示尺寸(dp) */
    int refreshIndicatorSizeDp();

    /** 短提示(toast);可为空实现:不弹 */
    void toast(String msg);

    /** 业务日志(下拉刷新生命周期);可为空实现:不记录 */
    void log(String msg);

    /** 空实现兜底:未注入环境时全部无副作用(仅失去动画/提示,不崩溃) */
    PullRefreshEnv NONE = new PullRefreshEnv() {
        @Nullable
        @Override
        public String loadingAnimFilePath() {
            return null;
        }

        @Override
        public int refreshIndicatorSizeDp() {
            return 40;
        }

        @Override
        public void toast(String msg) {
        }

        @Override
        public void log(String msg) {
        }
    };
}
