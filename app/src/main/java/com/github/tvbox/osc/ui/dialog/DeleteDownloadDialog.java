package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 删除下载确认弹窗(下载完成列表用):勾选"同时删除本地文件"。
 * 不勾 = 只删除记录,保留本地文件;勾选 = 记录与本地文件一起删除。
 * <p>
 * 弹窗背景来自布局根 dialog_delete_download(bg_large_round_popup → 主题色 bg_popup,
 * 含 theme_colors 的 bg_popup_alpha 透明度, 深浅色随主题);勾选行无背景无涟漪,
 * 复选框用默认样式(与下载完成长按多选一致)。
 */
public class DeleteDownloadDialog extends AppCenterPopupView {

    public interface OnDeleteListener {
        void onDelete(boolean deleteFiles);
    }

    private final OnDeleteListener mListener;

    public DeleteDownloadDialog(@NonNull @NotNull Context context, OnDeleteListener listener) {
        super(context);
        mListener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_delete_download;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        CheckBox cb = findViewById(R.id.cb_delete_file);
        View llCheck = findViewById(R.id.ll_check);
        // 弹窗背景由布局根 dialog_delete_download 提供(bg_large_round_popup → 主题 bg_popup,含 bg_popup_alpha 透明度)
        // 点整行切换勾选(勾选行无背景/无涟漪,复选框用默认样式与下载完成多选一致)
        llCheck.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
        findViewById(R.id.tv_cancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.tv_ok).setOnClickListener(v -> {
            boolean deleteFiles = cb.isChecked();
            dismiss();
            if (mListener != null) {
                mListener.onDelete(deleteFiles);
            }
        });
    }
}
