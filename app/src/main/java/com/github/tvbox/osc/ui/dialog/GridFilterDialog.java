package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.lihang.ShadowLayout;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 首页网格筛选弹窗（统一走 XPopup 底部弹窗 AppBottomPopupView;观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变:{@code new GridFilterDialog(ctx)} + {@code setData/setOnDismiss} + {@code show()};
 * GridFragment 复用同一实例反复 show,{@link #show()} 首次自绑定 popupInfo、之后 super.show() 复用。
 */
public class GridFilterDialog extends AppBottomPopupView {
    private LinearLayout filterRoot;
    private MovieSort.SortData mSortData;
    private Callback dismissCallback;
    private boolean selectChange = false;

    public GridFilterDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_grid_filter;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        filterRoot = findViewById(R.id.filterRoot);
        findViewById(R.id.btn_reset).setOnClickListener(view -> {
            mSortData.filterSelect = new HashMap<>();
            selectChange = true;
            setData(mSortData);
        });

        findViewById(R.id.btn_confirm).setOnClickListener(view -> dismiss());
        if (mSortData != null) {
            setData(mSortData);
        }
    }

    public interface Callback {
        void change();
    }

    /** 兼容旧 API：dismiss 后回调（仅筛选有变化时触发一次） */
    public void setOnDismiss(Callback callback) {
        this.dismissCallback = callback;
    }

    /** 兼容旧调用点：popupInfo 未绑定时经 Builder 绑定（底部贴底 + 无阴影遮罩,与原 dim=0 观感一致） */
    @Override
    public BasePopupView show() {
        selectChange = false;
        if (popupInfo == null) {
            return new com.lxj.xpopup.XPopup.Builder(getContext())
                    .isViewMode(true)
                    .hasNavigationBar(false)
                    .hasShadowBg(false)
                    .setPopupCallback(new com.lxj.xpopup.interfaces.XPopupCallback() {
                        @Override public void onCreated(BasePopupView v) { }
                        @Override public void beforeShow(BasePopupView v) { }
                        @Override public void onShow(BasePopupView v) { }
                        @Override public void onDismiss(BasePopupView v) {
                            if (selectChange && dismissCallback != null) {
                                dismissCallback.change();
                            }
                        }
                        @Override public void beforeDismiss(BasePopupView v) { }
                        @Override public boolean onBackPressed(BasePopupView v) { return false; }
                        @Override public void onKeyBoardStateChanged(BasePopupView v, int h) { }
                        @Override public void onDrag(BasePopupView v, int c, float x, boolean b) { }
                        @Override public void onClickOutside(BasePopupView v) { }
                    })
                    .asCustom(this).show();
        }
        return super.show();
    }

    public void setData(MovieSort.SortData sortData) {
        mSortData = sortData;
        if (filterRoot == null) {
            return; // 尚未 inflate：onCreate 会再渲染（GridFragment 首次 setData 在 show 前,由 onCreate 兜底）
        }
        filterRoot.removeAllViews();
        for (MovieSort.SortFilter filter : sortData.filters) {
            View line = LayoutInflater.from(getContext()).inflate(R.layout.item_grid_filter, null);
            RecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
            GridFilterKVAdapter filterKVAdapter = new GridFilterKVAdapter();
            gridView.setAdapter(filterKVAdapter);
            String key = filter.key;
            ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            ArrayList<String> keys = new ArrayList<>(filter.values.values());
            filterKVAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                View pre = null;

                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    selectChange = true;
                    String filterSelect = sortData.filterSelect.get(key);
                    if (filterSelect == null || !filterSelect.equals(keys.get(position))) {// 没选 或 不是重选
                        sortData.filterSelect.put(key, keys.get(position));
                        if (pre != null) {//上一次点击的view
                            ShadowLayout val = pre.findViewById(R.id.sl);
                            val.setSelected(false);
                        }
                        ShadowLayout val = view.findViewById(R.id.sl);
                        val.setSelected(true);
                        //记录点击的view,下次点击对上一个view做处理
                        pre = view;
                    } else {// 重选 取消
                        sortData.filterSelect.remove(key);
                        if (pre != null) {
                            ShadowLayout val = pre.findViewById(R.id.sl);
                            val.setSelected(false);
                        }
                        pre = null;
                    }
                }
            });
            filterKVAdapter.setNewData(values);
            filterRoot.addView(line);
        }
    }
}
