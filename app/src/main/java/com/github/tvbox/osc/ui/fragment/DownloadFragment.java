package com.github.tvbox.osc.ui.fragment;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.SPUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.databinding.FragmentDownloadBinding;
import com.github.tvbox.osc.constant.CacheConst;
import com.github.tvbox.osc.download.DownloadFacade;
import com.github.tvbox.osc.state.StorageSpace;
import com.github.tvbox.osc.util.DownloadDisplay;
import com.github.tvbox.osc.util.DownloadGrouping;
import com.github.tvbox.osc.util.PicassoLoad;
import com.github.tvbox.osc.ui.activity.LocalPlayActivity;
import com.github.tvbox.osc.ui.adapter.LocalVideoAdapter;
import com.github.tvbox.osc.ui.dialog.ConfirmDialog;
import com.github.tvbox.osc.ui.dialog.DeleteDownloadDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.kit.SelectActionBar;
import com.github.tvbox.osc.util.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载页(三 box 布局):
 * <ul>
 *   <li>标题 box:DownloadActivity 标题栏(返回 + 下载管理 + 设置齿轮)</li>
 *   <li>下载相关 box:内容超出内部滚动。
 *       聚合根级为剧集网格(收藏页样式,点击进入剧集详情状态页,长按圆形多选);
 *       详情页内为 正在下载/下载完成 两个 tab(该剧任务列表 / 该剧文件列表)。</li>
 *   <li>控件 box:全选/删除/取消全选(聚合与详情共用),下载中详情另有全部暂停/全部开始;
 *       默认隐藏,长按多选时显示,吸底效果。</li>
 *   <li>显示内存和其他信息 box:底部,文字居中。</li>
 * </ul>
 * 路径展示(顶部):仅剧集详情状态页显示,只展示 片名 · 来源。
 */
public class DownloadFragment extends BaseVbFragment<FragmentDownloadBinding> {

    private static final int TAB_DOWNLOADING = 0;
    private static final int TAB_DONE = 1;
    /** 左滑露出的操作区宽度(暂停+删除两个按钮,= 布局里两个 64dp 按钮之和) */
    private static final int SWIPE_REVEAL_WIDTH_DP = 128;
    /** 左滑松手后的吸附动画时长(卡片位移与操作区宽度同一动画驱动) */
    private static final int SWIPE_SETTLE_MS = 150;
    /** bindPoster 去重 tag key:ImageView 上记录"已发起懒拉取的 pic"(本地海报文件的去重在 PicassoLoad 内按路径记) */
    private static final int TAG_POSTER_SOURCE = 0x2D00001;
    /** 行内左滑吸附动画 tag key:item_front 上挂当前动画,重绑/新手势前先取消,避免旧动画改写新状态 */
    private static final int TAG_SWIPE_ANIM = 0x2D00002;

    // ------------------------------------------------------------------
    // 聚合根级:剧集网格(收藏页样式,按 剧名+来源 分组)
    // ------------------------------------------------------------------
    private BaseQuickAdapter<DownloadGrouping.Group, BaseViewHolder> aggregateAdapter;
    private boolean aggSelectMode = false;
    /** 聚合多选选中的分组 key(来源+剧名) */
    private final Set<String> selectedAggKeys = new LinkedHashSet<>();

    // ------------------------------------------------------------------
    // 剧集详情状态页(点击聚合卡片进入):正在下载 / 下载完成 两个 tab
    // ------------------------------------------------------------------
    /** 当前剧集名(非空=详情页) */
    private String currentVodGroup = null;
    /** 当前剧集来源(路径展示用) */
    private String currentSourceName = null;
    private int currentTab = TAB_DOWNLOADING;
    /** 该剧正在下载任务列表 */
    private BaseQuickAdapter<DownloadTask, BaseViewHolder> downloadingAdapter;
    private LocalVideoAdapter localVideoAdapter;
    /** 详情页下载中多选 */
    private boolean dlSelectMode = false;
    private final Set<String> selectedTaskIds = new LinkedHashSet<>();
    private int mSelectedCount = 0;
    /** 操作栏上的"删除"键:可用态随选中数变化(取色走组件 SelectActionBar) */
    private TextView mDeleteAction;
    /** 窄屏左滑已滑出的任务 id(下载中条目操作区保持展开,刷新重建后恢复) */
    private final Set<String> swipedTaskIds = new LinkedHashSet<>();
    /** 左滑操作区宽度缓存(px,<0 表示未计算) */
    private float swipeRevealPxCache = -1;

    // 聚合分组模型 + 分组/归属逻辑已抽到 util/DownloadGrouping(纯数据,可 JVM 测)

