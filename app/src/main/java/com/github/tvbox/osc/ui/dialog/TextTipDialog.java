package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogTextTipBinding;

/**
 * 通用文字提示弹窗(标题 + 正文 + 「知道了」)。
 * <p>
 * 用途:设置行是"标题 + 开关"的横排,长说明塞进去会挤(布局塞不下),故说明统一放这里,
 * 由设置行标题<b>长按</b>调出(见 SettingActivity.showSettingTip)。观感与「订阅提示」
 * ({@link SubsTipDialog})一致,同为底部弹窗。
 */
public class TextTipDialog extends AppBottomPopupView {

    private final String title;
    private final String content;

    public TextTipDialog(@NonNull Context context, String title, String content) {
        super(context);
        this.title = title;
        this.content = content;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_text_tip;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogTextTipBinding binding = DialogTextTipBinding.bind(getPopupImplView());
        binding.tvTipTitle.setText(TextUtils.isEmpty(title) ? "" : title);
        binding.tvTipContent.setText(TextUtils.isEmpty(content) ? "" : content);
        binding.btnTipOk.setOnClickListener(view -> dismiss());
    }
}
