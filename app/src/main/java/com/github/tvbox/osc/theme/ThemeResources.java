package com.github.tvbox.osc.theme;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import com.github.tvbox.osc.bean.theme.ThemePalette;

/**
 * 运行时换肤的<b>代码取色通道</b>:包一层 {@link Resources},把"随主题走"的颜色按当前调色板返回。
 * <p>
 * <b>内置亮/暗主题与自定义主题都走它</b>(2026-10-02 起,原先只服务自定义主题):
 * 编译期颜色资源带 {@code -night} 限定符,系统/OEM 把进程 {@code uiMode} 翻成夜间时,
 * 没有这一层的取色(以及用 Application 上下文建的窗口)就会跟着变深,而走运行时调色板的布局却不变
 * —— 现象是"只有弹窗/气泡/通知这类新窗口变深"。见 {@link ThemeRuntime#runtimePalette()}。
 *
 * <p>为什么需要它:布局里的 {@code @color/xxx} 由系统在 native 侧解析(改不了),但<b>代码里</b>
 * 那些 {@code ContextCompat.getColor(ctx, R.color.text_foreground)} / {@code getColorStateList(...)} /
 * {@code getDrawable(...)} 都会走到 {@link Resources} 的 Java 方法上 —— 在这里替换即可让
 * AppSwitch / AppTitleBar / 占位图 / 悬浮进度圈 / 各适配器 / 气泡 / 通知 等"自己读色"的组件一起跟着主题走。
 *
 * <p>只覆盖三类方法,其它一律委托父类(父类持有的 AssetManager/资源表就是安装包里那份,
 * 也就是"编译期默认值"),所以<b>未覆盖到的地方最差也只是保持内置配色,不会变成乱色</b>。
 *
 * <p>配置同步:{@link #syncFrom(Resources)} 在每次取用时对齐 Configuration/DisplayMetrics,
 * 避免本实例持有一份过期配置(字体缩放、屏幕尺寸、日夜切换后尺寸算错);是否顺带把明暗位
 * 归一到我们自己的类型见 {@link #ThemeResources(Resources, boolean)}。
 */
public class ThemeResources extends Resources {

    private final Configuration syncedConfiguration;
    private final DisplayMetrics syncedMetrics = new DisplayMetrics();
    /** 是否把本包装内的 {@code uiMode} 归一到"我们自己的明暗类型"(见 {@link #normalizeConfig}) */
    private final boolean normalizeNight;

    public ThemeResources(Resources base) {
        this(base, true);
    }

    /**
     * @param normalizeNight 是否把包装内的 {@code uiMode} 归一到当前主题的明暗。
     *                       <p><b>Activity / 弹窗一侧用 true</b>:系统(OEM)把进程翻成夜间时,
     *                       本包装内连 native 解析的 {@code @color} 与 {@code ?attr} 都按我们的主题走。
     *                       <p><b>Application 一侧必须用 false</b>:"跟随系统"是读
     *                       {@code appContext.getResources().getConfiguration().uiMode} 来判系统明暗的
     *                       (见 {@code ThemeStore.systemNight()} / {@code BaseActivity.systemNightNow()}),
     *                       把应用级那份也归一了,就等于用我们自己的类型去回答"系统现在是亮还是暗" ——
     *                       跟随系统会自锁,再也翻不动。应用级那份只需要覆盖取色方法(见 {@link #getColor})。
     */
    public ThemeResources(Resources base, boolean normalizeNight) {
        super(base.getAssets(), base.getDisplayMetrics(),
                normalizeNight ? normalizeConfig(base.getConfiguration()) : base.getConfiguration());
        this.normalizeNight = normalizeNight;
        syncedConfiguration = new Configuration(
                normalizeNight ? normalizeConfig(base.getConfiguration()) : base.getConfiguration());
        syncedMetrics.setTo(base.getDisplayMetrics());
    }

    /**
     * 把配置的明暗位换成"我们自己选的类型"。
     *
     * <p>做这件事的意义:包装内所有资源解析(含 {@code Resources} 原生解析的 {@code @color}
     * 与主题属性)都以这份配置为准,于是系统/OEM 把进程 {@code uiMode} 翻成夜晚也改不动我们界面
     * —— 只改本实例的配置,不动 {@code base}(系统给的那份),所以"跟随系统"仍读得到真实系统状态。
     */
    private static Configuration normalizeConfig(Configuration src) {
        Configuration cfg = src == null ? new Configuration() : new Configuration(src);
        int night = ThemeRuntime.type().isDark()
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return cfg;
    }

