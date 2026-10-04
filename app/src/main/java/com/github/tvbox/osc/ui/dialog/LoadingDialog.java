package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.theme.ThemeDrawables;
import com.github.tvbox.osc.util.LoadingAnim;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 全局加载框(居中):内容为全局加载态 Lottie({@link LoadingAnim#apply} 按设置切换动画/尺寸),
 * 替代 XPopup {@code asLoading()} 的默认转圈,保证订阅导入等所有 {@code showLoadingDialog}
 * 调用与全局加载态一致。有进度时动画、状态文字与取消键共用主题面板;纯动画态不铺面板底。
 * BACK 不关闭。
 * <p>
 * 可选状态文本({@link #setHint}):资源站嗅探等"要逐个试候选地址、可能十几秒"的流程用它
 * 显示当前进度,避免界面长时间无反馈被误认为点击无响应;文字与动画的间距取自动画配置
 * ({@link LoadingAnim#getMsgGapDp()}),避免动画图形下方的固有留白把两行内容撑得离得太远。
 */
public class LoadingDialog extends CenterPopupView {

    private TextView msgView;
    private View loadingPanel;
    private Drawable panelBackground;
    private View statusPanel;
    private com.google.android.material.button.MaterialButton cancelView;
    private CharSequence pendingHint;
    private Runnable onCancel;

    public LoadingDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getMaxWidth() {
        int compactWidth = Math.round(280 * getResources().getDisplayMetrics().density);
        return Math.min(compactWidth, DialogStyle.centerWidthPx(getContext()));
    }

    /**
     * 内容(卡片)按"扣掉系统栏后的窗口可用高度"封顶。
     *
     * <p>XPopup 只会把这个上限用在**内容容器**上(见 {@code XPopupUtils.applyPopupSize}),
     * 遮罩根视图不受影响 —— 这正是"内容别顶出可见区"该改的那一层。
     * 内容不高时(加载框常态:一段动画 + 一两行状态文字 + 可选的取消键)这个上限不生效,
     * 行为与不设上限一致。
     */
    @Override
    protected int getMaxHeight() {
        return DialogHeightPolicy.maxHeightPxInsideWindow(getContext());
    }

    /**
     * 遮罩就是**本视图自己的背景色**(XPopup 的 shadowBg 画在弹窗根视图上),所以每次显示前都要把
     * 根视图钉成"铺满宿主窗口"。两个坑都出在这一层:
     *
     * <ol>
     *   <li>XPopup 每次 {@code show()} 前都会 {@code doMeasure()} 把根视图量成"Activity 内容视图"
     *       那么大(见 BasePopupView#doMeasure);dialog 实现下它本该是 MATCH_PARENT。加载框在
     *       {@link com.github.tvbox.osc.base.BaseActivity} 里是**复用同一个实例**的,第二次起就可能
     *       按 doMeasure 的尺寸画遮罩,边上露一条没压暗的亮边。</li>
     *   <li>曾经在 {@code onLayout} 里把根视图高度收成"窗口可用高度"(本意是别让取消按钮被顶出可见区),
     *       那是**改错了对象**:根视图一矮,遮罩就只盖住上面一截,底部(输入法刚收起那块)永远是亮的
     *       —— 用户口径"遮罩不是全屏显示"。内容封顶请改内容(见 {@link #getMaxHeight()})。</li>
     * </ol>
     */
    @Override
    protected void beforeShow() {
        super.beforeShow();
        fitPanelToWindow();
        refreshPanelBackground();
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) return;
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
            return;
        }
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        setLayoutParams(lp);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_loading;
    }

    @Override
    protected void onCreate() {
        PopupKeyboardPolicy.onCreate(this);
        super.onCreate();
        LoadingAnim.apply(findViewById(R.id.lottie_loading));
        loadingPanel = findViewById(R.id.loading_panel);
        statusPanel = findViewById(R.id.loading_status_panel);
        msgView = findViewById(R.id.tv_loading_msg);
        cancelView = findViewById(R.id.btn_loading_cancel);
        fitPanelToWindow();
        refreshPanelBackground();
        applyMsgGap();
        setHint(pendingHint);   // show() 与 onCreate 之间设过的提示在此补上
        bindCancel();
    }

    private void fitPanelToWindow() {
        if (loadingPanel == null) return;
        ViewGroup.LayoutParams lp = loadingPanel.getLayoutParams();
        int width = getMaxWidth();
        // XPopup 的上限只收缩外层容器,固定宽度的内容还需同步收缩。
        if (lp != null && lp.width != width) {
            lp.width = width;
            loadingPanel.setLayoutParams(lp);
        }
    }

    private void refreshPanelBackground() {
        if (loadingPanel == null) return;
        panelBackground = ThemeDrawables.rebuild(R.drawable.bg_dialog, getResources());
        if (panelBackground == null) {
            panelBackground = ContextCompat.getDrawable(getContext(), R.drawable.bg_dialog);
        }
        // 主题允许 bg_float 半透明，进度面板强制不透底以免页面文字穿透。
        if (panelBackground instanceof GradientDrawable) {
            panelBackground = panelBackground.mutate();
            ((GradientDrawable) panelBackground).setColor(
                    ContextCompat.getColor(getContext(), R.color.bg_float) | 0xFF000000);
        }
        if (msgView != null) msgView.setTextColor(getResources().getColor(R.color.text_foreground));
        updatePanelVisibility();
    }

    @Override
    public void focusAndProcessBackPress() {
        super.focusAndProcessBackPress();
        PopupKeyboardPolicy.afterFocus(this);
    }

    /**
     * 设置"取消"回调:非空时显示取消按钮(导出/导入这类长耗时流程给用户一条退路),
     * 传 null 隐藏按钮并清掉回调(默认的阻塞式加载框就是这种)。
     */
    public void setOnCancel(Runnable listener) {
        onCancel = listener;
        bindCancel();
    }

    private void bindCancel() {
        if (cancelView == null) return;   // 弹窗还没创建完:由 onCreate 补上
        boolean show = onCancel != null;
        cancelView.setVisibility(show ? View.VISIBLE : View.GONE);
        cancelView.setOnClickListener(show ? v -> {
            Runnable r = onCancel;
            if (r != null) r.run();
        } : null);
        updatePanelVisibility();
    }

    /**
     * 状态文字与加载动画的间距取自动画自身配置({@link LoadingAnim#getMsgGapDp()},可为负):
     * Lottie 图形的可见内容常只占画布中上部,盒子底部有十几二十 dp 的固有留白,
     * 固定正间距会让"动画—文字"之间离得太远,配负值把文字区提进这段留白。
     * 只移动面板内的文字区,不移动整个面板。布局里的 2dp 只是兜底。
     */
    private void applyMsgGap() {
        ViewGroup.LayoutParams lp = statusPanel.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        int px = Math.round(LoadingAnim.getMsgGapDp() * getResources().getDisplayMetrics().density);
        if (mlp.topMargin == px) return;
        mlp.topMargin = px;
        statusPanel.setLayoutParams(mlp);
    }

    /** 更新状态文本;传空串/文本为 null 时隐藏该行(须在主线程调用) */
    public void setHint(CharSequence text) {
        pendingHint = text;
        if (msgView == null) return;   // 弹窗还没创建完:由 onCreate 应用
        boolean show = text != null && text.length() > 0;
        msgView.setText(show ? text : "");
        msgView.setVisibility(show ? View.VISIBLE : View.GONE);
        updatePanelVisibility();
    }

    private void updatePanelVisibility() {
        if (loadingPanel == null || statusPanel == null || msgView == null || cancelView == null) return;
        boolean hasStatus = msgView.getVisibility() == View.VISIBLE
                || cancelView.getVisibility() == View.VISIBLE;
        statusPanel.setVisibility(hasStatus ? View.VISIBLE : View.GONE);
        loadingPanel.setBackground(hasStatus ? panelBackground : null);
        float density = getResources().getDisplayMetrics().density;
        int horizontal = Math.round((hasStatus ? 20 : 12) * density);
        int vertical = hasStatus ? Math.round(20 * density) : 0;
        loadingPanel.setPadding(horizontal, vertical, horizontal, vertical);
    }

    /** 阻塞态:BACK 不关闭加载框,避免导入/请求中途被误关 */
    @Override
    protected boolean onBackPressed() {
        return true;
    }
}
