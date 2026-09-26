package com.github.tvbox.osc.ui.dialog;

import android.view.View;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.LiveConfig;

import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 直播设置面板协调器（合并 LiveSettingDialog / LiveSettingRightDialog 的重复实现）。
 * <p>
 * 分组:画面比例 / 播放解码 / 超时换源 / 偏好设置。
 * 线路选择已从设置面板移除,改为在非全屏点击线路文字或全屏控制条点击线路信息,
 * 弹出独立线路抽屉进行切换。
 * <p>
 * 宿主能力经 {@link LiveSettingHost} 注入,不依赖具体 Activity 类型。
 */
final class LiveSettingPanel {

    private final LiveSettingHost mHost;
    private final TvRecyclerView mGroupView;
    private final TvRecyclerView mItemView;
    private final LiveSettingGroupAdapter groupAdapter = new LiveSettingGroupAdapter();
    private final LiveSettingItemAdapter itemAdapter = new LiveSettingItemAdapter();
    private final List<LiveSettingGroup> groups = new ArrayList<>();

    LiveSettingPanel(LiveSettingHost host, TvRecyclerView groupView, TvRecyclerView itemView) {
        mHost = host;
        mGroupView = groupView;
        mItemView = itemView;
    }

    /** 绑定两个列表并初始化全部交互（弹窗 onCreate 调用一次即可） */
    void init() {
        initGroupView();
        initItemView();
        buildStaticGroups();
        selectGroup(0);
    }

    private void initGroupView() {
        mGroupView.setHasFixedSize(true);
        mGroupView.setLayoutManager(new V7LinearLayoutManager(mGroupView.getContext(), 1, false));
        mGroupView.setAdapter(groupAdapter);
        groupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectGroup(position);
            }
        });
    }

    private void initItemView() {
        mItemView.setHasFixedSize(true);
        mItemView.setLayoutManager(new V7LinearLayoutManager(mItemView.getContext(), 1, false));
        mItemView.setAdapter(itemAdapter);
        itemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickItem(position);
            }
        });
    }

    /** 静态分组：画面比例 / 播放解码 / 超时换源 / 偏好设置 */
    private void buildStaticGroups() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList("画面比例", "播放解码", "超时换源", "偏好设置"));
        ArrayList<ArrayList<String>> items = new ArrayList<>();
        items.add(new ArrayList<>(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪")));
        items.add(new ArrayList<>(Arrays.asList("系统", "ijk硬解", "ijk软解", "exo")));
        items.add(new ArrayList<>(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s")));
        items.add(new ArrayList<>(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类")));

        groups.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup group = new LiveSettingGroup();
            group.setGroupIndex(i);
            group.setGroupName(groupNames.get(i));
            ArrayList<LiveSettingItem> groupItems = new ArrayList<>();
            for (int j = 0; j < items.get(i).size(); j++) {
                LiveSettingItem item = new LiveSettingItem();
                item.setItemIndex(j);
                item.setItemName(items.get(i).get(j));
                groupItems.add(item);
            }
            group.setLiveSettingItems(groupItems);
            groups.add(group);
        }
        // 当前值回读:超时档位与偏好开关
        groups.get(2).getLiveSettingItems().get(LiveConfig.connectTimeout()).setItemSelected(true);
        groups.get(3).getLiveSettingItems().get(0).setItemSelected(LiveConfig.showTime());
        groups.get(3).getLiveSettingItems().get(1).setItemSelected(LiveConfig.showNetSpeed());
        groups.get(3).getLiveSettingItems().get(2).setItemSelected(LiveConfig.channelReverse());
        groups.get(3).getLiveSettingItems().get(3).setItemSelected(LiveConfig.crossGroup());
        groupAdapter.setNewData(groups);
    }

    /** 切换分组：更新选中并让条目列回读该组当前值 */
    void selectGroup(int position) {
        if (position == groupAdapter.getSelectedGroupIndex() || position < 0) {
            return;
        }
        groupAdapter.setSelectedGroupIndex(position);
        itemAdapter.setNewData(groups.get(position).getLiveSettingItems());
        switch (position) {
            case 0:
                itemAdapter.selectItem(mHost.getLivePlayerScale(), true, true);
                break;
            case 1:
                itemAdapter.selectItem(mHost.getLivePlayerType(), true, true);
                break;
        }
        int scrollTo = itemAdapter.getSelectedItemIndex();
        if (scrollTo < 0) scrollTo = 0;
        mItemView.scrollToPosition(scrollTo);
    }

    /** 条目点击：动作分发 */
    private void clickItem(int position) {
        int groupIndex = groupAdapter.getSelectedGroupIndex();
        if (groupIndex < 3) { // 渲染类分组先画选中态（防连点）
            if (position == itemAdapter.getSelectedItemIndex()) {
                return;
            }
            itemAdapter.selectItem(position, true, true);
        }
        switch (groupIndex) {
            case 0: // 画面比例
                mHost.changeScale(position);
                break;
            case 1: // 播放解码
                mHost.changePlayer(position);
                break;
            case 2: // 超时换源
                LiveConfig.setConnectTimeout(position);
                break;
            case 3: // 偏好设置
                boolean select = false;
                switch (position) {
                    case 0:
                        select = !LiveConfig.showTime();
                        LiveConfig.setShowTime(select);
                        break;
                    case 1:
                        select = !LiveConfig.showNetSpeed();
                        LiveConfig.setShowNetSpeed(select);
                        break;
                    case 2:
                        select = !LiveConfig.channelReverse();
                        LiveConfig.setChannelReverse(select);
                        break;
                    case 3:
                        select = !LiveConfig.crossGroup();
                        LiveConfig.setCrossGroup(select);
                        break;
                }
                itemAdapter.selectItem(position, select, false);
                mHost.refreshPreferenceUi(); // 显示时间/显示网速等即时作用于全屏与小窗控制条
                break;
        }
    }
}
