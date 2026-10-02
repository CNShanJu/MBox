package com.github.tvbox.osc.theme;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

/**
 * 换肤用的 Context 包装:把 {@link Resources} 换成 {@link ThemeResources}(代码取色通道)。
 *
 * <p>装在哪儿:{@code BaseActivity.attachBaseContext}(Activity 侧)、{@code App.attachBaseContext}
 * (应用上下文侧)。这样 Activity 自己的资源、它的 Theme、<b>由它 inflate 出来的所有视图</b>,
 * 以及用 <b>Application 上下文</b>建的窗口(气泡/Toast/通知/应用上下文 inflate 的弹窗)
 * 都走包装后的 Resources 取色(fragment 与 adapter 惯用 {@code LayoutInflater.from(activity)},拿到的还是同一个 inflater)。
 *
 * <p><b>内置亮/暗主题也要包</b>(2026-10-02,魅族 Flyme 强制深色):包装的门槛是"运行时调色板是否装配好"
 * ({@link ThemeRuntime#runtimePalette()}),不再是"是否自定义主题"。只要还按"仅自定义主题"放行,
 * 那条通道就会按 {@code -night} 编译期资源取色 —— 表现成"只有弹窗/气泡/通知这类新窗口变深,页面主体还是浅色"。
 *
 * <p>不介入时<b>原样返回</b>传入的 Context:快照还没装配(极早期调用)时这条链路等于不存在。
 */
public final class ThemeContextWrapper extends ContextWrapper {

    private ThemeResources resources;
    private final boolean normalizeNight;

    private ThemeContextWrapper(Context base, boolean normalizeNight) {
        super(base);
        this.normalizeNight = normalizeNight;
    }

    /** 需要时包一层(幂等);不需要时原样返回。明暗位按我们的主题归一(Activity/弹窗一侧用) */
    public static Context wrap(Context base) {
        return wrap(base, true);
    }

    /**
     * @param normalizeNight 是否把包装内的明暗位归一到当前主题类型;应用上下文传 false
     *                       (理由见 {@link ThemeResources#ThemeResources(Resources, boolean)})
     */
    public static Context wrap(Context base, boolean normalizeNight) {
        if (base == null || !ready()) return base;
        if (base instanceof ThemeContextWrapper) return base;
        try {
            return new ThemeContextWrapper(base, normalizeNight);
        } catch (Throwable th) {
            return base;
        }
    }

    /** 供色通道的门槛:运行时调色板装配好就介入(内置与自定义主题都算) */
    private static boolean ready() {
        return ThemeRuntime.runtimePalette() != null;
    }

    /**
     * 把一个 {@link Resources} 包成换肤版(幂等;快照未装配或包装失败时原样返回)。
     *
     * <p><b>为什么还需要这一条</b>:AppCompat 会在 {@code attachBaseContext2} 里给 Activity 下
     * {@code applyOverrideConfiguration(夜间模式等)},一旦有了 overrideConfiguration,
     * {@code ContextThemeWrapper.getResourcesInternal()} 就走
     * {@code createConfigurationContext(...)} **自己新建一份 Resources** —— 那份不是本类的
     * {@link ThemeResources},于是<b>代码里的取色全部绕过换肤层</b>:标题栏文字(代码里取的
     * {@code R.color.text_main})、列表项颜色、{@code getDrawable} 出来的矢量图标……
     * 统统停在内置配色,而布局里行内写的颜色(走 inflater 注入)却是好的 ——
     * 正是用户看到的"有的变了、有的没变"。
     *
     * <p>所以 Activity 侧要在 {@code getResources()} 上再兜一层(见 {@code BaseActivity}),
     * 把 AppCompat 新造的那份也包进来。
     */
    public static Resources wrapResources(Resources base) {
        return wrapResources(base, true);
    }

    /** {@link #wrapResources(Resources)};应用上下文传 {@code normalizeNight=false} */
    public static Resources wrapResources(Resources base, boolean normalizeNight) {
        if (base == null || !ready()) return base;
        if (base instanceof ThemeResources) return base;
        try {
            return new ThemeResources(base, normalizeNight);
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
                resources = new ThemeResources(base, normalizeNight);
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
