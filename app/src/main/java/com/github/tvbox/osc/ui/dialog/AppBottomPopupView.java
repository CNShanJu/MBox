package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.widget.FrostedGlassUtil;
import com.lxj.xpopup.core.BottomPopupView;

/**
 * 统一的底部弹窗基类:
 * 背景统一使用 {@link R.drawable#bg_bottom_dialog}(顶部圆角 + bg_popup 主题色),
 * 最大高度统一按 {@link DialogHeightPolicy} 分档封顶(内容自适应,内容少时保持内容高);
 * 横屏时宽度限制为屏幕 55% 居中, 防全宽挤压。
 * 调主题背景/尺寸只改基类/常量,一处生效全部底部弹窗。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()};
 * 标题+内容+按钮结构时,中间内容区用 weight=1 + 内部滚动,避免挤压上下标题/按钮。
 * 布局含 tag="glass_blur" 的 BlurView 时自动启用毛玻璃。
 */
public abstract class AppBottomPopupView extends BottomPopupView {

    public AppBottomPopupView(@NonNull Context context) {
        super(context);
    }

    /**
     * 纵向抽屉最大高度:按“屏幕可用高度(dp)”分档(≤480dp→铺满、≥700dp→固定 540dp、区间→50%,
     * 区间档可拖拽展开(enableDrag)时放宽到 70%);内容少时保持内容自然高度。
     * 阈值/数值集中在 {@link DialogHeightPolicy}。
     */
    @Override
    protected int getMaxHeight() {
        boolean dragExpandable = popupInfo != null && popupInfo.enableDrag;
        return DialogHeightPolicy.bottomDrawerMaxHeightPx(getContext(), dragExpandable);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 统一底部弹窗背景:顶部圆角 + 主题背景色(bg_popup 浅色白 / 暗色深)
        View root = getPopupImplView();
        if (root != null) {
            root.setBackgroundResource(R.drawable.bg_bottom_dialog);
            // 横屏适配:内容宽度限 屏幕55% 并居中(竖屏保持全宽), 防横屏被拉全宽挤压
            if (ScreenUtils.isLandscape()) {
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                int w = Math.round(ScreenUtils.getScreenWidth() * 0.55f);
                if (lp == null) {
                    lp = new ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
                } else {
                    lp.width = w;
                }
                root.setLayoutParams(lp);
                // 横屏时父容器水平居中
                View parent = (View) root.getParent();
                if (parent instanceof FrameLayout) {
                    FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) root.getLayoutParams();
                    fl.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                    root.setLayoutParams(fl);
                }
            }
        }
        // 毛玻璃:布局里存在 tag="glass_blur" 的 BlurView 时, 模糊弹层覆盖区域的下方内容
        FrostedGlassUtil.attach(root, getContext());
    }
}
