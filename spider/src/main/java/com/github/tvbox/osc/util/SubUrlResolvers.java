package com.github.tvbox.osc.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订阅地址特殊处理注册中心(统一特殊处理接口)
 * <p>
 * 当前**未注册任何特殊源**,所有订阅一律按正常规则拉取,本类不产生任何影响。
 * 将来若确有需要特殊动作的订阅地址(如请求前地址变换),在 static 块中 register 对应实现即可,
 * 命中才生效,普通订阅地址行为完全不变。
 */
public class SubUrlResolvers {

    private static final List<SubUrlResolver> RESOLVERS = new CopyOnWriteArrayList<>();

    static {
        // 在此注册特殊订阅源实现,例如:
        // register(new XxxSubUrlResolver());
    }

    private SubUrlResolvers() {
    }

    public static void register(SubUrlResolver resolver) {
        if (resolver != null && !RESOLVERS.contains(resolver)) {
            RESOLVERS.add(resolver);
        }
    }

    /** 返回第一个匹配该地址的处理器,没有则返回 null */
    public static SubUrlResolver find(String url) {
        if (url == null) return null;
        for (SubUrlResolver resolver : RESOLVERS) {
            try {
                if (resolver.match(url)) return resolver;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
