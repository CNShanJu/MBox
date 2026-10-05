package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;

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
    /** 可选:长按列表项 / 行样式(都透传给内部 adapter;不设时行为与以前完全一致) */
    private SelectDialogAdapter.OnItemLongClickListener<T> itemLongClickListener;
    private SelectDialogAdapter.RowStyle<T> rowStyle;
    /** 可选固定页脚(不随列表滚动);见 {@link #setFooterView} */
    private View footerView;

    /** 是否按“屏幕可用高度分档”动态调高(默认关闭;首页数据源等大列表场景经 setDynamicHeightByScreen(true) 开启) */
    private boolean dynamicHeightByScreen = false;
    private boolean autoFocusEditText = true;

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
        // 无条件走一次:有页脚=渲染 + 留出与列表的间距;没页脚=置 GONE + 清掉那段间距
        // (GONE 的视图在 ConstraintLayout 里仍会带上自己的 margin,不清掉就会给所有
        //  SelectDialog 白白多加一截空白)
        renderFooter();
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

    /** 含筛选框的选择弹窗可关闭自动聚焦，用户点输入框时再弹键盘。 */
    public void setAutoFocusEditText(boolean enable) {
        autoFocusEditText = enable;
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
                int footerPx = getResources().getDimensionPixelSize(R.dimen.dp_30);
                // 列表上方可能有筛选框等固定内容，按真实起点预留，而非仅扣标题。
                int listH = targetH - list.getTop() - footerPx;
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
     * <p>子类若在 {@code onCreate()} 里才补列表数据(基类的 onCreate 已按空列表量过一次),
     * 补完数据后需要再调一次本方法,否则列表会一直用布局里的固定上限、在小屏上被弹窗裁掉。
     */
    protected void clampListHeightToFit() {
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
            int width = root.getWidth() > 0 ? root.getWidth() : root.getMeasuredWidth();
            if (width <= 0) return;
            // 列表曾因内容过多被压成固定高度时，先恢复 wrap 再量自然高；否则删除条目后
            // 仍会留下空白，新增条目时也无法根据当前内容重新给固定页脚留空间。
            android.view.ViewGroup.LayoutParams lp = list.getLayoutParams();
            if (lp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                list.setLayoutParams(lp);
            }
            // 用 UNSPECIFIED 重新测量整卡"自然高"(不受 XPopup 容器钳高影响),判断是否真的超高
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY);
            int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
            root.measure(wSpec, hSpec);
            int naturalH = root.getMeasuredHeight();
            if (naturalH <= maxH) return; // 未超高:保持 wrap(短列表自适应)
            int naturalListH = list.getMeasuredHeight();
            int fixedH = naturalH - naturalListH;
            int available = maxH - fixedH;
            // 固定的标题/页脚优先可见；极短窗口也不能再让列表占半屏把页脚挤出去。
            if (available <= 0) available = 1;
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
                    .isDarkTheme(Utils.isDarkTheme())
                    .autoFocusEditText(autoFocusEditText);
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

    /**
     * @param select 默认选中项下标;传 <b>-1</b> 表示"动作列表"(没有默认选中项,每一行都可点,
     *               如详情页截图后的"跳转哪个 App"),其余场景传当前项下标。
     */
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
        applyExtras(adapter);
        adapter.setData(data, select);
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        tvRecyclerView.setAdapter(adapter);
        // select < 0 = "动作列表"语义(没有默认选中项,如截图后的"跳转哪个 App"):
        // 此时一**行都不打勾**,也不该把 -1 丢给 TvRecyclerView 选位置/滚动(库内按位置取视图,
        // 负值没有对应条目)。适配器那边 position == select 永远不成立,所以每一行都可点 ——
        // 这正是动作列表要的:选择列表才需要"已选项点不动"。
        if (select >= 0) {
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

    /**
     * 可选:长按列表项(如"长按自定义主题出编辑/设为默认/删除气泡")。
     * <p>与 {@link #setRowStyle} 一样是**追加能力**,不改动既有调用点的观感与行为。
     */
    public void setOnItemLongClickListener(SelectDialogAdapter.OnItemLongClickListener<T> l) {
        this.itemLongClickListener = l;
        SelectDialogAdapter<T> adapter = currentAdapter();
        if (adapter != null) adapter.setOnItemLongClickListener(l);
    }

    /** 可选:行样式微调(目前只用来给行背景加一个居中的水印图标,如太阳/月亮表示主题亮暗);见 {@link SelectDialogAdapter.RowStyle} */
    public void setRowStyle(SelectDialogAdapter.RowStyle<T> style) {
        this.rowStyle = style;
        SelectDialogAdapter<T> adapter = currentAdapter();
        if (adapter != null) adapter.setRowStyle(style);
    }

    /**
     * 可选:固定页脚(**不随列表滚动**)。用于放"动作入口"这类东西(如主题弹窗的「自定义主题颜色」)——
     * 放在列表里会跟着一起滚,用户滚到底才看得见;放页脚则始终可见,而且不必伪装成"可勾选的选项行"。
     * <p>页脚容器自带左右 10dp 外边距(与列表行对齐),内容自己在里面居中/排版。
     */
    public void setFooterView(View view) {
        this.footerView = view;
        View container = findViewById(R.id.ll_footer);
        if (container == null) return; // 尚未 inflate：onCreate 会再渲染
        renderFooter();
    }

    private void renderFooter() {
        View container = findViewById(R.id.ll_footer);
        if (!(container instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) container;
        group.removeAllViews();
        setFooterGap(group, footerView != null);
        if (footerView == null) {
            group.setVisibility(View.GONE);
            return;
        }
        View parent = (View) footerView.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(footerView);
        group.addView(footerView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        group.setVisibility(View.VISIBLE);
    }

    /**
     * 页脚与列表之间的间距:有页脚时留 10dp —— 否则列表最后一行会<b>贴着</b>页脚,
     * 滚动时上半截是行、下半截是页脚,看着像连成一片(用户口径:"给点外边距")。
     */
    private void setFooterGap(ViewGroup group, boolean hasFooter) {
        ViewGroup.LayoutParams lp = group.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        int gap = hasFooter
                ? getResources().getDimensionPixelSize(R.dimen.dp_10)
                : 0;
        if (mlp.topMargin == gap) return;
        mlp.topMargin = gap;
        group.setLayoutParams(mlp);
    }

    /** 当前列表上的 adapter(还没渲染时为 null) */
    @SuppressWarnings("unchecked")
    protected SelectDialogAdapter<T> currentAdapter() {
        View list = findViewById(R.id.list);
        if (!(list instanceof RecyclerView)) return null;
        RecyclerView.Adapter<?> a = ((RecyclerView) list).getAdapter();
        return a instanceof SelectDialogAdapter ? (SelectDialogAdapter<T>) a : null;
    }

    /** 重排列表(改了数据/选中项后调用):沿用同一个 adapter,不重建列表 */
    protected void refreshList(List<T> newData, int newSelect) {
        SelectDialogAdapter<T> adapter = currentAdapter();
        if (adapter == null) {
            setAdapter(selectInterface, itemCallback, newData, newSelect);
            clampListHeightToFit();
            return;
        }
        this.data = newData;
        this.selectPos = newSelect;
        adapter.setData(newData, newSelect);
        View list = findViewById(R.id.list);
        if (newSelect >= 0 && list instanceof TvRecyclerView) { // 同 setAdapter:负值 = 无默认选中项
            ((TvRecyclerView) list).setSelectedPosition(newSelect);
        }
        // 数据条数会在弹窗仍显示时改变(例如新增/删除主题)。同步重算列表高度，
        // 让新增的行留在可滚动区内，固定页脚始终在弹窗范围内。
        clampListHeightToFit();
    }

    private void applyExtras(SelectDialogAdapter<T> adapter) {
        if (itemLongClickListener != null) adapter.setOnItemLongClickListener(itemLongClickListener);
        if (rowStyle != null) adapter.setRowStyle(rowStyle);
    }
}
