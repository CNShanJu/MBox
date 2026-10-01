package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogInputSubsriptionBinding;
import com.lxj.xpopup.core.CenterPopupView;

/**
 * @Author : Liu XiaoRan
 * @Email : 592923276@qq.com
 * @Date : on 2023/8/17 09:28.
 * @Description :
 */
public class SubsciptionDialog extends AppCenterPopupView {

    public interface OnSubsciptionListener {
        void onConfirm(String name,String url,boolean check);
        void chooseLocal(boolean check);
        void chooseJson(boolean check);
    }

    private final String mDefaultName;
    private final String mDefaultUrl;
    /** 编辑模式:预填名称+地址,隐藏"本地导入/JSON导入"(那是新增入口),按钮改"保存" */
    private final boolean mEditMode;
    private OnSubsciptionListener listener;

    public SubsciptionDialog(@NonNull Context context, String defaultName, OnSubsciptionListener listener) {
        this(context, defaultName, null, false, listener);
    }

    public SubsciptionDialog(@NonNull Context context, String defaultName, String defaultUrl,
                             boolean editMode, OnSubsciptionListener listener) {
        super(context);
        mDefaultName = defaultName == null ? "" : defaultName;
        mDefaultUrl = defaultUrl == null ? "" : defaultUrl;
        mEditMode = editMode;
        this.listener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_input_subsription;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogInputSubsriptionBinding binding = DialogInputSubsriptionBinding.bind(getPopupImplView());
        if (mEditMode) {
            binding.tvTitle.setText("编辑订阅");
            binding.btnConfirm.setText("保 存");
            binding.tvLocal.setVisibility(android.view.View.GONE);
            binding.tvJson.setVisibility(android.view.View.GONE);
        }
        binding.etName.setText(mDefaultName);
        binding.etName.setSelection(mDefaultName.length());
        binding.etUrl.setText(mDefaultUrl);
        if (mEditMode) {
            binding.etUrl.setSelection(mDefaultUrl.length());
        }
        binding.btnCancel.setOnClickListener(v -> dismiss());
        binding.btnConfirm.setOnClickListener(view -> {
            String name = binding.etName.getText().toString().trim();
            if (name.isEmpty()) {
                AppBubble.toast("请输入名称");
                return;
            }
            String url = binding.etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                AppBubble.toast("请输入订阅地址");
                return;
            }
            if (listener != null) {
                listener.onConfirm(name,url,binding.cbCheck.isChecked());
            }
            dismiss();
        });

        binding.tvLocal.setOnClickListener(view -> {
            dismissWith(() -> listener.chooseLocal(binding.cbCheck.isChecked()));
        });

        binding.tvJson.setOnClickListener(view -> { //JSON 导入:粘贴 JSON 文本直接导入
            dismissWith(() -> listener.chooseJson(binding.cbCheck.isChecked()));
        });

        // 暂停订阅导入弹窗的输入框颜色诊断。
        /* 临时停用输入框颜色探针，保留代码供后续排障。
        try {
            getPopupImplView().postDelayed(() -> {
                try {
                    com.github.tvbox.osc.theme.RadiusCheck.reportInputsIn(
                            getPopupImplView(), "订阅导入弹窗");
                } catch (Throwable ignored) {
                }
            }, 600);
        } catch (Throwable ignored) {
        }
        */
    }
}
