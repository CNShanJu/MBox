package com.github.tvbox.osc.spiderapi;

/**
 * 源插件故障契约的注入点（与 SourceConfigProviders / SpiderDetailProviders 同一套写法）。
 * <p>
 * 默认安全降级:恒返回 null（等价"没有故障信息"）,未注入时调用方只会拿不到额外提示,不会崩。
 * 组合根在 {@code di/AppCompositionRoot} 里注入 :spider 的实现。
 */
public final class SpiderFaultProviders {

    private SpiderFaultProviders() {
    }

    private static volatile SpiderFaultApi impl = sourceKey -> null;

    public static void set(SpiderFaultApi api) {
        impl = api == null ? sourceKey -> null : api;
    }

    public static SpiderFaultApi get() {
        return impl;
    }

    /** 便捷读法:该源不可用的原因（可为 null） */
    public static String unavailableReason(String sourceKey) {
        try {
            return impl.unavailableReason(sourceKey);
        } catch (Throwable th) {
            return null;
        }
    }
}
