package com.github.tvbox.osc.theme;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemePalette;

/**
 * 临时兼容层:只给<b>明确登记</b>的视图重新应用主题令牌。
 *
 * <p>这里不再读取视图当前像素颜色,也不再拿内置主题颜色做反向猜测。两个语义色即使在内置主题里
 * 恰好同值,切到自定义主题后也不会串到彼此。新代码优先在创建/绑定处调用
 * {@link ThemeDrawables#applyBackground(View, int)} 或下面的显式令牌入口;本类只负责尚未迁走的少数
 * 布局根、RecyclerView 子项挂载与第三方弹壳。
 */
public final class ThemeSweep {

    private ThemeSweep() {
    }

    /** 对根与子树应用已登记的资源令牌。未登记视图原样放过;不按颜色值推断任何语义。 */
    public static void apply(View root) {
        if (root == null || ThemeRuntime.runtimePalette() == null) return;
        applyRegisteredTree(root);
        try {
            root.post(() -> applyRegisteredTree(root));
        } catch (Throwable ignored) {
        }
    }

    private static void applyRegisteredTree(View view) {
        if (view == null) return;
        String token = backgroundTokenOf(view.getId());
        if ("bg_surface".equals(token)) {
            applyBackgroundColor(view, R.color.bg_surface);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyRegisteredTree(group.getChildAt(i));
            }
        }
    }

    /** 资源 id → 明确背景令牌;包内可见供 JVM 合同测试验证。 */
    static String backgroundTokenOf(int viewId) {
        if (viewId == R.id.bottom_nav_surface) return "bg_surface";
        // my_surface_card 原来在这里被铺成 bg_large_round_float —— 但那个布局根本没有背景:
        // 它只是"我的"页顶部的应用名标题行(fragment_my.xml 里只有 margin/padding)。
        // 于是换自定义主题时,那块**凭空多出一层底色**(用户口径:"我的界面的顶部标题,怎么多出了
        // bg_surface 背景色,那块没有背景啊")。布局没背景就不该由扫描层补,已移除这条登记;
        // 真正需要跟着主题走的那张卡片,在 fragment_my.xml 里自己写着 @drawable/bg_large_round_float,
        // 由布局属性注入通道负责,不依赖本类。
        return null;
    }

    /** 代码创建的纯色背景入口:资源 id 就是语义,不读取当前像素。 */
    public static void applyBackgroundColor(View view, int colorRes) {
        if (view == null) return;
        Integer color = themedColor(colorRes, view);
        if (color != null) view.setBackgroundColor(color);
    }

    /** 代码创建的文字入口。 */
    public static void applyTextColor(TextView view, int colorRes) {
        if (view == null) return;
        Integer color = themedColor(colorRes, view);
        if (color != null) view.setTextColor(color);
    }

    /** 代码创建的图片着色入口。 */
    public static void applyImageTint(ImageView view, int colorRes) {
        if (view == null) return;
        Integer color = themedColor(colorRes, view);
        if (color != null) view.setImageTintList(ColorStateList.valueOf(color));
    }

    /** RecyclerView 条目在每次挂载/复用时重放已登记令牌。 */
    public static void watchItems(View root) {
        if (root == null || ThemeRuntime.runtimePalette() == null) return;
        if (root instanceof androidx.recyclerview.widget.RecyclerView) {
            ((androidx.recyclerview.widget.RecyclerView) root)
                    .addOnChildAttachStateChangeListener(
                            new androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener() {
                                @Override
                                public void onChildViewAttachedToWindow(View view) {
                                    apply(view);
                                }

                                @Override
                                public void onChildViewDetachedFromWindow(View view) {
                                }
                            });
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) watchItems(group.getChildAt(i));
        }
    }

    private static Integer themedColor(int colorRes, View view) {
        try {
            ThemePalette palette = ThemeRuntime.runtimePalette();
            String token = ThemeColorAliases.paletteNameOf(colorRes);
            if (palette != null && token != null) return palette.get(token);
            return ContextCompat.getColor(view.getContext(), colorRes);
        } catch (Throwable ignored) {
            return null;
        }
    }

}
