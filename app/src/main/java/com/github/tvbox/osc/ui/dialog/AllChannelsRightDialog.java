package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogAllChannelBinding;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupNewAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemNewAdapter;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

/**
 * 全部频道抽屉(右侧样式,全屏"换台"入口)。
 * 组/频道两个适配器经构造注入——它们的点击监听与状态由宿主 LiveActivity 统一注册维护,
 * 本类只负责将适配器绑定到对话框网格,不依赖具体 Activity 类型。
 */
public class AllChannelsRightDialog extends AppDrawerPopupView {

    private final LiveChannelGroupNewAdapter mGroupAdapter;
    private final LiveChannelItemNewAdapter mItemAdapter;
    private DialogAllChannelBinding mBinding;

    public AllChannelsRightDialog(@NonNull @NotNull Context context,
                                  @NonNull LiveChannelGroupNewAdapter groupAdapter,
                                  @NonNull LiveChannelItemNewAdapter itemAdapter) {
        super(context);
        mGroupAdapter = groupAdapter;
        mItemAdapter = itemAdapter;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_all_channel;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mBinding = DialogAllChannelBinding.bind(getPopupImplView());
        initChannelGroupView();
        initLiveChannelView();
    }

    private void initChannelGroupView() {
        mBinding.mGroupGridView.setHasFixedSize(true);
        mBinding.mGroupGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        if (mGroupAdapter != null) {
            mBinding.mGroupGridView.setAdapter(mGroupAdapter);
        }
    }

    private void initLiveChannelView() {
        mBinding.mChannelGridView.setHasFixedSize(true);
        mBinding.mChannelGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        if (mItemAdapter != null) {
            mBinding.mChannelGridView.setAdapter(mItemAdapter);
        }
    }

}
