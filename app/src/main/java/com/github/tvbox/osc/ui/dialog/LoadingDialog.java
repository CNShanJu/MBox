package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LoadingAnim;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 全局加载框(居中):内容为全局加载态 Lottie({@link LoadingAnim#apply} 按设置切换动画/尺寸),
 * 替代 XPopup {@code asLoading()} 的默认转圈,保证订阅导入等所有 {@code showLoadingDialog}
 * 调用与全局加载态一致。无卡片背景,由 XPopup 暗色遮罩垫底;BACK 不关闭(加载框为阻塞态)。
 * <p>
 * 可选状态文本({@link #setHint}):资源站嗅探等"要逐个试候选地址、可能十几秒"的流程用它
 * 显示当前进度,避免界面长时间无反馈被误认为点击无响应;文字与动画的间距取自动画配置
 * ({@link LoadingAnim#getMsgGapDp()}),避免动画图形下方的固有留白把两行内容撑得离得太远。
 */
public class LoadingDialog extends CenterPopupView {

    private TextView msgView;
    private com.google.android.material.button.MaterialButton cancelView;
    private CharSequence pendingHint;
    private Runnable onCancel;

    public LoadingDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_loading;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        LoadingAnim.apply(findViewById(R.id.lottie_loading));
        msgView = findViewById(R.id.tv_loading_msg);
        cancelView = findViewById(R.id.btn_loading_cancel);
        applyMsgGap();
        setHint(pendingHint);   // show() 与 onCreate 之间设过的提示在此补上
        bindCancel();
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
    }

    /**
     * 状态文字与加载动画的间距取自动画自身配置({@link LoadingAnim#getMsgGapDp()},可为负):
     * Lottie 图形的可见内容常只占画布中上部,盒子底部有十几二十 dp 的固有留白,
     * 固定正间距会让"动画—文字"之间离得太远,配负值把文字提进这段留白(订阅导入/资源站嗅探这类
     * 长耗时提示尤其明显)。布局里的 2dp 只是兜底,这里按当前动画覆盖。
     */
    private void applyMsgGap() {
        ViewGroup.LayoutParams lp = msgView.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        int px = Math.round(LoadingAnim.getMsgGapDp() * getResources().getDisplayMetrics().density);
        if (mlp.topMargin == px) return;
        mlp.topMargin = px;
        msgView.setLayoutParams(mlp);
    }

    /** 更新状态文本;传空串/文本为 null 时隐藏该行(须在主线程调用) */
    public void setHint(CharSequence text) {
        pendingHint = text;
        if (msgView == null) return;   // 弹窗还没创建完:由 onCreate 应用
        boolean show = text != null && text.length() > 0;
        msgView.setText(show ? text : "");
        msgView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 阻塞态:BACK 不关闭加载框,避免导入/请求中途被误关 */
    @Override
    protected boolean onBackPressed() {
        return true;
    }
}
