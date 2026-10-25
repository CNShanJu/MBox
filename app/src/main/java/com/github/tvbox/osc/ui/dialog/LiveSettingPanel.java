package com.github.tvbox.osc.ui.dialog;

import android.view.View;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.util.BackgroundPlaySettings;
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
 * 分组:直播源 / 画面比例 / 播放解码 / 超时换源 / 偏好设置 / 后台播放。
 * 「直播源」组是动态的(跟随订阅 + 订阅自带 + 用户历史),打开抽屉默认聚焦它 ——
 * 换源是直播页的高频动作;首页换源弹窗不展示直播源,切换统一在这里做。
 * <p>
 * 线路选择已从设置面板移除,改为在非全屏点击线路文字或全屏控制条点击线路信息,
 * 弹出独立线路抽屉进行切换。
 * <p>
 * 宿主能力经 {@link LiveSettingHost} 注入,不依赖具体 Activity 类型。
 */
final class LiveSettingPanel {

    private static final int GROUP_LIVE_SOURCE = 0;
    private static final int GROUP_SCALE = 1;
    private static final int GROUP_PLAYER = 2;
    private static final int GROUP_TIMEOUT = 3;
    private static final int GROUP_PREFERENCE = 4;
    private static final int GROUP_BACKGROUND = 5;

    private final LiveSettingHost mHost;
    private final TvRecyclerView mGroupView;
    private final TvRecyclerView mItemView;
    private final LiveSettingGroupAdapter groupAdapter = new LiveSettingGroupAdapter();
    private final LiveSettingItemAdapter itemAdapter = new LiveSettingItemAdapter();
    private final List<LiveSettingGroup> groups = new ArrayList<>();
    /** 「直播源」组的地址(与条目一一对应;下标 0 = 空串 = 跟随订阅) */
    private final List<String> liveSourceUrls = new ArrayList<>();

    LiveSettingPanel(LiveSettingHost host, TvRecyclerView groupView, TvRecyclerView itemView) {
        mHost = host;
        mGroupView = groupView;
        mItemView = itemView;
    }

    /** 绑定两个列表并初始化全部交互（弹窗 onCreate 调用一次即可） */
    void init() {
        initGroupView();
        initItemView();
        buildGroups();
        selectGroup(GROUP_LIVE_SOURCE);
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

    /** 分组:直播源(动态) / 画面比例 / 播放解码 / 超时换源 / 偏好设置 / 后台播放 */
    private void buildGroups() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList(
                "直播源", "画面比例", "播放解码", "超时换源", "偏好设置", "后台播放"));
        ArrayList<ArrayList<String>> items = new ArrayList<>();
        items.add(new ArrayList<>(liveSourceItemNames()));
        items.add(new ArrayList<>(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪")));
        items.add(new ArrayList<>(Arrays.asList("系统", "Media3")));
        items.add(new ArrayList<>(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s")));
        items.add(new ArrayList<>(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类")));
        items.add(new ArrayList<>(BackgroundPlaySettings.MODES));

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
        groups.get(GROUP_TIMEOUT).getLiveSettingItems().get(LiveConfig.connectTimeout()).setItemSelected(true);
        groups.get(GROUP_PREFERENCE).getLiveSettingItems().get(0).setItemSelected(LiveConfig.showTime());
        groups.get(GROUP_PREFERENCE).getLiveSettingItems().get(1).setItemSelected(LiveConfig.showNetSpeed());
        groups.get(GROUP_PREFERENCE).getLiveSettingItems().get(2).setItemSelected(LiveConfig.channelReverse());
        groups.get(GROUP_PREFERENCE).getLiveSettingItems().get(3).setItemSelected(LiveConfig.crossGroup());
        groupAdapter.setNewData(groups);
    }

    // ------------------------------------------------------------------
    // 「直播源」组(动态条目 + 当前值)
    // ------------------------------------------------------------------

    /** 「直播源」组条目名:跟随订阅 + 订阅自带 + 用户历史 */
    private List<String> liveSourceItemNames() {
        liveSourceUrls.clear();
        liveSourceUrls.add("");   // 下标 0 = 跟随订阅
        List<String> names = new ArrayList<>();
        names.add("跟随订阅");
        for (LiveSettingHost.LiveSourceChoice choice : mHost.getLiveSourceChoices()) {
            liveSourceUrls.add(choice.url);
            names.add(choice.name);
        }
        return names;
    }

    /** 当前生效直播源在组里的下标;空 = 跟随订阅(0);自定义地址不在清单里 = -1 */
    private int currentLiveSourceIndex() {
        String current = SystemConfig.getLiveUrl();
        if (current == null || current.isEmpty()) return 0;
        for (int i = 0; i < liveSourceUrls.size(); i++) {
            if (current.equals(liveSourceUrls.get(i))) return i;
        }
        return -1;
    }

    /** 切换分组：更新选中并让条目列回读该组当前值 */
    void selectGroup(int position) {
        if (position == groupAdapter.getSelectedGroupIndex() || position < 0) {
            return;
        }
        groupAdapter.setSelectedGroupIndex(position);
        itemAdapter.setNewData(groups.get(position).getLiveSettingItems());
        switch (position) {
            case GROUP_LIVE_SOURCE:
                itemAdapter.selectItem(currentLiveSourceIndex(), true, true);
                break;
            case GROUP_SCALE:
                itemAdapter.selectItem(mHost.getLivePlayerScale(), true, true);
                break;
            case GROUP_PLAYER:
                itemAdapter.selectItem(mHost.getLivePlayerType(), true, true);
                break;
            case GROUP_BACKGROUND:
                itemAdapter.selectItem(mHost.getBackgroundPlayType(), true, true);
                break;
        }
        int scrollTo = itemAdapter.getSelectedItemIndex();
        if (scrollTo < 0) scrollTo = 0;
        mItemView.scrollToPosition(scrollTo);
    }

    /** 条目点击：动作分发 */
    private void clickItem(int position) {
        int groupIndex = groupAdapter.getSelectedGroupIndex();
        // 偏好组是多选开关,其余组都是单选:重复点当前项不动作(防连点重载)
        if (groupIndex != GROUP_PREFERENCE) {
            if (position == itemAdapter.getSelectedItemIndex()) {
                return;
            }
            itemAdapter.selectItem(position, true, true);
        }
        switch (groupIndex) {
            case GROUP_LIVE_SOURCE: // 直播源:切换后由宿主就地重载频道列表
                String url = position < liveSourceUrls.size() ? liveSourceUrls.get(position) : "";
                mHost.changeLiveSource(url);
                break;
            case GROUP_SCALE: // 画面比例
                mHost.changeScale(position);
                break;
            case GROUP_PLAYER: // 播放解码
                mHost.changePlayer(position);
                break;
            case GROUP_TIMEOUT: // 超时换源
                LiveConfig.setConnectTimeout(position);
                break;
            case GROUP_PREFERENCE: // 偏好设置
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
            case GROUP_BACKGROUND: // 后台播放与另外两种播放场景共用 PlayConfig
                if (position != mHost.getBackgroundPlayType()) {
                    mHost.setBackgroundPlayType(position);
                    itemAdapter.selectItem(position, true, true);
                }
                break;
        }
    }
}
