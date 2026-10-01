package com.github.catvod.crawler;

import com.github.tvbox.osc.spiderapi.SpiderFaultApi;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 源插件故障登记处（:spider 内部实现;app 只经 {@code SpiderFaultProviders} 契约读）。
 * <p>
 * 记的是"这个源确定不可用了,原因是 X"——只登记<b>插件层面的确定性故障</b>
 * （插件类不存在 / jar 加载失败 / JS 编译初始化失败）,网络超时、站点临时 5xx 这类
 * 一次性失败不登记（否则正常的"这个片没搜到"也会被说成源坏了）。
 * <p>
 * 生命周期:创建成功即清除;jar 重载 / 换订阅（{@link JarLoader#reset()}）整体清除。
 * 冷却期内的源由各 Loader 重新登记一次原因,保证 UI 在整个冷却期都能给出说法。
 */
public final class SpiderFaults implements SpiderFaultApi {

    private static final SpiderFaults INSTANCE = new SpiderFaults();

    public static SpiderFaults get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, String> reasons = new ConcurrentHashMap<>();

    public void markUnavailable(String sourceKey, String reason) {
        if (sourceKey == null || sourceKey.isEmpty() || reason == null || reason.isEmpty()) return;
        reasons.put(sourceKey, reason);
    }

    public void markAvailable(String sourceKey) {
        if (sourceKey == null || sourceKey.isEmpty()) return;
        reasons.remove(sourceKey);
    }

    /** 订阅/插件整体换了:旧故障记录一并作废 */
    public void clear() {
        reasons.clear();
    }

    @Override
    public String unavailableReason(String sourceKey) {
        return sourceKey == null ? null : reasons.get(sourceKey);
    }

    // ------------------------------------------------------------------
    // 统一说法（各 Loader 共用,保证 UI/日志措辞一致）
    // ------------------------------------------------------------------

    /** 插件里根本没有这个类:订阅站点列表与爬虫包不匹配（线上实例:csp_XPathGuard） */
    public static String missingClassReason(String clsKey) {
        return "插件缺少 " + clsKey + " 类（订阅的爬虫包与该源不匹配，请更新订阅或换源）";
    }

    /** 类在、但实例化/初始化失败:源多半已失效 */
    public static String initFailedReason(String clsKey) {
        return "源初始化失败（" + clsKey + "，源可能已失效）";
    }

    /**
     * 插件会让整个 App 闪退(其 native 在 null 类加载器上调 loadClass,触发 CheckJNI abort,进程内接不住),
     * 已被隔离停用。实测见 {@link JarLoader} 的插件崩溃隔离注释。
     */
    public static String pluginCrashReason(String clsKey) {
        return "该源插件会导致 App 闪退，已停用（" + clsKey + "，请更新订阅或换源）";
    }

    /** JS 源:编译/初始化失败（api 地址失效、JS 语法不兼容等） */
    public static String jsLoadFailedReason() {
        return "JS 源加载失败（源地址或脚本可能已失效）";
    }

    /** JS 源:运行库/依赖类缺失（js api 的 jar 与当前订阅不匹配） */
    public static String jsMissingClassReason(String api) {
        return "JS 运行库缺少依赖类（" + api + "，请更新订阅或换源）";
    }

    /** 该异常是否"类不存在"这一类（含 ART 把缺类包成 NoClassDefFoundError 的情况） */
    public static boolean isMissingClass(Throwable th) {
        Throwable t = th;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof ClassNotFoundException || t instanceof NoClassDefFoundError) return true;
            Throwable cause = t.getCause();
            t = (cause == t) ? null : cause;
        }
        return false;
    }
}
