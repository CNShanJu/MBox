package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.blankj.utilcode.util.ColorUtils;
import com.blankj.utilcode.util.LogUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VideoFolder;
import com.github.tvbox.osc.bean.VideoInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public class SubscriptionAdapter extends BaseQuickAdapter<Subscription, BaseViewHolder> {

    /**
     * 勾选语义(点条目干什么):
     * <ul>
     *   <li>{@link #NORMAL}:点条目 = 切换"当前订阅"(单选);</li>
     *   <li>{@link #EXPORT}:点条目 = 勾选<b>要导出</b>的订阅(多选,标题栏「导出」进入);</li>
     *   <li>{@link #DELETE}:点条目 = 勾选<b>要删除</b>的订阅(多选,长按菜单「多选」进入)。</li>
     * </ul>
     * 两种多选态的列表呈现完全一样(复选框 + 收起删除/置顶标记),只是动作不同。
     */
    public enum Mode {NORMAL, EXPORT, DELETE}

    /** 多选勾选数变化(操作栏"删除"键的可用态看它) */
    public interface OnSelectCountListener {
        void onSelectCount(int count);
    }

    private Mode mode = Mode.NORMAL;
    /** 多选态下已勾选的地址(与列表顺序解耦,列表重排也不丢) */
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private OnSelectCountListener selectCountListener;

    public SubscriptionAdapter() {
        super(R.layout.item_subscription);
    }

    @Override
    protected void convert(BaseViewHolder helper, Subscription item) {
        helper.setText(R.id.tv_name,item.getName())
        .setText(R.id.tv_url,item.getUrl());

        if (mode != Mode.NORMAL) {
            // 多选态(导出/删除):复选框表示"选中",删除/置顶标记先收起来
            // (避免与"当前订阅"那颗勾混为一谈)
            helper.setChecked(R.id.cb, item.getUrl() != null && selected.contains(item.getUrl()))
                    .setVisible(R.id.iv_del, false)
                    .setVisible(R.id.iv_pushpin, false);
            return;
        }
        helper.setChecked(R.id.cb,item.isChecked())
                .setVisible(R.id.iv_del, true)
                .setVisible(R.id.iv_pushpin,item.isTop());

        helper.addOnClickListener(R.id.iv_del);
    }

    // ------------------------------------------------------------------
    // 多选(导出 / 删除共用同一套勾选)
    // ------------------------------------------------------------------

    public Mode getMode() {
        return mode;
    }

    /** 是否处于"导出选择"态(导出条的显隐与文案看它) */
    public boolean isExportMode() {
        return mode == Mode.EXPORT;
    }

    /** 是否处于任意多选态 */
    public boolean inSelectMode() {
        return mode != Mode.NORMAL;
    }

    /** 切换勾选语义(进入/退出多选:一律清空上次勾选) */
    public void setMode(Mode newMode) {
        mode = newMode == null ? Mode.NORMAL : newMode;
        selected.clear();
        notifyDataSetChanged();
        notifySelectCount();
    }

    /** 兼容旧调用(导出流程用) */
    public void setExportMode(boolean on) {
        setMode(on ? Mode.EXPORT : Mode.NORMAL);
    }

    public void setOnSelectCountListener(OnSelectCountListener listener) {
        selectCountListener = listener;
    }

    private void notifySelectCount() {
        if (selectCountListener != null) selectCountListener.onSelectCount(selected.size());
    }

    /** 勾选/取消一条;返回该条当前是否被勾选 */
    public boolean toggleSelection(String url) {
        if (url == null) return false;
        boolean nowSelected;
        if (!selected.remove(url)) {
            selected.add(url);
            nowSelected = true;
        } else {
            nowSelected = false;
        }
        notifyDataSetChanged();
        notifySelectCount();
        return nowSelected;
    }

    public int selectedCount() {
        return selected.size();
    }

    /** 全选/全不选(按当前列表数据) */
    public void setSelectAll(boolean all) {
        selected.clear();
        if (all) {
            for (Subscription s : getData()) {
                if (s != null && s.getUrl() != null) selected.add(s.getUrl());
            }
        }
        notifyDataSetChanged();
        notifySelectCount();
    }

    /** 是否已全选(列表非空时才有意义) */
    public boolean isAllSelected() {
        List<Subscription> data = getData();
        if (data == null || data.isEmpty()) return false;
        for (Subscription s : data) {
            if (s == null || s.getUrl() == null || !selected.contains(s.getUrl())) return false;
        }
        return true;
    }

    /** 按列表顺序返回勾选的订阅(导出抓取顺序 / 删除顺序 = 列表顺序) */
    public List<Subscription> selection() {
        List<Subscription> out = new ArrayList<>();
        for (Subscription s : getData()) {
            if (s != null && s.getUrl() != null && selected.contains(s.getUrl())) out.add(s);
        }
        return out;
    }

    /**
     * 刷新列表时候,添加去重和排序
     * @param data
     */
    @Override
    public void setNewData(@Nullable List<Subscription> data) {
        if (data!=null){
            //去除url重复的订阅
            for (int i = 0; i < data.size(); i++) {
                for (int j = i+1; j < data.size(); j++) {
                    if (data.get(i).getUrl().equals(data.get(j).getUrl())){
                        data.remove(j);
                        j--;
                    }
                }
            }
            data.sort(mComparator);
        }
        super.setNewData(data);
    }

    Comparator<Subscription> mComparator = (s1, s2) -> {
        if (s1.isTop() && !s2.isTop()) {
            return -1;
        } else if (!s1.isTop() && s2.isTop()) {
            return 1;
        } else if (s1.isChecked() && !s2.isChecked()) {
            return -1;
        } else if (!s1.isChecked() && s2.isChecked()) {
            return 1;
        } else {
            return 0;
        }
    };
}
