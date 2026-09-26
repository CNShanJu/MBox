package com.github.tvbox.osc.update;

import com.github.tvbox.osc.config.PrefsDataStore;

/**
 * 更新源配置门面:记录当前更新实现类型与 GitHub 仓库参数。
 * <p>
 * 数据自持于 {@link PrefsDataStore}(现代化偏好存储),供 {@link UpdaterProvider}
 * 按配置切换到不同更新实现;未写入时使用仓库默认值。
 */
public final class UpdaterConfig {

    /** 当前默认实现:GitHub Releases */
    public static final String SOURCE_GITHUB = "github";

    /**
     * 项目仓库默认坐标(仓库 {@code CNShanJu/MBox},原名 {@code CNShanJu/TVBoxOS-Mobile})。
     * <p>
     * 更名前发布的老安装包内置坐标仍是旧仓库名;GitHub 对更名后的仓库保留 301 重定向,而本 App 的
     * 检查更新与 APK 下载都走 {@code network().general()}(followRedirects=true),因此老包仍能查到并下载
     * 新仓库的 Release——发版时无需为老包额外做兼容。
     */
    public static final String DEFAULT_GITHUB_OWNER = "CNShanJu";
    public static final String DEFAULT_GITHUB_REPO = "MBox";

    /** 默认 GitHub 下载加速前缀(形如 https://gh-proxy.org/);空串=不使用代理,仅直连 */
    public static final String DEFAULT_GITHUB_DOWNLOAD_PROXY = "https://gh-proxy.org/";

    private static final String KEY_SOURCE = "update_source";
    private static final String KEY_GITHUB_OWNER = "update_github_owner";
    private static final String KEY_GITHUB_REPO = "update_github_repo";
    private static final String KEY_GITHUB_DOWNLOAD_PROXY = "update_github_download_proxy";

    private UpdaterConfig() {
    }

    /** 更新实现标识,默认 {@link #SOURCE_GITHUB} */
    public static String getSource() {
        String s = PrefsDataStore.getString(KEY_SOURCE, SOURCE_GITHUB);
        return s == null || s.trim().isEmpty() ? SOURCE_GITHUB : s.trim();
    }

    public static void setSource(String source) {
        PrefsDataStore.put(KEY_SOURCE, source == null || source.trim().isEmpty() ? SOURCE_GITHUB : source.trim());
    }

    /** GitHub 仓库 owner,默认 {@value #DEFAULT_GITHUB_OWNER} */
    public static String getGithubOwner() {
        String v = PrefsDataStore.getString(KEY_GITHUB_OWNER, DEFAULT_GITHUB_OWNER);
        return v == null || v.trim().isEmpty() ? DEFAULT_GITHUB_OWNER : v.trim();
    }

    public static void setGithubOwner(String owner) {
        PrefsDataStore.put(KEY_GITHUB_OWNER, owner == null || owner.trim().isEmpty() ? DEFAULT_GITHUB_OWNER : owner.trim());
    }

    /** GitHub 仓库名,默认 {@value #DEFAULT_GITHUB_REPO} */
    public static String getGithubRepo() {
        String v = PrefsDataStore.getString(KEY_GITHUB_REPO, DEFAULT_GITHUB_REPO);
        return v == null || v.trim().isEmpty() ? DEFAULT_GITHUB_REPO : v.trim();
    }

    public static void setGithubRepo(String repo) {
        PrefsDataStore.put(KEY_GITHUB_REPO, repo == null || repo.trim().isEmpty() ? DEFAULT_GITHUB_REPO : repo.trim());
    }

    /** GitHub 下载加速前缀(默认 {@value #DEFAULT_GITHUB_DOWNLOAD_PROXY});空串/未配置=只用直连 */
    public static String getGithubDownloadProxy() {
        String v = PrefsDataStore.getString(KEY_GITHUB_DOWNLOAD_PROXY, DEFAULT_GITHUB_DOWNLOAD_PROXY);
        return v == null ? "" : v.trim();
    }

    public static void setGithubDownloadProxy(String proxyPrefix) {
        PrefsDataStore.put(KEY_GITHUB_DOWNLOAD_PROXY,
                proxyPrefix == null ? "" : proxyPrefix.trim());
    }
}
