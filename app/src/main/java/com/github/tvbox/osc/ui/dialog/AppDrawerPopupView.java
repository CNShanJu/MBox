package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.widget.FrostedGlassUtil;
import com.lxj.xpopup.core.DrawerPopupView;

/**
 * 统一的右侧(横向)抽屉基类:
 * 高度恒为全屏 100%;宽度按“屏幕可用宽度(dp)”分档封顶(≤480dp 允许全宽、≥700dp 超宽固定 560dp、
 * 区间 50% 屏宽,数值见 {@link DialogHeightPolicy}),作为上限、更窄的自定义宽度不受影响。
 * 背景统一使用圆角 + bg_popup(跟随主题深浅),内部组件由各页面自行决定。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()}。
 * 布局含 tag="glass_blur" 的 BlurView 时自动启用毛玻璃(布局根部叠 BlurView+半透明 bg_popup)。
 */
public abstract class AppDrawerPopupView extends DrawerPopupView {

    public AppDrawerPopupView(@NonNull Context context) {
        super(context);
    }

    /** 横向抽屉宽度上限:按屏宽(dp)分档(见 {@link DialogHeightPolicy#rightDrawerMaxWidthPx(Context)}) */
    @Override
    protected int getMaxWidth() {
        return DialogHeightPolicy.rightDrawerMaxWidthPx(getContext());
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 统一抽屉背景:圆角 + 主题背景色(bg_popup 浅色白 / 暗色深)
        View root = getPopupImplView();
        if (root != null) {
            root.setBackgroundResource(R.drawable.bg_drawer);
        }
        // 毛玻璃:布局里存在 tag="glass_blur" 的 BlurView 时, 模糊其后方内容(抽屉与列表分层更明显)
        FrostedGlassUtil.attach(root, getContext());
    }
}
