package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ClipboardUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogInputJsonBinding;
import com.github.tvbox.osc.util.AppBubble;
import com.google.gson.JsonParser;

/**
 * JSON 文本导入弹窗:粘贴/输入 JSON(订阅清单、多线路、多仓,或整份配置内容)后点"导入",
 * 由宿主 {@link OnJsonImportListener} 决定如何落库——本类只负责取文本与格式校验,
 * 校验不通过时不关闭弹窗,方便用户就地修改。
 */
public class JsonImportDialog extends AppCenterPopupView {

    public interface OnJsonImportListener {
        void onConfirm(String json);
    }

    private final OnJsonImportListener listener;

    public JsonImportDialog(@NonNull Context context, OnJsonImportListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_input_json;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogInputJsonBinding binding = DialogInputJsonBinding.bind(getPopupImplView());
        binding.btnCancel.setOnClickListener(v -> dismiss());
        // 粘贴键是"点击型"小组件按钮:补点击特效(按下整键透明度 80% 再恢复)
        com.github.tvbox.osc.ui.kit.WidgetPressEffect.attach(binding.tvPaste);
        binding.tvPaste.setOnClickListener(v -> {
            CharSequence text = ClipboardUtils.getText();
            if (TextUtils.isEmpty(text)) {
                AppBubble.toast("剪贴板为空");
                return;
            }
            String pasted = text.toString().trim();
            binding.etJson.setText(pasted);
            binding.etJson.setSelection(binding.etJson.getText().length());
        });
        binding.btnConfirm.setOnClickListener(view -> {
            String json = binding.etJson.getText().toString().trim();
            if (json.isEmpty()) {
                AppBubble.toast("请输入 JSON 内容");
                return;
            }
            if (!isJson(json)) {
                AppBubble.toast("JSON 格式不正确,请检查内容");
                return; // 不关闭弹窗,保留内容供修改
            }
            if (listener != null) {
                listener.onConfirm(json);
            }
            dismiss();
        });
    }

    /** 是否合法 JSON(宽松解析,只做格式兜底;具体是清单/多线路/多仓由宿主识别) */
    private boolean isJson(String text) {
        try {
            JsonParser.parseString(text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
