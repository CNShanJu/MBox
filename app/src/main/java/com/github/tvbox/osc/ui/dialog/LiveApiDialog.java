package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogInputSubsriptionBinding;
import com.github.tvbox.osc.databinding.DialogLiveApiBinding;
import com.github.tvbox.osc.util.LiveConfig;
import com.github.tvbox.osc.config.SystemConfig;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.CenterPopupView;
import com.lxj.xpopup.interfaces.OnInputConfirmListener;

import java.util.ArrayList;

public class LiveApiDialog extends AppCenterPopupView {

    private com.github.tvbox.osc.databinding.DialogLiveApiBinding mBinding;
    /** 点"确定"保存成功后的回调(订阅管理-直播源页用它刷新列表;不传则与原来完全一致) */
    private final Runnable mOnSaved;

    public LiveApiDialog(@NonNull Context context) {
        this(context, null);
    }

    public LiveApiDialog(@NonNull Context context, Runnable onSaved) {
        super(context);
        mOnSaved = onSaved;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_live_api;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mBinding = DialogLiveApiBinding.bind(getPopupImplView());
        String liveApi = SystemConfig.getLiveUrl();
        updateEt(liveApi);

        mBinding.ivHistory.setOnClickListener(view -> {
            ArrayList<String> liveHistory = LiveConfig.liveHistory();
            if (liveHistory.isEmpty()){
                AppBubble.toast("暂无历史记录");
                return;
            }
            // ApiHistoryDialog 是底部抽屉:走 bottom(...) 才带上 isViewMode/hasNavigationBar 这两个参数,
            // 与其它抽屉同款(原来用 center(...) 只是 asCustom,位置对但少了这两个参数,底边观感不一致)
            DialogCoordinator.bottom(getContext(), new ApiHistoryDialog(getContext(), liveApi, this::updateEt), 0).show();
        });

        mBinding.btnCancel.setOnClickListener(v -> dismiss());
        mBinding.btnConfirm.setOnClickListener(view -> {
            String newLive = mBinding.etUrl.getText().toString().trim();
            // Capture Live input into Settings & Live History (max 20)
            SystemConfig.setLiveUrl(newLive);
            if (!newLive.isEmpty()) {
                ArrayList<String> liveHistory = LiveConfig.liveHistory();
                if (!liveHistory.contains(newLive))
                    liveHistory.add(0, newLive);
                if (liveHistory.size() > 20)
                    liveHistory.remove(20);
                LiveConfig.setLiveHistory(liveHistory);
            }
            AppBubble.toast("设置成功");
            if (mOnSaved != null) mOnSaved.run();
            dismiss();
        });
    }

    private void updateEt(String text){
        mBinding.etUrl.setText(text);
        mBinding.etUrl.setSelection(text.length());
    }
}