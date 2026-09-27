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
        // **再补一趟"布局之后"**:列表 item / 异步填充的子视图都是在这之后才出现的 ——
        // 只扫创建那一刻,弹窗列表里的 item 永远扫不到(用户口径:"加载动画弹窗里的 item
        // 文字没变、背景还是白的")。
        try {
            root.post(() -> {
                try {
                    walk(root, palette, builtin);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给列表挂"子项挂载即补色":RecyclerView 的 item 可能在任何时刻被复用/新造出来
     * (首帧之后、滚动时、数据刷新后),靠一次性扫描盖不全。
     */
    public static void watchItems(View root) {
        final ThemePalette palette = ThemeRuntime.palette();
        if (root == null || palette == null) return;
        final ThemePalette builtin = builtin();
        if (builtin == null) return;
        if (root instanceof androidx.recyclerview.widget.RecyclerView) {
            ((androidx.recyclerview.widget.RecyclerView) root)
                    .addOnChildAttachStateChangeListener(
                            new androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener() {
                                @Override
                                public void onChildViewAttachedToWindow(View view) {
                                    try {
                                        walk(view, palette, builtin);
                                    } catch (Throwable ignored) {
                                    }
                                }

                                @Override
                                public void onChildViewDetachedFromWindow(View view) {
                                }
                            });
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                watchItems(g.getChildAt(i));
            }
        }
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
        // ① 背景:还是内置那份"面/浮层"色 → 换成主题的对应档
        Drawable bg = v.getBackground();
        if (bg != null) {
            Integer now = solidColorOf(bg);
            if (now != null) {
                if (now == builtin.get("bg_card") || now == builtin.get("bg_surface")) {
                    setSolidColor(bg, palette.get("bg_surface"));
                } else if (now == builtin.get("bg_float")) {
                    setSolidColor(bg, palette.get("bg_float"));
                }
            }
        }
        // ② 文字:还是内置那份文字档色 → 换成主题的同一档。
        //    弹窗列表里的 item(MaterialCheckBox 那种)就是这么漏的 ——
        //    它们写的是 @color/text_foreground,但视图本身没被注入到(用户口径:"弹窗里面的 item 没走主题色")。
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            int nowText = tv.getCurrentTextColor();
            for (String key : TEXT_KEYS) {
                if (nowText == builtin.get(key)) {
                    tv.setTextColor(palette.get(key));
                    break;
                }
            }
        }
    }

    /** 参与"文字档"比对的键(顺序无关,命中即换) */
    private static final String[] TEXT_KEYS = {
            "text_main", "text_sub", "text_hint", "text_disable",
            "text_accent", "text_highlight", "color_highlight", "btn_select_text",
    };

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
