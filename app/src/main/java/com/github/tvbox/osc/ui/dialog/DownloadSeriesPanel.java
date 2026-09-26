package com.github.tvbox.osc.ui.dialog;

import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载选集内容协调器（合并 DownloadSeriesDialog / DownloadSeriesRightDialog 的重复数据与交互逻辑）。
 * <p>
 * 承载两弹窗共有的：剧集数据(列表/状态/当前勾选)、选中切换语义、已选计数、倒序按钮文字、
 * "开始下载/打开下载管理"动作回调 {@link Listener}——两弹窗只保留壳与渲染差异(间距/字号)。
 */
final class DownloadSeriesPanel {

    /** 下载动作回调（原两弹窗各自 OnDownloadActionListener 的公共形态） */
    interface Listener {
        /** 开始下载所选剧集(selected 为已勾选列表) */
        void onStartDownload(List<VodInfo.VodSeries> selected);

        /** 打开下载管理页 */
        void onOpenDownloadManager();

        /** 倒序排列剧集(与选集/详情共用 sortSeries 状态) */
        void onSortSeries();
    }

    private final Listener mListener;
    /** 当前是否已倒序(倒序按钮文字;由宿主注入 isSeriesReversed) */
    private final java.util.function.BooleanSupplier mIsReversed;

    private List<VodInfo.VodSeries> mList = new ArrayList<>();
    private int[] mStates = new int[0];

    DownloadSeriesPanel(Listener listener, java.util.function.BooleanSupplier isReversed) {
        mListener = listener;
        mIsReversed = isReversed;
    }

    // ── 数据（弹窗渲染层读取）──

    List<VodInfo.VodSeries> getList() {
        return mList;
    }

    int stateAt(int position) {
        return mStates != null && position >= 0 && position < mStates.length ? mStates[position] : 0;
    }

    /** 当前展示的选集列表(供外部刷新副本保留勾选;外部也会直接改 item.selected) */
    List<VodInfo.VodSeries> getCurrentList() {
        return mList;
    }

    /** 数据准备完成后填充(主线程调用) */
    void setData(List<VodInfo.VodSeries> list, int[] states) {
        mList = list != null ? list : new ArrayList<>();
        mStates = states != null ? states : new int[0];
    }

    /** 点击单集:已下载/下载中不可选;否则切换勾选。返回是否切换成功(供 UI 刷新) */
    boolean toggleSelect(int position) {
        VodInfo.VodSeries item = mList != null && position >= 0 && position < mList.size() ? mList.get(position) : null;
        if (item == null) return false;
        int st = stateAt(position);
        if (st == 1) {
            return false; // 已下载完成:置灰不可选(UI 提示)
        }
        if (st == 2) {
            return false; // 下载中/排队:置灰不可选
        }
        item.selected = !item.selected;
        return true;
    }

    /** 已选集数 */
    int selectedCount() {
        int count = 0;
        if (mList != null) {
            for (VodInfo.VodSeries s : mList) {
                if (s.selected) count++;
            }
        }
        return count;
    }

    /** 开始下载:收集已选集;空选返回 false(UI 提示且不关闭) */
    boolean collectAndDownload() {
        List<VodInfo.VodSeries> selected = new ArrayList<>();
        if (mList != null) {
            for (VodInfo.VodSeries s : mList) {
                if (s.selected) selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            return false;
        }
        if (mListener != null) mListener.onStartDownload(selected);
        return true;
    }

    /** 倒序按钮文字:已倒序显示"正序",否则"倒序" */
    String sortButtonText() {
        try {
            if (mIsReversed != null && mIsReversed.getAsBoolean()) return "正序";
        } catch (Throwable ignored) {
        }
        return "倒序";
    }

    /** 排序动作(由弹窗按钮触发;点击后宿主负责刷新显示) */
    void sort() {
        if (mListener != null) mListener.onSortSeries();
    }

    void openManager() {
        if (mListener != null) mListener.onOpenDownloadManager();
    }
}