    @Override
    protected void init() {
        // 聚合网格列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mBinding.rvAggregate.setLayoutManager(new GridLayoutManager(mContext, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)));
        mBinding.rvDownloading.setLayoutManager(new LinearLayoutManager(mContext));
        mBinding.rvDone.setLayoutManager(new LinearLayoutManager(mContext));

        mBinding.tvTabDownloading.setOnClickListener(v -> switchTab(TAB_DOWNLOADING));
        mBinding.tvTabDone.setOnClickListener(v -> switchTab(TAB_DONE));

        // 顶部路径展示:点按返回聚合根级
        mBinding.llNav.setOnClickListener(v -> onBackPressed());

        // 控件 box(吸底):全部暂停/全部开始(仅详情下载中多选)留在卡片里;
        // 全选/删除/取消全选走公共组件 select_action_bar(与本地视频页同一款:主题面背景 + 危险键色),
        // 它显示时占用底部"可用空间·下载配置"条的位置(见 updateToolbar)
        mBinding.btnPauseAll.setOnClickListener(v -> pauseSelected());
        mBinding.btnStartAll.setOnClickListener(v -> startSelected());
        bindSelectActions();

        // ------------------------------------------------------------------
        // 聚合根级:剧集网格(收藏页样式;海报本地文件,左上角来源徽标/圆形多选框)
        // ------------------------------------------------------------------
        aggregateAdapter = new BaseQuickAdapter<DownloadGrouping.Group, BaseViewHolder>(R.layout.item_download_vod_grid) {
            @Override
            protected void convert(@NonNull BaseViewHolder helper, DownloadGrouping.Group group) {
                bindPoster(helper.getView(R.id.ivThumb), group.name, picOf(group.name, group.sourceName));
                // 左上角:正常=来源徽标;多选=圆形勾选框(未选中外环,选中外环+内部填充圆,两圆有边距)
                boolean sel = aggSelectMode;
                helper.setVisible(R.id.layout_source, !sel);
                helper.setVisible(R.id.iv_select, sel);
                if (sel) {
                    ImageView ivSel = helper.getView(R.id.iv_select);
                    ivSel.setImageResource(selectedAggKeys.contains(group.key)
                            ? R.drawable.ic_select_checked : R.drawable.ic_select_ring);
                } else {
                    String src = group.sourceName == null || group.sourceName.isEmpty() ? "未知" : group.sourceName;
                    helper.setText(R.id.tv_source, src);
                }
                helper.setText(R.id.tv_name, group.name);
                // 聚合状态:任务数(未完成) + 已完成集数(档案表,文件存在才计)
                helper.setText(R.id.tv_note,
                        DownloadGrouping.aggregateNote(group.tasks, group.doneItems));
            }
        };
        aggregateAdapter.setOnItemClickListener((adapter, view, position) -> {
            DownloadGrouping.Group g = aggregateAdapter.getItem(position);
            if (g == null) return;
            if (aggSelectMode) {
                toggleSet(selectedAggKeys, g.key);
                aggregateAdapter.notifyDataSetChanged();
                updateToolbar();
            } else {
                enterDetail(g);
            }
        });
        aggregateAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            DownloadGrouping.Group g = aggregateAdapter.getItem(position);
            if (g == null) return false;
            if (!aggSelectMode) aggSelectMode = true;
            selectedAggKeys.add(g.key);
            aggregateAdapter.notifyDataSetChanged();
            updateToolbar();
            return true;
        });
        mBinding.rvAggregate.setAdapter(aggregateAdapter);

        // ------------------------------------------------------------------
        // 剧集详情状态页:正在下载(该剧任务列表;多选只作用于选中条目)
        // ------------------------------------------------------------------
        downloadingAdapter = new BaseQuickAdapter<DownloadTask, BaseViewHolder>(R.layout.item_download_task_new) {
            @Override
            protected void convert(@NonNull BaseViewHolder helper, DownloadTask task) {
                // 封面图:本地海报文件(缺失显示搜索页同款占位图并懒拉取);同源去重,状态刷新整行重绑不再闪图
                bindPoster(helper.getView(R.id.iv_cover), DownloadGrouping.vodNameOf(task), task.pic);
                // 行1:剧名 · 集名(拼装抽到 DownloadDisplay)
                helper.setText(R.id.tv_name, DownloadDisplay.titleText(task));
                // 行2/行3:状态·进度(含网速)与 大小·进度(与进度事件局部刷新共用同一绑定)
                bindProgressTexts(helper, task);
                // 行4:来源 · 存储位置
                helper.setText(R.id.tv_source, DownloadDisplay.sourceText(task.sourceName));
                // 操作按钮:统一左滑滑出(不区分大小屏,宽屏同样滑出右侧 暂停/删除 操作区)
                View swipeBehind = helper.getView(R.id.swipe_behind);
                View front = helper.getView(R.id.item_front);
                cancelSwipeAnim(front); // 复用条目:先停掉上一次吸附动画,别让它改写这一行的新状态
                if (dlSelectMode) {
                    swipeBehind.setVisibility(View.GONE);
                    front.setTranslationX(0);
                    setSwipeBehindWidth(swipeBehind, 0);
                    front.setOnTouchListener(null);
                } else {
                    swipeBehind.setVisibility(View.VISIBLE);
                    TextView btnSwipe = helper.getView(R.id.btn_swipe_pause);
                    btnSwipe.setText(DownloadDisplay.swipeActionText(task));
                    float tx = swipedTaskIds.contains(task.id) ? -swipeRevealPx() : 0;
                    front.setTranslationX(tx);
                    // 操作区只保留"已滑出的那一段"宽度:信息卡是半透明的(主题 bg_card alpha),
                    // 整条铺在卡片下面会被透出来(见 item_download_task_new.xml 顶部说明)
                    setSwipeBehindWidth(swipeBehind, -tx);
                    attachSwipe(front, swipeBehind, task);
                    helper.addOnClickListener(R.id.btn_swipe_pause, R.id.btn_swipe_delete);
                }
                // 多选勾选框:仅长按多选时显示
                CheckBox cb = helper.getView(R.id.cb);
                cb.setVisibility(dlSelectMode ? View.VISIBLE : View.GONE);
                cb.setChecked(selectedTaskIds.contains(task.id));
            }
        };
        downloadingAdapter.setOnItemClickListener((adapter, view, position) -> {
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return;
            DownloadTask t = data.get(position);
            if (dlSelectMode) {
                toggleSet(selectedTaskIds, t.id);
                downloadingAdapter.notifyDataSetChanged();
                updateToolbar();
            } else {
                // 点击切换:暂停/失败 -> 开始下载;下载中/等待/排队 -> 暂停
                toggleTaskPlay(t);
            }
        });
        downloadingAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return false;
            DownloadTask t = data.get(position);
            if (!dlSelectMode) dlSelectMode = true;
            selectedTaskIds.add(t.id);
            downloadingAdapter.notifyDataSetChanged();
            updateToolbar();
            return true;
        });
        downloadingAdapter.setOnItemChildClickListener((adapter, view, position) -> {
            if (dlSelectMode) return;
            List<DownloadTask> data = downloadingAdapter.getData();
            if (position < 0 || position >= data.size()) return;
            DownloadTask t = data.get(position);
            int id = view.getId();
            if (id == R.id.btn_swipe_pause) {
                if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED
                        || t.state == DownloadTask.STATE_NETWORK_PAUSED) {
                    if (blockedByWifiOnly()) return;
                    DownloadFacade.get().resume(t);
                } else {
                    DownloadFacade.get().pause(t);
                }
                // 左滑操作区点击后收起
                swipedTaskIds.remove(t.id);
                downloadingAdapter.notifyDataSetChanged();
            } else if (id == R.id.btn_swipe_delete) {
                // 删除:确认后记录+过程文件一起删,收起并刷新
                swipedTaskIds.remove(t.id);
                downloadingAdapter.notifyDataSetChanged();
                DialogCoordinator.centerDark(mContext, new ConfirmDialog(mContext,
                        "删除任务",
                        "将删除该任务及其未完成的下载文件,确定?",
                        "删除",
                        () -> {
                            DownloadFacade.get().remove(t, true);
                            refresh();
                        }, true))
                        .show();
            }
        });
        mBinding.rvDownloading.setAdapter(downloadingAdapter);

        // ------------------------------------------------------------------
        // 剧集详情状态页:下载完成(该剧文件列表,复用本地视频 adapter)
        // ------------------------------------------------------------------
        localVideoAdapter = new LocalVideoAdapter();
        localVideoAdapter.setOnItemClickListener((adapter, view, position) -> {
            VideoInfo info = localVideoAdapter.getItem(position);
            if (info == null) return;
            if (localVideoAdapter.isSelectMode()) {
                // 走适配器勾选入口:计数增量维护 + 只刷新该行
                localVideoAdapter.setItemChecked(info, !info.isChecked());
            } else {
                playFile(info);
            }
        });
        localVideoAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            VideoInfo info = localVideoAdapter.getItem(position);
            if (info != null) {
                if (!localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(true);
                localVideoAdapter.setItemChecked(info, true);
                updateToolbar();
            }
            return true;
        });
        localVideoAdapter.setOnSelectCountListener(count -> {
            mSelectedCount = count;
            updateToolbar();
        });
        mBinding.rvDone.setAdapter(localVideoAdapter);

        refresh();
    }

    /** 下载结构变更(全量刷新)与任务进度(局部刷新)经 DownloadFacade 订阅;仅在页面可见时挂载 */
    private boolean mDownloadEventsSubscribed = false;

    private final DownloadFacade.DownloadStatusListener mDownloadStatusListener = this::refresh;
    private final DownloadFacade.TaskProgressListener mTaskProgressListener = this::onTaskProgress;

    private void subscribeDownloadEvents() {
        if (mDownloadEventsSubscribed) return;
        DownloadFacade.get().register(mDownloadStatusListener);
        DownloadFacade.get().registerProgress(mTaskProgressListener);
        mDownloadEventsSubscribed = true;
    }

    private void unsubscribeDownloadEvents() {
        if (!mDownloadEventsSubscribed) return;
        DownloadFacade.get().unregister(mDownloadStatusListener);
        DownloadFacade.get().unregisterProgress(mTaskProgressListener);
        mDownloadEventsSubscribed = false;
    }

    @Override
    public void onStart() {
        super.onStart();
        subscribeDownloadEvents();
    }

    @Override
    public void onStop() {
        super.onStop();
        unsubscribeDownloadEvents();
    }

    /** 返回键处理:详情页先退多选再退聚合,聚合根级退多选后返回 false */
    public boolean onBackPressed() {
        if (currentVodGroup != null) { // 剧集详情状态页
            if (currentTab == TAB_DOWNLOADING && dlSelectMode) {
                if (!selectedTaskIds.isEmpty()) {
                    cancelAllChecked();
                } else {
                    dlSelectMode = false;
                    downloadingAdapter.notifyDataSetChanged();
                    updateToolbar();
                }
                return true;
            }
            if (currentTab == TAB_DONE && localVideoAdapter.isSelectMode()) {
                if (mSelectedCount > 0) {
                    cancelAllChecked();
                } else {
                    localVideoAdapter.setSelectMode(false);
                    updateToolbar();
                }
                return true;
            }
            backToAggregate();
            return true;
        }
        // 聚合根级
        if (aggSelectMode) {
            if (!selectedAggKeys.isEmpty()) {
                cancelAllChecked();
            } else {
                aggSelectMode = false;
                aggregateAdapter.notifyDataSetChanged();
                updateToolbar();
            }
            return true;
        }
        return false;
    }

    /** 任务级进度回调(带任务 id,高频,由 DownloadManager.flushProgress 节流后经 Facade 转发):
     * 只对该任务在"正在下载"列表中的可见条目做文本级局部刷新,不整行 notifyItemChanged 重绑——
     * 避免每次 HLS 分片进度都重新聚合全部任务/检查文件/setNewData(任务 B),也避免重绑时
     * 重新发起海报加载导致图片闪动。UI 状态(展开/多选/勾选/滑动)均由任务对象与 adapter 字段
     * 驱动,文本就地更新不影响它们。 */
    private void onTaskProgress(String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        // 底部"可用空间/设置"条:进度期间磁盘占用持续变化,轻量刷新(StatFs 开销极小)
        updateStorageText();
        // 聚合根级卡片只展示 任务数/已完成集数,不含进度百分比/网速 → 进度事件无需刷聚合
        if (currentVodGroup == null) return;
        // 只有"正在下载"tab 的该剧任务行展示进度
        if (currentTab != TAB_DOWNLOADING) return;
        List<DownloadTask> data = downloadingAdapter.getData();
        if (data == null || data.isEmpty()) return;
        for (int i = 0; i < data.size(); i++) {
            DownloadTask t = data.get(i);
            if (t != null && taskId.equals(t.id)) {
                refreshProgressRowTexts(i);
                return;
            }
        }
        // 任务不属于当前剧集分组(未找到行)或已不在该列表:无需任何操作
    }

    /** 进度事件就地刷新指定行的动态文本(状态行含网速 + 大小·进度行),不动图片/布局/手势等;
     * 行不可见或列表已重排导致 holder 不再对应目标任务时放弃(该行下次绑定会按最新状态重绘)。 */
    private void refreshProgressRowTexts(int position) {
        List<DownloadTask> data = downloadingAdapter.getData();
        if (data == null || position < 0 || position >= data.size()) return;
        DownloadTask t = data.get(position);
        if (t == null) return;
        RecyclerView.ViewHolder vh = mBinding.rvDownloading.findViewHolderForAdapterPosition(position);
        if (vh == null) return; // 行不可见(滚出屏外):下次绑定即读到最新内存值
        if (vh.getBindingAdapterPosition() != position) return; // 事件与列表重排竞态:放弃就地刷新
        bindProgressTexts((BaseViewHolder) vh, t);
    }

    /** 绑定"正在下载"行的动态文本(行2 状态·进度含网速 + 行3 大小·进度;失败附原因);
     * 整行重绑(convert)与进度事件局部刷新共用;文本/颜色无变化时不重设,避免无谓重排。 */
    private void bindProgressTexts(BaseViewHolder helper, DownloadTask task) {
        TextView tvStatus = helper.getView(R.id.tv_status);
        String statusLine = DownloadDisplay.statusLine(task);
        if (statusLine != null && !statusLine.contentEquals(tvStatus.getText())) {
            tvStatus.setText(statusLine);
        }
        int statusColor;
        switch (DownloadDisplay.statusToneOf(task)) {
            case ERROR:
                statusColor = ContextCompat.getColor(mContext, R.color.red);
                break;
            case MUTED:
                statusColor = ContextCompat.getColor(mContext, R.color.text_sub_foreground);
                break;
            default:
                statusColor = ContextCompat.getColor(mContext, R.color.download_active);
                break;
        }
        if (tvStatus.getCurrentTextColor() != statusColor) tvStatus.setTextColor(statusColor);
        TextView tvSize = helper.getView(R.id.tv_size_speed);
        String percent = DownloadDisplay.buildPercentText(task);
        if (percent != null && !percent.contentEquals(tvSize.getText())) tvSize.setText(percent);
    }

    private void switchTab(int tab) {
        currentTab = tab;
        applyTabStyle();
        // 切 tab 时退出两页的多选
        exitDlSelectMode();
        if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        updateNavBar();
    }

    /** 刷新 tab 行的选中样式(加粗+主题色) */
    private void applyTabStyle() {
        boolean downloading = currentTab == TAB_DOWNLOADING;
        mBinding.tvTabDownloading.setTextColor(getResources().getColor(downloading ? R.color.colorPrimary : R.color.text_sub_foreground));
        mBinding.tvTabDownloading.setTextSize(16);
        mBinding.tvTabDownloading.setTypeface(null, downloading ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        mBinding.tvTabDone.setTextColor(getResources().getColor(downloading ? R.color.text_sub_foreground : R.color.colorPrimary));
        mBinding.tvTabDone.setTextSize(16);
        mBinding.tvTabDone.setTypeface(null, downloading ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    }

    private void refresh() {
        refreshAggregate();
        refreshDetailLists();
        updateTabCounts();
        updateNavBar();
        updateToolbar();
    }

    /** 刷新聚合网格(按 剧名+来源 分组,含下载中与已完成);无数据时展示空态 */
    private void refreshAggregate() {
        List<DownloadGrouping.Group> groups = buildAggregateGroups();
        aggregateAdapter.setNewData(groups);
        boolean empty = groups == null || groups.isEmpty();
        mBinding.rvAggregate.setVisibility(empty ? View.GONE : View.VISIBLE);
        mBinding.llAggregateEmpty.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 当前剧集下载完成列表的数据指纹(路径+大小),内容未变时跳过重建,避免下载进度刷新打断长按多选 */
    private String doneListSignature = "";

    /** 刷新详情页两个 tab 的列表;该剧已被删光时自动退回聚合根级 */
    private void refreshDetailLists() {
        if (currentVodGroup == null) return;
        if (!isGroupPresent(currentVodGroup, currentSourceName)) {
            backToAggregate();
            return;
        }
        downloadingAdapter.setNewData(tasksInGroup(currentVodGroup, currentSourceName));
        List<VideoInfo> files = buildFolderVideosFromRecords(currentVodGroup, currentSourceName);
        String sig = currentVodGroup + "\u0001" + (currentSourceName == null ? "" : currentSourceName)
                + "\u0001" + DownloadDisplay.doneSignature(files);
        if (sig.equals(doneListSignature)) return; // 内容未变:跳过重建(保持多选与滚动状态)
        doneListSignature = sig;        // 重建时保留多选勾选(按路径恢复),且不重置多选模式,避免刷新打断"下载完成"长按多选
        Set<String> checked = new LinkedHashSet<>();
        if (localVideoAdapter.isSelectMode()) {
            for (VideoInfo v : localVideoAdapter.getData()) {
                if (v.isChecked()) checked.add(v.getPath());
            }
        }
        if (!checked.isEmpty()) {
            for (VideoInfo v : files) {
                if (checked.contains(v.getPath())) v.setChecked(true);
            }
        }
        localVideoAdapter.setNewData(files);
        // 数据重建后同步一次选中计数(BRVAH notifyDataSetChanged 为 final,列表重建无法被计数拦截)
        localVideoAdapter.syncSelection();
    }

    /** 刷新剧集展示插槽:聚合组件(剧集网格) / 详情组件(导航+tab+两列表) 二选一换入;
     * 聚合展示时中间 box 移除背景色(收藏页风格);详情页无下载中任务时隐藏 tab 直接展示下载完成 */
    private void updateNavBar() {
        boolean inDetail = currentVodGroup != null;
        // 中间 box 背景:聚合根级透明,详情页恢复卡片背景(悬浮面,与底栏/「我的」页同一个面)。
        // 代码设底要走 ThemeDrawables.applyBackground,否则自定义主题下这张卡片永远是内置色
        com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(mBinding.llDownloadBox,
                inDetail ? R.drawable.bg_large_round_float : 0);
        // 聚合组件显隐:非详情时由 refreshAggregate 按数据是否为空设置列表/空态,此处仅处理详情态
        if (inDetail) {
            mBinding.rvAggregate.setVisibility(View.GONE);
            mBinding.llAggregateEmpty.getRoot().setVisibility(View.GONE);
        }
        mBinding.llDetail.setVisibility(inDetail ? View.VISIBLE : View.GONE);
        if (inDetail) {
            String src = currentSourceName == null || currentSourceName.isEmpty() ? "未知" : currentSourceName;
            mBinding.tvNavPath.setText(src + " · " + currentVodGroup);
            // 该剧没有下载中的任务:隐藏 正在下载/下载完成 tab,直接展示下载完成内容
            boolean hasActive = hasInProgressInGroup();
            mBinding.llTabs.setVisibility(hasActive ? View.VISIBLE : View.GONE);
            if (!hasActive && currentTab != TAB_DONE) {
                currentTab = TAB_DONE;
                applyTabStyle();
            }
            // 列表与空态联动:当前 tab 对应列表为空时展示空态(空态视图与列表二选一)
            boolean dlEmpty = downloadingAdapter.getData() == null || downloadingAdapter.getData().isEmpty();
            boolean doneEmpty = localVideoAdapter.getData() == null || localVideoAdapter.getData().isEmpty();
            mBinding.rvDownloading.setVisibility(inDetail && currentTab == TAB_DOWNLOADING && !dlEmpty ? View.VISIBLE : View.GONE);
            mBinding.llDownloadingEmpty.getRoot().setVisibility(inDetail && currentTab == TAB_DOWNLOADING && dlEmpty ? View.VISIBLE : View.GONE);
            mBinding.rvDone.setVisibility(inDetail && currentTab == TAB_DONE && !doneEmpty ? View.VISIBLE : View.GONE);
            mBinding.llDoneEmpty.getRoot().setVisibility(inDetail && currentTab == TAB_DONE && doneEmpty ? View.VISIBLE : View.GONE);
        }
        updateToolbar();
        updateStorageText();
    }

    /** 当前剧集是否存在未完成(下载中/等待/暂停/失败)的任务 */
    private boolean hasInProgressInGroup() {
        if (currentVodGroup == null) return false;
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state != DownloadTask.STATE_COMPLETED && DownloadGrouping.inGroup(t, currentVodGroup, currentSourceName)) {
                return true;
            }
        }
        return false;
    }

    /** 刷新底部"显示内存和其他信息"条(居中) */
    private void updateStorageText() {
        try {
            File dir = DownloadFacade.get().getSaveDir();
            // 可用空间统一问中控层(与下载看门狗/预检同一份测量,不再各页自己 new StatFs)
            long free = StorageSpace.freeBytes(dir);
            if (free < 0) {
                mBinding.tvStorage.setText("");
                return;
            }
            String wifi = DownloadFacade.get().isWifiOnly() ? "仅Wi-Fi" : "Wi-Fi+流量";
            mBinding.tvStorage.setText("可用 " + DownloadDisplay.formatSize(free) + "  |  " + wifi + " · 并发 " + DownloadFacade.get().getMaxConcurrent());
        } catch (Throwable th) {
            mBinding.tvStorage.setText("");
        }
    }

    /** 详情页 Tab 数量角标(该剧维度):正在下载 (N)  已完成 (M, 档案表) */
    private void updateTabCounts() {
        if (currentVodGroup == null) return;
        int downloading = 0;
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state == DownloadTask.STATE_COMPLETED) continue;
            if (DownloadGrouping.inGroup(t, currentVodGroup, currentSourceName)) downloading++;
        }
        int done = 0;
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadFacade.get().queryArchiveByVod(currentVodGroup, currentSourceName)) {
            if (it.savePath != null && new File(it.savePath).exists()) done++;
        }
        mBinding.tvTabDownloading.setText(downloading > 0 ? "正在下载 (" + downloading + ")" : "正在下载");
        mBinding.tvTabDone.setText(done > 0 ? "下载完成 (" + done + ")" : "下载完成");
    }

    // ------------------------------------------------------------------
    // 聚合根级 与 详情页 切换
    // ------------------------------------------------------------------

    private void enterDetail(DownloadGrouping.Group g) {
        currentVodGroup = g.name;
        currentSourceName = g.sourceName;
        doneListSignature = ""; // 进入新剧集,失效上一剧的列表指纹
        exitAggSelectMode();
        exitDlSelectMode();
        if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        switchTab(TAB_DOWNLOADING);
        refresh();
    }

    private void backToAggregate() {
        currentVodGroup = null;
        currentSourceName = null;
        doneListSignature = "";
        exitAggSelectMode();
        exitDlSelectMode();
        if (localVideoAdapter.isSelectMode()) localVideoAdapter.setSelectMode(false);
        refresh();
    }

    private void exitAggSelectMode() {
        aggSelectMode = false;
        selectedAggKeys.clear();
        aggregateAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    private void exitDlSelectMode() {
        dlSelectMode = false;
        selectedTaskIds.clear();
        downloadingAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    // ------------------------------------------------------------------
    // 控件 box(吸底):全选/删除/取消全选 + 全部暂停/全部开始(仅详情下载中)
    // ------------------------------------------------------------------

    /** 刷新控件 box:可见性、全部暂停/全部开始可用态、删除按钮颜色(启用=危险红,深浅主题均清晰) */
    private void updateToolbar() {
        boolean inDetail = currentVodGroup != null;
        boolean aggSel = !inDetail && aggSelectMode;
        boolean dlSel = inDetail && currentTab == TAB_DOWNLOADING && dlSelectMode;
        boolean doneSel = inDetail && currentTab == TAB_DONE && localVideoAdapter.isSelectMode();
        boolean show = aggSel || dlSel || doneSel;
        // 操作栏与底部"可用空间·下载配置"条**共用同一格**:多选时把那条临时顶掉(两者互斥显示)
        mBinding.selectActionBar.setVisibility(show ? View.VISIBLE : View.GONE);
        mBinding.llStorageBox.setVisibility(show ? View.GONE : View.VISIBLE);
        mBinding.llToolbarActions.setVisibility(dlSel ? View.VISIBLE : View.GONE);
        if (dlSel) {
            boolean canPause = false;
            boolean canStart = false;
            for (DownloadTask t : currentDlScope()) {
                if (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_WAITING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    canPause = true;
                } else if (t.state == DownloadTask.STATE_PAUSED || t.state == DownloadTask.STATE_FAILED) {
                    canStart = true;
                }
            }
            mBinding.btnPauseAll.setEnabled(canPause);
            mBinding.btnStartAll.setEnabled(canStart);
        }
        boolean hasSel = aggSel ? !selectedAggKeys.isEmpty()
                : dlSel ? !currentDlScope().isEmpty()
                : doneSel && mSelectedCount > 0;
        // 没有选中项时"删除"置灰:可用态只报"能不能点",取色由组件按 Kind(text_danger/disable)决定
        mBinding.selectActionBar.setActionEnabled(mDeleteAction, hasSel);
    }

    /**
     * 装配长按多选操作栏上的三个动作键(公共组件 ui/kit/SelectActionBar)。
     * 三个键在"聚合级/下载中/下载完成"三种上下文里共用一份,点击时按当前上下文分派 ——
     * 与旧布局里三个共享 TextView 的分工完全一致,只是样式(主题面背景/危险键色/可用态)收敛进了组件。
     */
    private void bindSelectActions() {
        mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL, v -> selectAllChecked());
        mDeleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER, v -> {
            if (currentVodGroup == null) {
                deleteSelectedAggregates();
            } else if (currentTab == TAB_DOWNLOADING) {
                deleteSelectedDownloading();
            } else {
                deleteChecked();
            }
        });
        mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL, v -> cancelAllChecked());
        mBinding.selectActionBar.setActionEnabled(mDeleteAction, false);
    }

    /** 全选:聚合=全部剧集,详情下载中=该剧全部任务,详情下载完成=全部文件 */
    private void selectAllChecked() {
        if (currentVodGroup == null) {
            for (DownloadGrouping.Group g : aggregateAdapter.getData()) selectedAggKeys.add(g.key);
            aggregateAdapter.notifyDataSetChanged();
        } else if (currentTab == TAB_DOWNLOADING) {
            for (DownloadTask t : tasksInGroup(currentVodGroup, currentSourceName)) selectedTaskIds.add(t.id);
            downloadingAdapter.notifyDataSetChanged();
        } else {
            localVideoAdapter.selectAll();
        }
        updateToolbar();
    }

    /** 取消全选:清空当前层级的选择 */
    private void cancelAllChecked() {
        if (currentVodGroup == null) {
            selectedAggKeys.clear();
            aggregateAdapter.notifyDataSetChanged();
        } else if (currentTab == TAB_DOWNLOADING) {
            selectedTaskIds.clear();
            downloadingAdapter.notifyDataSetChanged();
        } else {
            localVideoAdapter.cancelAllSelection();
        }
        updateToolbar();
    }

    /** 当前详情下载中多选作用域:该剧内选中的任务 */
    private List<DownloadTask> currentDlScope() {
        List<DownloadTask> scope = new ArrayList<>();
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.state == DownloadTask.STATE_COMPLETED) continue;
            if (DownloadGrouping.inGroup(t, currentVodGroup, currentSourceName) && selectedTaskIds.contains(t.id)) {
                scope.add(t);
            }
        }
        return scope;
    }

    /** 全部暂停(仅详情下载中多选):作用于作用域内下载中/等待中/排队中的任务 */
    private void pauseSelected() {
        List<DownloadTask> scope = currentDlScope();
        int n = 0;
        for (DownloadTask t : scope) {
            if (t.state == DownloadTask.STATE_DOWNLOADING
                    || t.state == DownloadTask.STATE_WAITING
                    || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                DownloadFacade.get().pause(t);
                n++;
            }
        }
        if (n > 0) AppBubble.toast("已暂停 " + n + " 个任务");
    }

    /** 全部开始(仅详情下载中多选):作用于作用域内已暂停/失败/网络中断的任务 */
    private void startSelected() {
        if (blockedByWifiOnly()) return;
        List<DownloadTask> scope = currentDlScope();
        int n = 0;
        for (DownloadTask t : scope) {
            if (t.state == DownloadTask.STATE_PAUSED
                    || t.state == DownloadTask.STATE_FAILED
                    || t.state == DownloadTask.STATE_NETWORK_PAUSED) {
                DownloadFacade.get().resume(t);
                n++;
            }
        }
        if (n > 0) AppBubble.toast("已开始 " + n + " 个任务");
    }

    /**
     * 删除选中的聚合剧集(带"同时删除本地文件"勾选框):
     * 勾选 = 下载完成 + 下载中的记录与本地文件一起删(整个文件夹);
     * 不勾选 = 只删下载中的任务(记录+过程文件),保留已完成记录与文件。
     */
    private void deleteSelectedAggregates() {
        List<DownloadGrouping.Group> sel = new ArrayList<>();
        for (DownloadGrouping.Group g : aggregateAdapter.getData()) {
            if (selectedAggKeys.contains(g.key)) sel.add(g);
        }
        if (sel.isEmpty()) return;
        DialogCoordinator.centerDark(mContext, new DeleteDownloadDialog(mContext, deleteFiles -> {
                    for (DownloadGrouping.Group g : sel) {
                        if (deleteFiles) {
                            // 勾选:全部删除,记录 + 整个文件夹(该来源下该剧名目录)。
                            // 注意: 聚合组的 g.tasks 只含运行态任务(COMPLETED 被分组逻辑跳过,已完成集在
                            // g.doneItems)——目录必须从两边收集,否则"全剧已下载完"的组 g.tasks 为空,
                            // 文件夹永远删不掉, 只清档案记录而本地文件残留。
                            Set<File> dirs = new LinkedHashSet<>();
                            for (DownloadTask t : g.tasks) {
                                if (t.savePath != null) {
                                    File p = new File(t.savePath).getParentFile();
                                    if (p != null) dirs.add(p);
                                }
                                DownloadFacade.get().remove(t, false);
                            }
                            for (com.github.tvbox.osc.download.ArchiveItem it : g.doneItems) {
                                if (it.savePath != null) {
                                    File p = new File(it.savePath).getParentFile();
                                    if (p != null) dirs.add(p);
                                }
                            }
                            for (File d : dirs) {
                                DownloadDisplay.deleteRecursive(d);
                            }
                            // 已完成集(档案表): 文件已随文件夹删除, 同步删档案记录;
                            // deleteFile=true 兜底: 文件夹删除失败(权限/占用)时再尝试删单个文件
                            for (com.github.tvbox.osc.download.ArchiveItem it : g.doneItems) {
                                if (it.episodeId != null) {
                                    com.github.tvbox.osc.download.DownloadFacade.get().deleteArchive(it.episodeId, true);
                                }
                            }
                        } else {
                            // 不勾选:只删下载中的任务(记录 + 过程文件),已完成记录与文件保留
                            for (DownloadTask t : g.tasks) {
                                if (t.state != DownloadTask.STATE_COMPLETED) {
                                    DownloadFacade.get().remove(t, true);
                                }
                            }
                        }
                    }
                    exitAggSelectMode();
                    refresh();
                }))
                .show();
    }

    /** 删除选中的下载中任务(详情页):确认后记录+过程文件一起删,并清理可能变空的剧名目录 */
    private void deleteSelectedDownloading() {
        List<DownloadTask> scope = new ArrayList<>(currentDlScope());
        if (scope.isEmpty()) return;
        DialogCoordinator.centerDark(mContext, new ConfirmDialog(mContext,
                "删除任务",
                "确定删除选中的 " + scope.size() + " 个任务?",
                "删除",
                () -> {
                    Set<File> dirs = new LinkedHashSet<>();
                    for (DownloadTask t : scope) {
                        if (t.savePath != null) {
                            File p = new File(t.savePath).getParentFile();
                            if (p != null) dirs.add(p);
                        }
                        DownloadFacade.get().remove(t, true);
                    }
                    // 组内任务清空后,删掉空的剧名目录
                    for (File d : dirs) {
                        File[] fs = d.listFiles();
                        if (fs != null && fs.length == 0) d.delete();
                    }
                    exitDlSelectMode();
                    refresh();
                }, true))
        .show();
    }

    /** 删除选中的下载完成文件(详情页):确认后按"同时删除本地文件"决定 */
    private void deleteChecked() {
        DialogCoordinator.centerDark(mContext, new DeleteDownloadDialog(mContext, deleteFiles -> {
            List<VideoInfo> data = new ArrayList<>(localVideoAdapter.getData());
            for (VideoInfo item : data) {
                if (item.isChecked()) {
                    removeTaskAndFile(item.getPath(), deleteFiles);
                }
            }
            localVideoAdapter.setSelectMode(false);
            updateToolbar();
            refresh();
        }))
        .show();
    }

    /**
     * 删除下载:找到对应任务记录移除;deleteFiles=true 连本地文件一起删,false 只删记录保留文件。
     * 仅在用户确认删除后调用(DeleteDownloadDialog 确认),确认前不动任何数据/文件。
     */
    private void removeTaskAndFile(String path, boolean deleteFiles) {
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (t.savePath != null && t.savePath.equals(path)) {
                DownloadFacade.get().remove(t, deleteFiles);
                return;
            }
        }
        // 完成项可能只在档案表(任务记录已清理): 同步删档案(deleteFiles=true 连文件一起删)
        com.github.tvbox.osc.download.ArchiveItem it =
                com.github.tvbox.osc.download.DownloadFacade.get().findArchiveByPath(path);
        if (it != null) {
            com.github.tvbox.osc.download.DownloadFacade.get().deleteArchive(it.episodeId, deleteFiles);
            return;
        }
        if (deleteFiles) {
            File f = new File(path);
            if (f.exists()) f.delete();
        }
    }

    // ------------------------------------------------------------------
    // 数据构建
    // ------------------------------------------------------------------

    /** 聚合分组:所有下载记录(未完成 + 已完成且文件存在),按 剧名+来源 分组,组序按最早加入时间 */
    private List<DownloadGrouping.Group> buildAggregateGroups() {
        return DownloadGrouping.group(DownloadFacade.get().getTasks(), DownloadFacade.get().getAllArchive());
    }

    /** 该剧(剧名+来源)是否仍存在于聚合(用于详情页自动退回):有下载中任务 或 有已完成档案 */
    private boolean isGroupPresent(String name, String source) {
        return DownloadGrouping.isGroupPresent(
                DownloadFacade.get().getTasks(), DownloadFacade.get().getAllArchive(), name, source);
    }

    /** 该剧未完成的任务列表(按加入时间排序) */
    private List<DownloadTask> tasksInGroup(String vodName, String sourceName) {
        return DownloadGrouping.tasksInGroup(DownloadFacade.get().getTasks(), vodName, sourceName);
    }

    /** 该剧(剧名+来源)下已完成且文件存在的视频列表(档案表驱动,按文件名排序) */
    private List<VideoInfo> buildFolderVideosFromRecords(String vodName, String sourceName) {
        List<VideoInfo> videos = new ArrayList<>();
        // 播放过的集索引集合(SP key=sourceKey|vodId);episodeId 取第一条推导
        Set<String> played = null;
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadFacade.get().queryArchiveByVod(vodName, sourceName)) {
            if (played == null) played = playedIndicesOf(it.episodeId);
            if (it.savePath == null) continue;
            File f = new File(it.savePath);
            if (!f.exists()) continue;
            VideoInfo info = new VideoInfo();
            info.setPath(f.getAbsolutePath());
            info.setDisplayName(f.getName());
            info.setTitle(f.getName());
            info.setSize(f.length());
            info.setEpisodeId(it.episodeId); // 统一剧集标识:回跳详情页/本地播放联动用
            info.setVodName(vodName); // 主标题:剧名
            // 集数名(第1集_720P 去清晰度后缀;无则按索引推导 第N集;仍无则文件名)
            info.setEpisodeName(DownloadDisplay.episodeTitleOf(it, f.getName()));
            // 来源名 + 清晰度(从集数名/文件名解析 480P/720P...)
            info.setSourceName(it.sourceName);
            info.setResolution(DownloadDisplay.resolutionOf(it, f.getName()));
            // 播放过的剧集标题置灰
            if (played != null && it.episodeId != null) {
                String idx = DownloadDisplay.lastSegment(it.episodeId);
                info.setPlayed(idx != null && played.contains(idx));
            }
            videos.add(info);
        }
        videos.sort(Comparator.comparing(VideoInfo::getDisplayName));
        return videos;
    }

    /**
     * 播放过的集索引集合:SP key=sourceKey|vodId(由 episodeId 前两段推导);
     * 无 SP 记录时回填 Room 观看记录里的"上次看到"集(老数据无播放记录);结果按 videoId 缓存。
     */
    private final Map<String, Set<String>> playedCache = new HashMap<>();

    private Set<String> playedIndicesOf(String episodeId) {
        if (episodeId == null) return null;
        String videoId = com.github.tvbox.osc.util.player.PlayedVodKey.fromEpisodeId(episodeId);
        if (videoId == null) return null;
        Set<String> cached = playedCache.get(videoId);
        if (cached != null) return cached;
        Set<String> set = SPUtils.getInstance(CacheConst.VIDEO_PLAYED_SP).getStringSet(videoId, null);
        if (set == null) set = new LinkedHashSet<>();
        // 历史回填:观看记录里"上次看到"的集也算播放过(功能上线前的老数据)
        try {
            int first = episodeId.indexOf('|');
            int second = episodeId.indexOf('|', first + 1);
            com.github.tvbox.osc.bean.VodInfo rec = com.github.tvbox.osc.repo.HistoryRepositories.history().get(
                    episodeId.substring(0, first), episodeId.substring(first + 1, second));
            if (rec != null && rec.playIndex >= 0) set.add(String.valueOf(rec.playIndex));
        } catch (Throwable ignored) {
        }
        playedCache.put(videoId, set);
        return set;
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 该剧的封面 URL(取该剧任一任务携带的 pic; 任务已清理/全部完成时从档案表补找) */
    private String picOf(String name, String source) {
        for (DownloadTask t : DownloadFacade.get().getTasks()) {
            if (DownloadGrouping.inGroup(t, name, source) && t.pic != null && !t.pic.isEmpty()) {
                return t.pic;
            }
        }
        // 聚合组可能只剩档案(任务已清理/全部完成后): 从档案表补找 pic, 避免封面一直占位
        for (com.github.tvbox.osc.download.ArchiveItem it :
                com.github.tvbox.osc.download.DownloadFacade.get().queryArchiveByVod(name, source)) {
            if (it.pic != null && !it.pic.isEmpty()) return it.pic;
        }
        return null;
    }

    /** 绑定剧集海报:优先加载本地海报文件(私有目录,不入相册),缺失时显示统一占位并懒拉取。
     * <p>占位一律走 {@link PicassoLoad} 的背景层(与首页/搜索/收藏/历史同一个组件):
     * 以前把 {@code placeholder_poster} 直接塞进 src / Picasso 的 placeholder(),
     * 会被 ImageView 的 centerCrop 当成普通图片等比放大再裁剪 —— 3:4 聚合卡上图标被撑满、
     * 56×74dp 的条目封面上更是直接超出显示范围,这就是"下载页占位超出范围"的根因。
     * 同源去重:同一 ImageView 再次绑定同一海报源(本地文件路径/已请求的 pic)时直接跳过,
     * 避免状态刷新/多选切换整行重绑时反复"占位→重载"导致图片闪动;复用条目换绑其它封面、
     * 或海报文件从无到有(懒拉取落地)时源变化,自然重新加载。 */
    private void bindPoster(ImageView iv, String vodName, String pic) {
        File pf = DownloadFacade.get().getPosterFile(vodName);
        if (pf != null) {
            // 本地海报已就绪:清掉"已发起懒拉取"的记录(复用条目换绑时不误判),按本地文件加载
            iv.setTag(TAG_POSTER_SOURCE, null);
            PicassoLoad.intoFile(iv, pf);
            return;
        }
        if (pic == null || pic.isEmpty()) {
            // 既无本地海报、也没有可拉取的 pic:仍用"加载态"占位(灰底 + 居中图标,不写"图片加载失败")
            // —— 下载页的"没有海报"是"海报还没落到本地/源没给图",不是加载失败;
            // 写失败文案还会让图标被压缩、整组上移避开底部信息条,观感变成"卡片里一小块占位"
            iv.setTag(TAG_POSTER_SOURCE, null);
            PicassoLoad.showLoadingPlaceholder(iv);
            return;
        }
        // 本地海报还在懒拉取:加载态占位(与首页加载态同观感,图标按宿主尺寸自适应)
        PicassoLoad.showLoadingPlaceholder(iv);
        if (pic.equals(iv.getTag(TAG_POSTER_SOURCE))) return; // 同一条目的 pic 只发起一次
        iv.setTag(TAG_POSTER_SOURCE, pic);
        DownloadFacade.get().ensurePosterAsync(pic, vodName);
    }

    private void toggleSet(Set<String> set, String key) {
        if (set.contains(key)) set.remove(key);
        else set.add(key);
    }

    /** 左滑操作区宽度(px,懒计算) */
    private float swipeRevealPx() {
        if (swipeRevealPxCache < 0) {
            swipeRevealPxCache = SWIPE_REVEAL_WIDTH_DP * mContext.getResources().getDisplayMetrics().density;
        }
        return swipeRevealPxCache;
    }

    /**
     * 设置左滑操作区槽位宽度 = 卡片已让出的那一段(px)。
     * <p>为什么是"动态宽度"而不是"整条铺着让卡片盖住":信息卡底色是主题 bg_card(alpha 60),
     * 半透明卡片盖不住下面垫着的色块,会按 40% 透出来(每行右侧一条浑浊蓝/红色带)。
     * 槽位宽度跟随位移后,卡片之外永远只有真正露出来的那一截,静止时宽度为 0 —— 垫在卡片下的像素恒为 0。
     */
    private void setSwipeBehindWidth(View behind, float widthPx) {
        if (behind == null) return;
        int w = Math.max(0, Math.round(widthPx));
        ViewGroup.LayoutParams lp = behind.getLayoutParams();
        if (lp == null || lp.width == w) return;
        lp.width = w;
        behind.setLayoutParams(lp);
    }

    /** 左滑松手吸附:同一动画同时驱动卡片位移与操作区宽度,保证过渡的每一帧都不多露/少露一个像素 */
    private void settleSwipe(final View front, final View behind, float toTx) {
        cancelSwipeAnim(front);
        ValueAnimator anim = ValueAnimator.ofFloat(front.getTranslationX(), toTx);
        anim.setDuration(SWIPE_SETTLE_MS);
        anim.addUpdateListener(a -> {
            float tx = (Float) a.getAnimatedValue();
            front.setTranslationX(tx);
            setSwipeBehindWidth(behind, -tx);
        });
        front.setTag(TAG_SWIPE_ANIM, anim);
        anim.start();
    }

    /** 取消该行还在跑的吸附动画(条目复用重绑、新手势开始前调用) */
    private void cancelSwipeAnim(View front) {
        Object running = front.getTag(TAG_SWIPE_ANIM);
        if (running instanceof ValueAnimator) {
            ((ValueAnimator) running).cancel();
            front.setTag(TAG_SWIPE_ANIM, null);
        }
    }

    /**
     * 下载条目的左滑手势(手指从右往左):向左拖出右侧操作区(整条高度),松手按拖出距离吸附开/关;
     * 已展开时点按前面卡片收拢。DOWN 必须消费(true)才能成为触摸目标收到后续 MOVE,
     * 长按进入多选用 Handler 定时触发(手指静止时系统不发 MOVE,不能在 MOVE 里查时长)。
     * 选中态不启用(convert 里已按需置 null);操作区槽位宽度与位移同步(见 setSwipeBehindWidth)。
     */
    private void attachSwipe(final View front, final View behind, final DownloadTask task) {
        final float reveal = swipeRevealPx();
        final int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        final long longPressTimeout = ViewConfiguration.getLongPressTimeout();
        final Handler swipeHandler = new Handler(Looper.getMainLooper());
        final float[] down = new float[2];
        final float[] startTx = new float[1];
        final boolean[] dragging = new boolean[1];
        final boolean[] longPressed = new boolean[1];
        final Runnable longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (!dragging[0] && !longPressed[0]) {
                    longPressed[0] = true;
                    enterDlSelectMode(task);
                }
            }
        };
        front.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    startTx[0] = v.getTranslationX();
                    dragging[0] = false;
                    longPressed[0] = false;
                    // 必须消费 DOWN 才能成为触摸目标收到 MOVE/UP;定时触发长按。
                    // 注意: 已展开时不在 DOWN 立即收拢——否则手指按住时的微动 MOVE 会
                    // 从收起位置拖回, 造成"收回去又弹出来"的抖动; 收拢判定移到 UP
                    swipeHandler.removeCallbacks(longPressRunnable);
                    swipeHandler.postDelayed(longPressRunnable, longPressTimeout);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!dragging[0] && !longPressed[0] && Math.abs(dx) > touchSlop
                            && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging[0] = true;
                        swipeHandler.removeCallbacks(longPressRunnable);
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        // 左滑:以按下时位置为基准增量移动(范围 -reveal..0);操作区宽度同步跟随
                        float tx = Math.max(-reveal, Math.min(0, startTx[0] + dx));
                        v.setTranslationX(tx);
                        setSwipeBehindWidth(behind, -tx);
                        return true;
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    swipeHandler.removeCallbacks(longPressRunnable);
                    if (longPressed[0]) {
                        longPressed[0] = false;
                        return true;
                    }
                    if (dragging[0]) {
                        boolean open = v.getTranslationX() < -reveal / 2f;
                        if (open) {
                            swipedTaskIds.add(task.id);
                        } else {
                            swipedTaskIds.remove(task.id);
                        }
                        settleSwipe(v, behind, open ? -reveal : 0);
                        dragging[0] = false;
                        return true;
                    }
                    // 未拖动:已展开点按 → 收拢;未展开快速点击 → 切换 暂停/开始
                    if (v.getTranslationX() < 0) {
                        swipedTaskIds.remove(task.id);
                        settleSwipe(v, behind, 0);
                    } else {
                        toggleTaskPlay(task);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    swipeHandler.removeCallbacks(longPressRunnable);
                    if (longPressed[0]) longPressed[0] = false;
                    if (dragging[0]) {
                        // 系统中断(滚动拦截等):恢复到按下前位置(展开保持展开), 不触发点击
                        settleSwipe(v, behind, startTx[0]);
                        dragging[0] = false;
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    /** 手动长按进入下载中多选并选中该任务(右滑条目替代适配器自带长按) */
    private void enterDlSelectMode(DownloadTask task) {
        if (!dlSelectMode) dlSelectMode = true;
        selectedTaskIds.add(task.id);
        downloadingAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    /** 点击条目切换播放/暂停:暂停/失败/网络中断 -> 开始下载;下载中/等待/排队 -> 暂停 */
    private void toggleTaskPlay(DownloadTask t) {
        // 被"仅WiFi"拦住的等待任务:点击给出原因,而不是无声变"已暂停"
        if (t.state == DownloadTask.STATE_WAITING && blockedByWifiOnly()) return;
        if (t.state == DownloadTask.STATE_PAUSED
                || t.state == DownloadTask.STATE_FAILED
                || t.state == DownloadTask.STATE_NETWORK_PAUSED) {
            if (blockedByWifiOnly()) return;
            DownloadFacade.get().resume(t);
        } else {
            DownloadFacade.get().pause(t);
        }
    }

    /** 仅WiFi + 当前移动网络时明确提示并拦截,避免"点了没反应";@return true=已拦截 */
    private boolean blockedByWifiOnly() {
        if (DownloadFacade.get().isWifiOnly() && DownloadFacade.get().isMobileNetwork()) {
            AppBubble.toast("已开启仅Wi-Fi下载,当前为移动网络,任务不会开始。请连接 Wi-Fi,或将下载设置为“Wi-Fi+流量”。");
            return true;
        }
        return false;
    }

    /** 用内置播放器播放下载的文件(与"我的-本地视频"一致) */
    private void playFile(VideoInfo info) {
        try {
            if (!new File(info.getPath()).exists()) {
                AppBubble.toast("文件不存在");
                return;
            }
            List<VideoInfo> list = new ArrayList<>();
            list.add(info);
            Bundle bundle = new Bundle();
            bundle.putString("videoList", GsonUtils.toJson(list));
            bundle.putInt("position", 0);
            jumpActivity(LocalPlayActivity.class, bundle);
        } catch (Throwable th) {
            th.printStackTrace();
            AppBubble.toast("播放失败:" + th.getMessage());
        }
    }
}
