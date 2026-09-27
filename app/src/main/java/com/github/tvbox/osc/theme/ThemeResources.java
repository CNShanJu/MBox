package com.github.tvbox.osc.theme;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import com.github.tvbox.osc.bean.theme.ThemePalette;

/**
 * 运行时换肤的<b>代码取色通道</b>:包一层 {@link Resources},把"随主题走"的颜色按当前调色板返回。
 *
 * <p>为什么需要它:布局里的 {@code @color/xxx} 由系统在 native 侧解析(改不了),但<b>代码里</b>
 * 那些 {@code ContextCompat.getColor(ctx, R.color.text_foreground)} / {@code getColorStateList(...)} /
 * {@code getDrawable(...)} 都会走到 {@link Resources} 的 Java 方法上 —— 在这里替换即可让
 * AppSwitch / AppTitleBar / 占位图 / 悬浮进度圈 / 各适配器 等"自己读色"的组件一起跟着自定义主题走。
 *
 * <p>只覆盖三类方法,其它一律委托父类(父类持有的 AssetManager/资源表就是安装包里那份,
 * 也就是"编译期默认值"),所以<b>未覆盖到的地方最差也只是保持内置配色,不会变成乱色</b>。
 *
 * <p>配置同步:{@link #syncFrom(Resources)} 在每次取用时对齐 Configuration/DisplayMetrics,
 * 避免本实例持有一份过期配置(字体缩放、屏幕尺寸、日夜切换后尺寸算错)。
 */
public class ThemeResources extends Resources {

    private final Resources base;

    public ThemeResources(Resources base) {
        super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
        this.base = base;
    }

    /** 与最新配置对齐(变了才调 updateConfiguration,避免每次取色都做一次 native 调用) */
    public void syncFrom(Resources latest) {
        if (latest == null || latest == this.base) return;
        try {
            android.content.res.Configuration cur = getConfiguration();
            android.content.res.Configuration now = latest.getConfiguration();
            DisplayMetrics m = latest.getDisplayMetrics();
            if (now == null || m == null) return;
            if (cur == null || !cur.equals(now)
                    || getDisplayMetrics().density != m.density
                    || getDisplayMetrics().scaledDensity != m.scaledDensity) {
                @SuppressWarnings("deprecation")
                Resources self = this;
                self.updateConfiguration(now, m);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前生效的调色板;没有自定义主题时返回 null(此时全部走父类的编译期默认值) */
    private ThemePalette palette() {
        return ThemeRuntime.palette();
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
        String name = ThemeDrawables.iconTintKey(id, this);
        if (name == null) return d;
        ThemePalette p = palette();
        if (p == null) return d;
        try {
            Drawable copy = d.mutate();
            copy.setTintList(ColorStateList.valueOf(p.get(name)));
            return copy;
        } catch (Throwable th) {
            return d;
        }
    }
}
