package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/**
 * 线路选择抽屉(底部弹层):列出当前频道所有线路,点击切换并收起。
 * 非全屏点击"线路 x/y"走这里;全屏走右侧抽屉 {@link LiveLineSelectRightDialog}。
 * 宿主能力经 {@link LiveLineSelectHost} 注入,不依赖具体 Activity 类型。
 */
public class LiveLineSelectDialog extends AppBottomPopupView {

    private final LiveLineSelectHost mHost;

    public LiveLineSelectDialog(@NonNull @NotNull Context context, @NonNull LiveLineSelectHost host) {
        super(context);
        mHost = host;
    }

    /** 线路抽屉共用的列表装载与点击处理(底部/右侧两套壳共用) */
    static void bind(TvRecyclerView lineView, LiveLineSelectHost host, Runnable dismiss) {
        LiveChannelItem channelItem = host.getCurrentLiveChannelItem();
        if (channelItem == null) {
            dismiss.run();
            return;
        }
        lineView.setHasFixedSize(true);
        lineView.setLayoutManager(new V7LinearLayoutManager(lineView.getContext(), 1, false));
        ArrayList<LiveSettingItem> items = buildItems(channelItem);
        final LiveSettingItemAdapter adapter = new LiveSettingItemAdapter();
        lineView.setAdapter(adapter);
        adapter.setNewData(items);
        if (!items.isEmpty()) {
            adapter.selectItem(channelItem.getSourceIndex(), true, false);
        }
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter1, View view, int position) {
                if (channelItem.getSourceIndex() == position) {
                    dismiss.run();
                    return;
                }
                host.switchingLine2Replay(position);
                dismiss.run();
            }
        });
    }

    /** 构建线路条目;无线路名(纯 url)时按序号兜底,保证抽屉有值 */
    public static ArrayList<LiveSettingItem> buildItems(LiveChannelItem channelItem) {
        ArrayList<LiveSettingItem> items = new ArrayList<>();
        if (channelItem == null) return items;
        ArrayList<String> names = channelItem.getChannelSourceNames();
        int count = channelItem.getSourceNum();
        for (int i = 0; i < count; i++) {
            LiveSettingItem item = new LiveSettingItem();
            item.setItemIndex(i);
            String name = (names != null && i < names.size() && names.get(i) != null && !names.get(i).isEmpty())
                    ? names.get(i) : ("线路" + (i + 1));
            item.setItemName(name);
            items.add(item);
        }
        return items;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_live_line_select;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        bind(findViewById(R.id.mLineView), mHost, this::dismiss);
    }
}
