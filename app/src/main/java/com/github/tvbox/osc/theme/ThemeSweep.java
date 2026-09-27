package com.github.tvbox.osc.theme;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.bean.theme.ThemeType;
import com.github.tvbox.osc.storage.theme.ThemeStore;

/**
 * 视图树"补色"兜底:把**仍然是内置主题那份色**的面/浮层底,改成当前自定义主题的颜色。
 *
 * <p>为什么需要它(真机定位到的):换肤的布局属性注入只对"由被补丁过的 inflater 造出来的视图"有效,
 * 而 MainActivity 的底栏容器、以及走 ViewBinding + AutoSize 的 Fragment(我的页那张卡片)实测拿不到
 * —— 现象是:二级页标题栏(代码取色)跟着主题变了,而这两处的底**永远是内置浅色面**
 * (用户口径:"二级页面的顶部标题栏都变了…为什么我的界面的中间卡片背景没变?底部导航栏也没变")。
 *
 * <p>做法不看 inflater,只看**视图当前画的是什么色**:背景是渐变/纯色且颜色 == <b>同类型内置主题</b>
 * 的 {@code bg_card}/{@code bg_float},就地把颜色改成调色板里的对应值。圆角/描边/状态都原样保留
 * (只改颜色,不重建 drawable),所以不会把形状弄坏,也不会影响非主题的底色。
 */
public final class ThemeSweep {

    private ThemeSweep() {
    }

    /** 补一遍(幂等:已经是主题色的视图按值比较不会命中) */
    public static void apply(View root) {
        ThemePalette palette = ThemeRuntime.palette();
        if (root == null || palette == null) return;
        ThemePalette builtin = builtin();
        if (builtin == null) return;
        walk(root, palette, builtin);
    }

    private static ThemePalette builtin() {
        try {
            ThemeType type = ThemeStore.activeType();
            return ThemeStore.builtinPalette(type);
        } catch (Throwable th) {
            return null;
        }
    }

    private static void walk(View v, ThemePalette palette, ThemePalette builtin) {
        try {
            recolor(v, palette, builtin);
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), palette, builtin);
            }
        }
    }

    private static void recolor(View v, ThemePalette palette, ThemePalette builtin) {
        Drawable bg = v.getBackground();
        if (bg == null) return;
        Integer now = solidColorOf(bg);
        if (now == null) return;
        if (now == builtin.get("bg_card") || now == builtin.get("bg_surface")) {
            setSolidColor(bg, palette.get("bg_surface"));
        } else if (now == builtin.get("bg_float")) {
            setSolidColor(bg, palette.get("bg_float"));
        }
    }

    /** 取 drawable 的单一实色(渐变/层叠/多色返回 null,不去动它) */
    private static Integer solidColorOf(Drawable d) {
        if (d instanceof ColorDrawable) return ((ColorDrawable) d).getColor();
        if (d instanceof GradientDrawable) {
            android.content.res.ColorStateList csl = ((GradientDrawable) d).getColor();
            return csl == null ? null : csl.getDefaultColor();
        }
        return null;
    }

    private static void setSolidColor(Drawable d, int color) {
        Drawable copy = d.mutate(); // mutate:别把同一份 drawable 的其它使用者一起改掉
        if (copy instanceof ColorDrawable) {
            ((ColorDrawable) copy).setColor(color);
        } else if (copy instanceof GradientDrawable) {
            ((GradientDrawable) copy).setColor(color);
        }
    }
}
