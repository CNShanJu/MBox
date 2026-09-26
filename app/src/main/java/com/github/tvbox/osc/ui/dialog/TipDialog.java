package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.Utils;
import com.hjq.bar.TitleBar;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupStatus;

import org.jetbrains.annotations.NotNull;

/**
 * 提示/重试弹窗(统一走 XPopup 居中弹窗 AppCenterPopupView;观感与其它 XPopup 弹窗一致)。
 * <p>外部用法不变:{@code new TipDialog(ctx, tip, left, right, listener)} + {@link #show()} /
 * {@link #hide()} / {@link #isShowing()},支持同一实例反复 show/hide(单例复用):
 * {@link #show()} 首次自绑定时经 XPopup.Builder 绑定 popupInfo 并保留,再次 show 走
 * {@code super.show()}(isDestroyOnDismiss 默认 false,可安全复用);
 * 内容绑定全部在 {@link #onCreate()} 内完成,XPopup 保证 onCreate 只执行一次,不会重复绑定。
 * <p>语义兼容:{@link #hide()} ≈ dismiss;返回键触发 {@link OnListener#cancel()} 后关闭
 * (沿用 BaseDialog 的 OnCancelListener 语义);点弹窗外区域不关闭(与旧 Dialog 默认一致);
 * left/right/TitleBar rightView 点击仍分别回调 {@link OnListener#left()} / {@link OnListener#right()} /
 * {@link OnListener#onTitleClick()}。
 */
public class TipDialog extends AppCenterPopupView {

    private final String tip;
    private final String left;
    private final String right;
    private final OnListener listener;

    /** 收起动画未结束时又请求 show:延迟到 dismiss 结束后再展示,防止快速 hide→show 漏显示 */
    private boolean reShowPending = false;

    public TipDialog(@NonNull @NotNull Context context, String tip, String left, String right, OnListener listener) {
        super(context);
        this.tip = tip;
        this.left = left;
        this.right = right;
        this.listener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_tip;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TextView tipInfo = findViewById(R.id.tipInfo);
        TextView leftBtn = findViewById(R.id.leftBtn);
        TextView rightBtn = findViewById(R.id.rightBtn);
        TitleBar titleBar = findViewById(R.id.title_bar);
        if (tip != null) {
            tipInfo.setText(tip);
        }
        leftBtn.setText(left);
        rightBtn.setText(right);
        leftBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.left();
            }
        });
        rightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.right();
            }
        });
        titleBar.getRightView().setOnClickListener(view -> listener.onTitleClick());
    }

    /** 兼容旧调用点:popupInfo 未绑定(直接 new 未走 Builder)时经 Builder 绑定后展示 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            // 旧 Dialog:点弹窗外区域不取消,仅返回键触发 OnCancelListener.cancel()
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme())
                    .dismissOnTouchOutside(false)
                    .asCustom(this).show();
        }
        // 单例复用:上一次 dismiss 的收起动画尚未结束时请求 show,等动画结束后再展示,避免漏显示
        if (popupStatus == PopupStatus.Dismissing && !reShowPending) {
            reShowPending = true;
            long delay = getAnimationDuration() * 2L + 100L;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reShowPending = false;
                    if (popupStatus == PopupStatus.Dismiss) {
                        TipDialog.super.show();
                    }
                }
            }, delay);
            return this;
        }
        return super.show();
    }

    /** 兼容旧 Dialog API:隐藏弹窗;之后可再次 {@link #show()} */
    public void hide() {
        dismiss();
    }

    /** 兼容旧 Dialog API:当前是否展示中 */
    public boolean isShowing() {
        return isShow();
    }

    /** 兼容旧 Dialog API 的取消语义:返回键触发(listener.cancel() 后关闭) */
    @Override
    protected boolean onBackPressed() {
        listener.cancel();
        dismiss();
        return true;
    }

    public interface OnListener {
        void left();

        void right();

        void cancel();

        void onTitleClick();
    }
}
