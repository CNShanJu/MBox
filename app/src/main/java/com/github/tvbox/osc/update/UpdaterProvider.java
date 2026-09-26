package com.github.tvbox.osc.update;

/**
 * 更新实现提供者:按 {@link UpdaterConfig#getSource()} 配置返回对应 {@link Updater}。
 * <p>
 * 单例 + 按源缓存:配置不变时复用实例;新增实现只需在 {@link #create(String)} 注册分支。
 */
public final class UpdaterProvider {

    private static volatile Updater updater;
    private static volatile String currentSource;

    private UpdaterProvider() {
    }

    /** 当前配置对应的更新实现(未知配置回退到 GitHub 实现) */
    public static Updater get() {
        String source = UpdaterConfig.getSource();
        Updater u = updater;
        if (u != null && source != null && source.equals(currentSource)) {
            return u;
        }
        synchronized (UpdaterProvider.class) {
            if (updater == null || !source.equals(currentSource)) {
                updater = create(source);
                currentSource = source;
            }
            return updater;
        }
    }

    private static Updater create(String source) {
        if (UpdaterConfig.SOURCE_GITHUB.equals(source)) {
            return new com.github.tvbox.osc.update.github.GithubReleaseUpdater();
        }
        // 未知实现回退 GitHub,保证"检查更新"始终可用
        return new com.github.tvbox.osc.update.github.GithubReleaseUpdater();
    }
}