    /** 与最新配置对齐(变了才调 updateConfiguration,避免每次取色都做一次 native 调用) */
    public void syncFrom(Resources latest) {
        // Resources 会原地更新配置；同一个实例也可能已从竖屏切到横屏。
        if (latest == null || latest == this) return;
        try {
            Configuration now = normalizeNight
                    ? normalizeConfig(latest.getConfiguration()) : latest.getConfiguration();
            DisplayMetrics m = latest.getDisplayMetrics();
            if (now == null || m == null) return;
            // 比较上次基础资源的内容快照，既识别原地更新，也保留 AutoSize 对包装资源的密度适配。
            if (!syncedConfiguration.equals(now) || !syncedMetrics.equals(m)) {
                @SuppressWarnings("deprecation")
                Resources self = this;
                self.updateConfiguration(now, m);
                syncedConfiguration.setTo(now);
                syncedMetrics.setTo(m);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 当前生效的调色板:{@link ThemeRuntime#runtimePalette()}(内置亮/暗主题也非空)。
     * 只有"快照还没装配"时才为 null,那时代码取色退回父类的编译期默认值。
     */
    private ThemePalette palette() {
        return ThemeRuntime.runtimePalette();
    }

    private Integer overrideColor(int id) {
        ThemePalette p = palette();
        if (p == null) return null;
        String name = ThemeColorAliases.paletteNameOf(id);
        return name == null ? null : p.get(name);
    }

    // ── 颜色 ──

    @Override
    @SuppressWarnings("deprecation")
    public int getColor(int id) throws NotFoundException {
        Integer c = overrideColor(id);
        return c != null ? c : super.getColor(id);
    }

    @Override
    public int getColor(int id, Theme theme) throws NotFoundException {
        Integer c = overrideColor(id);
        return c != null ? c : super.getColor(id, theme);
    }

    // ── 颜色选择器(按钮文字/chip/底栏选中这类带状态的色) ──

    @Override
    @SuppressWarnings("deprecation")
    public ColorStateList getColorStateList(int id) throws NotFoundException {
        ColorStateList override = ThemeDrawables.rebuildColorStateList(id, this);
        return override != null ? override : super.getColorStateList(id);
    }

    @Override
    public ColorStateList getColorStateList(int id, Theme theme) throws NotFoundException {
        ColorStateList override = ThemeDrawables.rebuildColorStateList(id, this);
        return override != null ? override : super.getColorStateList(id, theme);
    }

    // ── drawable(只重着色"单色矢量图标"这一小类,见 ThemeIcons) ──

    @Override
    @SuppressWarnings("deprecation")
    public Drawable getDrawable(int id) throws NotFoundException {
        return tintIfThemed(super.getDrawable(id), id);
    }

    @Override
    public Drawable getDrawable(int id, Theme theme) throws NotFoundException {
        return tintIfThemed(super.getDrawable(id, theme), id);
    }

    /**
     * 主题驱动的单色图标:按注册表换上当前主题色。
     * <p>图标本身在 drawable 里写死了 {@code fillColor="@color/text_xxx"},那份颜色编译期就定死了;
     * 这里用 tint 覆盖(矢量图标是单色字形,tint 等价于换填充色)。
     * <p>{@code mutate()} 是必须的:否则改的是全局共享的 constant state,会串到别的页面。
     */
    private Drawable tintIfThemed(Drawable d, int id) {
        if (d == null) return null;
        // 旧系统的 EditText 没有公开句柄/光标 setter；框架从 Context 的 Resources
        // 加载 Material 句柄与光标时，在这里把默认强调色替换为当前文字主色。
        if ((id >>> 24) == 0x01) {
            try {
                String entry = getResourceEntryName(id);
                if ((entry.startsWith("text_select_handle_") && entry.endsWith("_material"))
                        || "text_cursor_material".equals(entry)) {
                    ThemePalette current = palette();
                    if (current != null) {
                        Drawable.ConstantState state = d.getConstantState();
                        Drawable copy = (state == null ? d : state.newDrawable(this)).mutate();
                        androidx.core.graphics.drawable.DrawableCompat.setTint(copy, current.get("text_main"));
                        return copy;
                    }
                }
            } catch (Throwable ignored) {
                // 非预期的系统 drawable 保持原样。
            }
        }
        String name = ThemeDrawables.iconTintKey(id, this);
        if (name == null) return d;
        ThemePalette p = palette();
        if (p == null) return d;
        int color = p.get(name);
        try {
            Drawable copy = d.mutate();
            // 用 androidx 兼容层给这一层着色(ImageView 的 imageTint 走的是 View 的公开 API,
            // 而这里是 drawable 自己着色,必须走 DrawableCompat 才在各 API 上都成立)。
            //
            // **注意容器**:StateListDrawable 这类容器换状态时画的是**子 drawable**,
            // 父层 tint 不会自动下传;而本运行时的 android.jar 把 DrawableContainer.getChildren() /
            // Drawable.getTintList() 这些隐藏 API 剥掉了(真机反射均为 NoSuchMethodException),
            // 公开面上拿不到子项、也就没法替子项着色。
            // 所以"选择器型图标"(如方形勾选框 button_checkbox_square)不能指望在这里被涂色:
            // 它的颜色必须像其它 drawable 一样**写进 XML 的颜色引用里**、由主题重建通道换掉。
            androidx.core.graphics.drawable.DrawableCompat.setTint(copy, color);
            return copy;
        } catch (Throwable th) {
            return d;
        }
    }
}
