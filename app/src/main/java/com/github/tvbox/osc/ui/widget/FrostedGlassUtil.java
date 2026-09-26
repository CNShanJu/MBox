package com.github.tvbox.osc.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.github.tvbox.osc.util.StackBlurBlur;

import eightbitlab.com.blurview.BlurView;

/**
 * 弹层/抽屉毛玻璃工具：给布局里打了 tag="glass_blur" 的 BlurView 挂到窗口上，
 * 实时模糊其后方（弹层覆盖的区域）内容，上方再由半透明主题色 bg_popup 叠色成毛玻璃质感。
 * <p>
 * 用法：在弹层根布局里放
 * {@code <eightbitlab.com.blurview.BlurView ... android:tag="glass_blur"/>}
 * 和一个 {@code <View android:background="@color/bg_popup"/>}（半透明由 bg_popup_alpha 控制），
 * 弹层基类 onCreate 时调用 {@link #attach(View, Context)} 即可，无该 tag 时静默跳过。
 */
public final class FrostedGlassUtil {

    public static final String TAG = "glass_blur";
    /** 默认模糊半径(px 缩放前, BlurView 内部按 view 尺寸处理) */
    private static final float BLUR_RADIUS = 24f;

    private FrostedGlassUtil() {
    }

    /**
     * 为弹层附加毛玻璃(幂等、失败静默)。要求 context 是 Activity(弹层挂在 activity 窗口内)。
     */
    public static void attach(View popupRoot, Context context) {
        try {
            if (popupRoot == null || !(context instanceof Activity)) return;
            View child = findTagged(popupRoot, TAG);
            if (!(child instanceof BlurView)) return;
            final BlurView blur = (BlurView) child;
            final Activity activity = (Activity) context;
            ViewGroup decorContent = activity.getWindow().getDecorView()
                    .findViewById(android.R.id.content);
            if (decorContent == null) return;
            blur.setupWith(decorContent)
                    .setFrameClearDrawable(activity.getWindow().getDecorView().getBackground())
                    .setBlurAlgorithm(new StackBlurBlur())
                    .setBlurRadius(BLUR_RADIUS)
                    .setBlurAutoUpdate(true);
            // 部分弹层根布局是 wrap 测量, 子 BlurView 的 match_parent 会塌成 0,
            // 布局完成后按弹层实际大小补齐, 保证模糊层铺满整个抽屉/弹窗区域
            popupRoot.post(() -> {
                try {
                    int w = popupRoot.getWidth();
                    int h = popupRoot.getHeight();
                    if (w > 0 && h > 0) {
                        ViewGroup.LayoutParams lp = blur.getLayoutParams();
                        if (lp.width != w || lp.height != h) {
                            lp.width = w;
                            lp.height = h;
                            blur.setLayoutParams(lp);
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable th) {
            Log.d("FrostedGlassUtil", "毛玻璃附加失败: " + th.getMessage());
        }
    }

    private static View findTagged(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View v = findTagged(group.getChildAt(i), tag);
                if (v != null) return v;
            }
        }
        return null;
    }
}
