package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.widget.FrostedGlassUtil;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.core.BottomPopupView;

/**
 * 统一的底部弹窗基类:
 * 背景统一使用 {@link R.drawable#bg_bottom_dialog}(顶部圆角 + bg_float 主题色),
 * 最大高度统一按 {@link DialogHeightPolicy} 分档封顶(内容自适应,内容少时保持内容高);
 * 横屏时宽度限制为屏幕 55%,所有宽屏再按 {@link DialogStyle#BOTTOM_MAX_WIDTH_DP} 封顶并居中。
 * 调主题背景/尺寸只改基类/常量,一处生效全部底部弹窗。
 * 子类只需实现 {@link #getImplLayoutId()} 与各自 {@link #onCreate()};
 * 标题+内容+按钮结构时,中间内容区用 weight=1 + 内部滚动,避免挤压上下标题/按钮。
 * 布局含 tag="glass_blur" 的 BlurView 时自动启用毛玻璃。
 * <p>弹壳绑定({@link #show()})也在基类兜底:子类既可经 {@link DialogCoordinator#bottom} 弹,
 * 也可直接 {@code new XxxBottomPopup(ctx).show()},两条路观感一致。
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
        PopupKeyboardPolicy.onCreate(this);
        super.onCreate();
        // 统一底部弹窗背景:顶部圆角 + 主题背景色(bg_float 浅色白 / 暗色深)
        View root = getPopupImplView();
        if (root != null) {
            // 统一底部弹窗背景:顶部圆角 + 主题背景色(bg_float 浅色白 / 暗色深)。
            // **不能直接 setBackgroundResource** —— 那按编译期资源取色,会把换肤注入好的主题底
            // 又覆盖回内置色(用户口径:"弹窗的透明度怎么都不变");走 themedDrawable 才认自定义主题。
            com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(root, R.drawable.bg_bottom_dialog);
            // 列表条目在挂载/复用时只重放已登记资源令牌,不按当前像素猜颜色。
            com.github.tvbox.osc.theme.ThemeSweep.watchItems(root);
            // 手机竖屏保持全宽;横屏先收至 55%,平板及超宽屏统一封顶。
            int screenWidth = ScreenUtils.getScreenWidth();
            int maxWidth = Math.round(DialogStyle.BOTTOM_MAX_WIDTH_DP
                    * getContext().getResources().getDisplayMetrics().density);
            int width = Math.min(ScreenUtils.isLandscape()
                    ? Math.round(screenWidth * 0.55f) : screenWidth, maxWidth);
            if (width < screenWidth) {
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                if (lp == null) {
                    lp = new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                } else {
                    lp.width = width;
                }
                root.setLayoutParams(lp);
                // 宽度收窄后保持贴底、水平居中。
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

    @Override
    public void focusAndProcessBackPress() {
        super.focusAndProcessBackPress();
        PopupKeyboardPolicy.afterFocus(this);
    }

    /** 弹壳兜底:popupInfo 只由 XPopup.Builder 绑定,未经 Builder 直接 {@code show()} 会命中
     * {@code BasePopupView.show()} 的硬校验并抛
     * {@code IllegalArgumentException: popupInfo is null}。
     * <p><b>为什么放基类</b>(2026-09-27,日志页错误日志的日期抽屉崩溃):{@code BottomListDialog} 按
     * "公共抽屉组件"设计、文档就写着 {@code new BottomListDialog(...).show()},但漏了 Builder 绑定,
     * 一点日期即崩;此类"新公共弹窗忘了自绑定"的坑每个子类都要各写一遍 show() 才躲得过。
     * 收口到基类后,所有底部弹窗(含以后新增的)直接 show() 都安全。
     * <p>已绑定的调用点(经 {@link DialogCoordinator} 弹)走 {@code super.show()},行为完全不变;
     * 壳参数用 {@link DialogCoordinator#bottom} 与全站底部弹窗保持同一套(view 模式 + 无导航栏占位)。
     */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            // 高度交回 XPopup 自己按内容与 getMaxHeight 决定:这里**不要**传 popupHeight ——
            // 传了就变成"强制固定高度",内容少的抽屉会被撑出一段空白、位置也被拉歪
            // (用户口径:"抽屉都跑哪去了,位置都不对")。
            return DialogCoordinator.bottom(getContext(), this, 0).show();
        }
        return super.show();
    }
}
