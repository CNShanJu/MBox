package com.github.tvbox.osc.spiderapi;

/**
 * 订阅爬虫 jar（{@code files/csp.jar}）的缓存取舍规则。
 * <p>
 * 纯函数、无 Android 依赖,故可直接 JVM 单测（见 app/src/test 的 JarCachePolicyTest）。
 * 背景:原 {@code ApiConfig.loadJar} 写的是 {@code cache.exists() && (useCache || md5相符)},
 * 而 {@code useCache} 正是"带缓存配置重启"（改设置/切首页源后的重载）这条最常走的路 ——
 * 于是它会把<b>md5 已经不符</b>的旧 jar 当成可用缓存加载:换过订阅/换过仓库后仍跑上一份 jar,
 * 站点声明的类不在里面 → {@code 源初始化失败} → 整源搜索/详情空白。
 * <p>
 * 现规则:配置里带了 md5 就必须校验通过;只有配置本身没给 md5 时,才允许用"信任本地缓存"
 * （useCache）这条兜底路径。
 */
public final class JarCachePolicy {

    private JarCachePolicy() {
    }

    /**
     * 本地缓存的 jar 能否直接使用（不走网络）。
     *
     * @param cacheExists   {@code files/csp.jar} 是否存在
     * @param configuredMd5 订阅里声明的 md5（可为空 = 订阅没给凭据）
     * @param cachedMd5     本地文件的 md5（cacheExists=false 时调用方可传空）
     * @param useCache      是否处于"信任本地缓存"的快速启动路径
     */
    public static boolean cacheUsable(boolean cacheExists, String configuredMd5, String cachedMd5, boolean useCache) {
        if (!cacheExists) return false;
        String want = trim(configuredMd5);
        if (want.isEmpty()) return useCache;
        return want.equalsIgnoreCase(trim(cachedMd5));
    }

    /**
     * 订阅的爬虫 jar 地址是否换了（换了 = 本地这份 jar 属于别的订阅/别的仓库）。
     * <p>
     * 换过订阅时本地 jar 不能当"下载失败的回退"用:它里面的爬虫类与当前站点列表毫无关系,
     * 用了只会得到一批看不懂的失败;此时宁可让 jar 源整体等下一次下载成功。
     * 地址没变、只是内容变了（md5 变了）则不算换:那份旧 jar 仍属当前订阅,可作回退。
     */
    public static boolean jarUrlChanged(String lastJarUrl, String currentJarUrl) {
        String now = trim(currentJarUrl);
        String last = trim(lastJarUrl);
        if (now.isEmpty() || last.isEmpty()) return false; // 无记录（首启/旧版本升级）不判定为"换了"
        return !last.equals(now);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
