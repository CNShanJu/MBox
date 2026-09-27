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
     * 把某个视图**已有的**纯色底就地改成当前主题的浮层面({@code bg_float}),圆角/描边/尺寸原样保留。
     *
     * <p>与 {@link #apply(View)} 的区别:那一趟只换"颜色还等于内置那份"的视图(按值比对,防止误伤
     * 有意写死的颜色);而有些底**按设计就是浮层面**,只是它的颜色是别人在 native 资源解析里取的
     * 编译期那份 —— 典型是 XPopup 的 {@code AttachPopupView#applyBg}:它把 impl 视图的背景搬到外层容器,
     * 搬过去的是它自己 Resources 重新解析出来的 drawable,换肤注入拦不到,按值比对又可能因为
     * 日夜档位不同而不命中(现象:"气泡的圆角对了,颜色又不走卡片/悬浮层的颜色")。
     * 这种地方就该直接说"这块面是 bg_float",而不是猜它原来是不是内置色。
     *
     * <p>只动纯色底(渐变/多层/无底一律不碰),并且 {@code mutate()} 后再改,不会串到同一份 drawable 的其它使用者。
     * <p>和 {@link #apply(View)} 一样补两趟(当场 + 布局之后):{@code apply} 自己也会 {@code post} 一趟,
     * 两趟的顺序不能保证"面"排在最后 —— 主题的 bg_float 恰好等于内置的 bg_surface/bg_card 时,
     * 后跑的那一趟会把它当内置面换掉(浮层透明度就丢了)。这一趟 post 在 apply 之后入队,必然后跑。
     */
    public static void applyFloatFace(View v) {
        if (v == null) return;
        forceFloatFace(v);
        try {
            v.post(() -> forceFloatFace(v));
        } catch (Throwable ignored) {
        }
    }

    private static void forceFloatFace(View v) {
        ThemePalette palette = ThemeRuntime.palette();
        if (palette == null) return;
        try {
            if (solidColorOf(v.getBackground()) == null) return;
            setSolidColor(v.getBackground(), palette.get("bg_float"));
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
        // ① 背景:还是内置那份"面/浮层"色 → 换成主题的对应档;
        //    如果是内置的"文字档"色(1dp 分割线这类拿 @color/text_foreground 当底的 View)→ 换同档文字色。
        //    只有 TextView 那一支认文字色是不够的:分割线是裸 View,背景那一支原先只比对面/浮层色,
        //    于是它永远停在编译期颜色(用户口径:"搜索页 x 后面的 | 分割线颜色没走主要文字色")。
        Drawable bg = v.getBackground();
        if (bg != null) {
            Integer now = solidColorOf(bg);
            if (now != null) {
                if (now == builtin.get("bg_card") || now == builtin.get("bg_surface")) {
                    setSolidColor(bg, palette.get("bg_surface"));
                } else if (now == builtin.get("bg_float")) {
                    setSolidColor(bg, palette.get("bg_float"));
                } else {
                    for (String key : TEXT_KEYS) {
                        if (now == builtin.get(key)) {
                            setSolidColor(bg, palette.get(key));
                            break;
                        }
                    }
                }
            }
        }
        // ② 文字:还是内置那份文字档色 → 换成主题的同一档。
        //    弹窗列表里的 item(MaterialCheckBox 那种)就是这么漏的 ——
        //    它们写的是 @color/text_foreground,但视图本身没被注入到(用户口径:"弹窗里面的 item 没走主题色")。
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            String textKey = builtinTextKeyOf(tv.getCurrentTextColor(), builtin);
            if (textKey != null) {
                tv.setTextColor(palette.get(textKey));
            }
            // ②b 提示/占位文字(EditText 的 hint):
            //     用户口径"搜索框里的提示文本没走文字主色(的透明度)"就是这么漏的 ——
            //     两个搜索框的视图类(首页 SizedIconTextView、搜索页 ClearEditText)在布局里写的是**全限定类名**,
            //     框架走 createView 反射兜底路径造它们,布局注入器拿不到那次 onCreateView,只能靠这趟补色;
            //     而本类原来只补"正文色",于是同一个框里"正文跟着主题变了、提示文字还是内置那档灰"。
            //     口径与正文完全一致:仍是内置那一档文字色才换(不是内置色的说明是别人有意设的,不动)。
            android.content.res.ColorStateList hint = tv.getHintTextColors();
            if (hint != null) {
                String hintKey = builtinTextKeyOf(hint.getDefaultColor(), builtin);
                if (hintKey != null) {
                    tv.setHintTextColor(palette.get(hintKey));
                }
            }
            // ③ compound drawable 的着色(图标):同样是"内置那份文字色"→ 换主题同档。
            //    不补这一支的话,同一个控件会出现"文字跟着主题走了、图标还停在编译期颜色"。
            android.content.res.ColorStateList tint = tv.getCompoundDrawableTintList();
            if (tint != null) {
                String tintKey = builtinTextKeyOf(tint.getDefaultColor(), builtin);
                if (tintKey != null) {
                    tv.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(palette.get(tintKey)));
                }
            }
        }
        // ④ ImageView 的着色(气泡/抽屉里那些独立图标):同一套比对
        if (v instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) v;
            android.content.res.ColorStateList tint = iv.getImageTintList();
            if (tint != null) {
                String key = builtinTextKeyOf(tint.getDefaultColor(), builtin);
                if (key != null) {
                    iv.setImageTintList(android.content.res.ColorStateList.valueOf(palette.get(key)));
                }
            }
        }
    }

    /** 参与"文字档"比对的键(顺序无关,命中即换) */
    private static final String[] TEXT_KEYS = {
            "text_main", "text_sub", "text_hint", "text_disable",
            "text_accent", "text_highlight", "color_highlight", "btn_select_text",
    };

    /**
     * 当前颜色是否"仍然是内置主题的某一档文字色";是则返回该档的键,否则 {@code null}。
     *
     * <p>整类补色的判据就一句话:<b>只换"还是内置那一档"的颜色</b> —— 不是内置色的,
     * 说明是代码或布局里有意的取值(例如 {@code text_danger} 这种写死字面量),不能动。
     * 抽成纯函数一是四处共用(正文/hint/compound 图标/ImageView 图标),二是可 JVM 单测这条判据
     * (见 ThemeSweepMatchTest:命中内置档才换、非内置色一律放过)。
     */
    static String builtinTextKeyOf(int color, ThemePalette builtin) {
        if (builtin == null) return null;
        for (String key : TEXT_KEYS) {
            if (color == builtin.get(key)) return key;
        }
        return null;
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
