package com.github.tvbox.osc.ui;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.ui.kit.PullRefreshEnv;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LoadingAnim;

/**
 * 下拉刷新/到底了套件(app 组合根)环境工厂:
 *
 * kit 的 {@link PullRefreshEnv} 只声明契约,不直连全局配置/单例;
 * 本类在 app 层用 LoadingAnim(读 SystemConfig/动画配置)、AppBubble(toast)
 * 组装默认实现(动画路径、尺寸、速度),供各列表页 attach 时注入。
 */
public final class RefreshUiEnvFactory {

    private RefreshUiEnvFactory() {
    }

    /** 组装默认下拉刷新环境(全局加载动画 + 系统 toast) */
    @NonNull
    public static PullRefreshEnv create() {
        return new PullRefreshEnv() {
            @Override
            public String loadingAnimFilePath() {
                return LoadingAnim.getAnimFileName(); // 与全局加载态同一动画文件
            }

            @Override
            public int refreshIndicatorSizeDp() {
                return LoadingAnim.getRefreshSizeDp(); // config.json size_refresh
            }

            @Override
            public float loadingAnimSpeed() {
                return LoadingAnim.getPlaybackSpeed(); // config.json speed
            }

            @Override
            public void toast(String msg) {
                AppBubble.toast(msg);
            }
        };
    }
}
