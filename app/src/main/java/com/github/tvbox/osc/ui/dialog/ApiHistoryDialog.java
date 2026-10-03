package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogTitleListBinding;
import com.github.tvbox.osc.ui.adapter.TitleWithDelAdapter;
import com.github.tvbox.osc.ui.kit.LinearSpacingItemDecoration;
import com.github.tvbox.osc.util.LiveConfig;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BottomPopupView;
import com.lxj.xpopup.interfaces.OnInputConfirmListener;

import java.util.ArrayList;

/**
 * @Author : Liu XiaoRan
 * @Email : 592923276@qq.com
 * @Date : on 2023/10/26 10:52.
 * @Description :
 */
public class ApiHistoryDialog extends AppBottomPopupView {
    private final String mPreApi;
    private final OnInputConfirmListener mOnInputConfirmListener;
    private ArrayList<String> mLiveHistory;

    public ApiHistoryDialog(@NonNull Context context, String preApi, OnInputConfirmListener onInputConfirmListener) {
        super(context);
        mPreApi = preApi;
        mOnInputConfirmListener = onInputConfirmListener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_title_list;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogTitleListBinding binding = DialogTitleListBinding.bind(getPopupImplView());
        binding.title.setText("历史直播源");

        binding.ivUseTip.setOnClickListener(view -> {
            ConfirmDialog.show(getContext(), "使用帮助",
                    "这里显示手动添加过的直播源,最多20条。订阅自带直播源请到「订阅管理 → 直播源」查看。", "知道了", null);
        });

        binding.rv.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.rv.addItemDecoration(new LinearSpacingItemDecoration(20, true));
        TitleWithDelAdapter adapter = new TitleWithDelAdapter();
        binding.rv.setAdapter(adapter);

        mLiveHistory = LiveConfig.liveHistory();
        mLiveHistory.remove(mPreApi);
        adapter.setNewData(mLiveHistory);
        adapter.setOnItemChildClickListener((adapter1, view, position) -> {
            if (view.getId() == R.id.tvDel) {
                String url = mLiveHistory.get(position);
                ConfirmDialog.showDanger(getContext(), "删除直播源", "确定从历史直播源中删除这条地址吗？", "删除", () -> {
                    if (mLiveHistory.remove(url)) {
                        adapter1.notifyDataSetChanged();
                        LiveConfig.setLiveHistory(mLiveHistory);
                    }
                });
            }else {
                mOnInputConfirmListener.onConfirm(mLiveHistory.get(position));
                dismiss();
            }
        });
    }

    @Override
    public void onDestroy() {
        mLiveHistory.add(0,mPreApi);
        LiveConfig.setLiveHistory(mLiveHistory);
        super.onDestroy();
    }
}
