package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogLiveSettingBinding;

import org.jetbrains.annotations.NotNull;

/**
 * 直播设置弹窗（底部样式，竖屏/普通播放器设置入口）。
 * 内容编排全部委托 {@link LiveSettingPanel}；本类只保留 XPopup 底部壳差异。
 * 宿主能力经 {@link LiveSettingHost} 注入,不依赖具体 Activity 类型。
 */
public class LiveSettingDialog extends AppBottomPopupView {

    @NonNull
    private final LiveSettingHost mHost;
    private LiveSettingPanel mPanel;

    public LiveSettingDialog(@NonNull @NotNull Context context, @NonNull LiveSettingHost host) {
        super(context);
        mHost = host;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_live_setting;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogLiveSettingBinding binding = DialogLiveSettingBinding.bind(getPopupImplView());
        mPanel = new LiveSettingPanel(mHost, binding.mSettingGroupView, binding.mSettingItemView);
        mPanel.init();
    }
}
