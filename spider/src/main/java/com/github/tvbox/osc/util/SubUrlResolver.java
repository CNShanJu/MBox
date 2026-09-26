package com.github.tvbox.osc.util;

import java.util.Collections;
import java.util.List;

/**
 * 订阅地址特殊处理接口(统一特殊处理入口)
 * <p>
 * 仅当某个订阅地址**确有特殊动作**需要(例如请求前必须变换地址、附加特定处理)时,
 * 才实现本接口并注册到 {@link SubUrlResolvers};当前没有任何实现注册,所有订阅均按正常规则拉取。
 * 将来遇到新的特殊订阅源,新增一个实现并注册即可,无需改动拉取逻辑,普通地址行为不变。
 */
public interface SubUrlResolver {

    /** 是否处理该订阅地址 */
    boolean match(String url);

    /** 请求前对地址做改造;不需要改造返回原样 */
    String transform(String url);

    /** 空备用地址 */
    default List<String> noFallbacks() {
        return Collections.emptyList();
    }
}
