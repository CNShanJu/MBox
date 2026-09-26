package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 通用选择弹窗（统一走 XPopup 居中弹窗 AppCenterPopupView，观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变：{@code new SelectDialog<>(ctx)} + {@code setTip/setAdapter} + {@code show()}——
 * XPopup 内容视图在 onCreate() 才能 findViewById，故 setter 只暂存参数、onCreate 内渲染；
 * {@link #show()} 在 popupInfo 未绑定时自动经 XPopup.Builder 绑定，兼容旧 Dialog 式调用点；
 * {@link #setOnDismissListener} 兼容旧 Dialog API（经 XPopupCallback.onDismiss 触发）。
 */
public class SelectDialog<T> extends AppCenterPopupView {

    private final int layoutId;

    private String tip;
    private SelectDialogAdapter.SelectDialogInterface<T> selectInterface;
    private DiffUtil.ItemCallback<T> itemCallback;
    private List<T> data;
    private int selectPos;
    private DialogInterface.OnDismissListener onDismissListener;
    private RecyclerView.LayoutManager listLayoutManager;

    /** 是否按“屏幕可用高度分档”动态调高(默认关闭;首页数据源等大列表场景经 setDynamicHeightByScreen(true) 开启) */
    private boolean dynamicHeightByScreen = false;

    public SelectDialog(@NonNull @NotNull Context context) {
        this(context, R.layout.dialog_select);
    }

    public SelectDialog(@NonNull @NotNull Context context, int resId) {
        super(context);
        layoutId = resId;
    }

    @Override
    protected int getImplLayoutId() {
        return layoutId;
    }

    /** 布局自带滚动区(TvRecyclerView),超高由列表自滚,不整卡包裹 */
    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        applyListLayoutManager();
        findViewById(R.id.iv_close).setOnClickListener(view -> dismiss());
        if (tip != null) {
            setTip(tip);
        }
        if (selectInterface != null && itemCallback != null && data != null) {
            setAdapter(selectInterface, itemCallback, data, selectPos);
        }
        boolean stretchToFixedHeight = dynamicHeightByScreen && isFixedFillBucket();
        if (stretchToFixedHeight) {
            // 大屏(≥700dp)/小屏(≤480dp):整卡固定为分档目标高(60% / 铺满),列表吃满剩余空间;超高由 TvRecyclerView 自滚
            applyDynamicHeight();
        } else {
            // 其余情况(含区间 480~700dp):高度由内容撑开(不固定);内容超高时才按分档上限压缩列表、列表自滚
            clampListHeightToFit();
        }
        // 自绘滚动指示条:RecyclerView 系统滚动条静止不绘制,列表超高时用右侧 thumb 提示可滚动
        // (固定高模式由 applyDynamicHeight 在重排完成后挂载;wrap 模式此处直接挂载)
        android.view.View list = findViewById(R.id.list);
        android.view.View thumb = findViewById(R.id.scroll_thumb);
        if (list != null && thumb != null && !stretchToFixedHeight) {
            ScrollThumbIndicator.attach((com.owen.tvrecyclerview.widget.TvRecyclerView) list, thumb);
        }
    }

    /**
     * 按“屏幕可用高度”分档(开关见 {@link #setDynamicHeightByScreen};分档数值统一见 {@link DialogHeightPolicy}):
     * 大屏(≥700dp)→ 整卡固定 60% 屏高;小屏(≤480dp)→ 铺满屏幕;
     * 区间(480~700dp)→ 高度不固定、由内容撑开,50% 屏高仅为超高时的上限。
     */
    public void setDynamicHeightByScreen(boolean enable) {
        dynamicHeightByScreen = enable;
        if (enable && findViewById(R.id.cl_root) != null && isFixedFillBucket()) {
            applyDynamicHeight();
        }
    }

    /** 是否为“整卡固定高”分档(大屏 60% / 小屏铺满);区间档走 wrap 内容自适应 */
    private boolean isFixedFillBucket() {
        int base = getMeasuredHeight();
        if (base <= 0) base = ScreenUtils.getScreenHeight();
        if (base <= 0) return false;
        float heightDp = base / getResources().getDisplayMetrics().density;
        return heightDp >= DialogHeightPolicy.SCREEN_DP_LARGE
                || heightDp <= DialogHeightPolicy.SCREEN_DP_SMALL;
    }

    /** 动态模式:弹窗可用高度上限 = 分档高度(超高时列表自滚,不会被 XPopup 再裁剪) */
    @Override
    protected int getMaxHeight() {
        if (dynamicHeightByScreen) {
            int targetH = dynamicTargetHeightPx();
            if (targetH > 0) return targetH;
        }
        return super.getMaxHeight();
    }

    /** 分档高度:基准为弹窗可用高度(未布局前取整屏),按 {@link DialogHeightPolicy} 分档取占比;
     *  大屏(≥700dp)60% / 小屏(≤480dp)铺满 / 区间(480~700dp)50%(仅作内容超高时的封顶上限) */
    private int dynamicTargetHeightPx() {
        int base = getMeasuredHeight();
        if (base <= 0) base = ScreenUtils.getScreenHeight();
        if (base <= 0) return 0;
        float density = getResources().getDisplayMetrics().density;
        return Math.round(base * DialogHeightPolicy.ratio(density, base));
    }

    /**
     * 布局完成后按分档目标高重排整卡:标题 + 底部留白(dp_30)为固定区,
     * 列表高度 = 目标高 - 固定区(内容更少时列表吃满整卡,超高时由 TvRecyclerView 自滚),
     * 布局根保持 wrap,总高自然收敛到目标高。
     */
    private void applyDynamicHeight() {
        final android.view.View list = findViewById(R.id.list);
        if (list == null) return;
        list.post(() -> {
            try {
                int targetH = dynamicTargetHeightPx();
                int available = getMeasuredHeight();
                if (available > 0 && targetH > available) {
                    targetH = available; // 兜底:不超出弹窗实际可用区域
                }
                if (targetH <= 0) return;
                android.view.View title = findViewById(R.id.title);
                int titleBottom = title != null ? title.getBottom() : 0;
                int footerPx = getResources().getDimensionPixelSize(R.dimen.dp_30);
                int listH = targetH - titleBottom - footerPx;
                int minListH = Math.round(60f * getResources().getDisplayMetrics().density);
                if (listH < minListH) listH = minListH;
                android.view.ViewGroup.LayoutParams lp = list.getLayoutParams();
                if (lp == null) {
                    lp = new android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, listH);
                } else {
                    lp.height = listH;
                }
                list.setLayoutParams(lp);
                // 列表高度变化后重新挂滚动指示条:其初始 update 会在新几何布局完成后执行
                android.view.View thumb = findViewById(R.id.scroll_thumb);
                if (thumb != null && list instanceof com.owen.tvrecyclerview.widget.TvRecyclerView) {
                    final com.owen.tvrecyclerview.widget.TvRecyclerView tvList =
                            (com.owen.tvrecyclerview.widget.TvRecyclerView) list;
                    list.post(() -> ScrollThumbIndicator.attach(tvList, thumb));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 内容自带滚动区(TvRecyclerView):超高由列表自滚、标题固定,不整卡包裹。
     * 列表可用高 = maxHeight(动态分档 / 默认 70% 屏) - 固定区(标题+上下边距);
     * 用 UNSPECIFIED 量"自然内容高"判断是否超高(不受 XPopup 容器已钳高影响),
     * 超高时把列表压到可用高内,由 TvRecyclerView 自己滚动;未超高则保持 wrap,高度由内容撑开。
     */
    private void clampListHeightToFit() {
        final android.view.View root = findViewById(R.id.cl_root);
        final android.view.View list = findViewById(R.id.list);
        if (root == null || list == null) return;
        list.post(this::applyListHeightClamp);
    }

    private void applyListHeightClamp() {
        try {
            final android.view.View root = findViewById(R.id.cl_root);
            final android.view.View list = findViewById(R.id.list);
            if (root == null || list == null) return;
            int maxH = getMaxHeight();
            if (maxH <= 0) return;
            // 用 UNSPECIFIED 重新测量整卡"自然高"(不受 XPopup 容器钳高影响),判断是否真的超高
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(root.getWidth(), android.view.View.MeasureSpec.EXACTLY);
            int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
            root.measure(wSpec, hSpec);
            int naturalH = root.getMeasuredHeight();
            if (naturalH <= maxH) return; // 未超高:保持 wrap(短列表自适应)
            int naturalListH = list.getMeasuredHeight();
            int fixedH = naturalH - naturalListH;
            int available = maxH - fixedH;
            if (available <= 0) available = maxH / 2;
            android.view.ViewGroup.LayoutParams lp = list.getLayoutParams();
            lp.height = available;
            list.setLayoutParams(lp);
            list.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    /** 兼容旧调用点：popupInfo 未绑定（直接 new 未走 Builder）时经 Builder 绑定后展示 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            XPopup.Builder builder = new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme());
            if (onDismissListener != null) {
                builder.setPopupCallback(new XPopupCallback() {
                    @Override public void onCreated(BasePopupView v) { }
                    @Override public void beforeShow(BasePopupView v) { }
                    @Override public void onShow(BasePopupView v) { }
                    @Override public void onDismiss(BasePopupView v) {
                        DialogInterface.OnDismissListener l = onDismissListener;
                        onDismissListener = null;
                        if (l != null) l.onDismiss(null);
                    }
                    @Override public void beforeDismiss(BasePopupView v) { }
                    @Override public boolean onBackPressed(BasePopupView v) { return false; }
                    @Override public void onKeyBoardStateChanged(BasePopupView v, int h) { }
                    @Override public void onDrag(BasePopupView v, int c, float x, boolean b) { }
                    @Override public void onClickOutside(BasePopupView v) { }
                });
            }
            return builder.asCustom(this).show();
        }
        return super.show();
    }

    /** 兼容旧 Dialog API：dismiss 后回调（同一次展示只触发一次） */
    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        this.onDismissListener = listener;
    }

    /** 兼容旧 Dialog API：cancel() ≈ dismiss() */
    public void cancel() {
        dismiss();
    }

    /**
     * 自定义列表 LayoutManager（默认走布局 XML 的 tv_layoutManager）。
     * XPopup 内容视图在 onCreate() 才有，故只暂存、onCreate 渲染前应用；
     * 若内容已创建（show() 后调用）则立即生效。
     */
    public void setListLayoutManager(RecyclerView.LayoutManager layoutManager) {
        listLayoutManager = layoutManager;
        applyListLayoutManager();
    }

    private void applyListLayoutManager() {
        if (listLayoutManager == null) return;
        View list = findViewById(R.id.list);
        if (list instanceof TvRecyclerView) {
            ((TvRecyclerView) list).setLayoutManager(listLayoutManager);
        }
    }

    public void setTip(String tip) {
        this.tip = tip;
        if (findViewById(R.id.title) != null) {
            ((android.widget.TextView) findViewById(R.id.title)).setText(tip);
        }
    }

    public void setAdapter(SelectDialogAdapter.SelectDialogInterface<T> sourceBeanSelectDialogInterface,
                           DiffUtil.ItemCallback<T> sourceBeanItemCallback, List<T> data, int select) {
        this.selectInterface = sourceBeanSelectDialogInterface;
        this.itemCallback = sourceBeanItemCallback;
        this.data = data;
        this.selectPos = select;
        if (findViewById(R.id.list) == null) {
            return; // 尚未 inflate：onCreate 会再渲染
        }
        SelectDialogAdapter<T> adapter = new SelectDialogAdapter(sourceBeanSelectDialogInterface, sourceBeanItemCallback);
        adapter.setData(data, select);
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        tvRecyclerView.setAdapter(adapter);
        tvRecyclerView.setSelectedPosition(select);
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                tvRecyclerView.smoothScrollToPosition(select);
                tvRecyclerView.setSelectionWithSmooth(select);
            }
        });
    }
}

