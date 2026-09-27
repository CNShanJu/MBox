package com.github.tvbox.osc.theme;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

/**
 * 换肤用的 Context 包装:把 {@link Resources} 换成 {@link ThemeResources}(代码取色通道)。
 *
 * <p>装在哪儿:{@code BaseActivity.attachBaseContext}。这样 Activity 自己的资源、它的 Theme、
 * 以及<b>由它 inflate 出来的所有视图</b>都走包装后的 Resources 取色
 * (fragment 与 adapter 惯用 {@code LayoutInflater.from(activity)},拿到的还是同一个 inflater)。
 *
 * <p>不介入时就<b>原样返回</b>传入的 Context:没用自定义主题的机器上,这条链路等于不存在。
 */
public final class ThemeContextWrapper extends ContextWrapper {

    private ThemeResources resources;

    private ThemeContextWrapper(Context base) {
        super(base);
    }

    /** 需要时包一层(幂等);不需要时原样返回 */
    public static Context wrap(Context base) {
        if (base == null || !ThemeRuntime.active()) return base;
        if (base instanceof ThemeContextWrapper) return base;
        try {
            return new ThemeContextWrapper(base);
        } catch (Throwable th) {
            return base;
        }
    }

    @Override
    public Resources getResources() {
        Resources base = super.getResources();
        if (base == null) return null;
        try {
            if (resources == null) {
                resources = new ThemeResources(base);
            } else {
                // 配置可能已经变过(字号/屏幕/日夜),对齐一次再交出去
                resources.syncFrom(base);
            }
            return resources;
        } catch (Throwable th) {
            // 包装失败就退回原资源:宁可没有换肤,也不能让页面拿不到资源
            return base;
        }
    }
}
