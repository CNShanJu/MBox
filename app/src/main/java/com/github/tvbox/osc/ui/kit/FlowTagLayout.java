package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 自研**流式标签容器**(标签 chips 一行放不下就换行)。
 *
 * <p>替代第三方 {@code com.hyman:flowlayout-lib}({@code com.zhy.view.flowlayout.TagFlowLayout}):
 * 那条库的行高算法把子视图的**上下外边距**算漏了,于是"只剩边框线"的标签在最后一行会被容器裁掉下半截 ——
 * 表现成"圆角只对顶部有效、下边看着是方的",而且子视图一改外边距观感就变。自己写一个把几件事定死:
 *
 * <ul>
 *   <li><b>行高含外边距</b>:排版算术全在 {@link FlowLineBreaker}(纯函数,带 JVM 单测),
 *       块占位 = 外边距 + 自身尺寸,所以任何一行都不会溢出、不会被裁;</li>
 *   <li><b>间距只认子视图自己的 {@code layout_margin}</b>:标签间距就是条目布局上的 margin,
 *       不再有"容器 spacing + 条目 margin"两套值互相打架;</li>
 *   <li><b>数量少时不铺满</b>:宽度 wrap_content 给多少用多少,不强行等分(等分是 GridLayout 的活)。</li>
 * </ul>
 *
 * <p>API 与旧库对齐,调用点迁移只改类名:{@link #setAdapter(Adapter)}、
 * {@link #setOnTagClickListener(OnTagClickListener)}、{@link TagAdapter}(列表型适配器基类),
 * XML 属性沿用 {@code app:max_select}。
 */
public class FlowTagLayout extends ViewGroup {

    /** 适配器:按位置给出条目数据与它的视图 */
    public interface Adapter<T> {

        int getCount();

        T getItem(int position);

        /** 造/复用一个标签视图(parent 是本容器,供 inflate 用) */
        View getView(FlowTagLayout parent, int position, T item);
    }

    /**
     * 列表型适配器:调用点只需重写 {@link #getView} —— 与旧库的 {@code TagAdapter} 同名同形,
     * 迁移时把类名从 {@code TagAdapter} 换成 {@code FlowTagLayout.TagAdapter} 即可。
     */
    public abstract static class TagAdapter<T> implements Adapter<T> {

        private final List<T> items;

        public TagAdapter(List<T> items) {
            this.items = items == null ? new ArrayList<T>() : items;
        }

        @Override
        public final int getCount() {
            return items.size();
        }

        @Override
        public final T getItem(int position) {
            return items.get(position);
        }
    }

    /** 点击某个标签;返回值与旧库一致(是否已消费),本容器不据此改变行为 */
    public interface OnTagClickListener {

        boolean onTagClick(View view, int position, FlowTagLayout parent);
    }

    private Adapter<?> adapter;
    private OnTagClickListener tagClickListener;
    /** >0 时点击标签会切换"选中态"(单选),并同步 view.setSelected —— 驱动 selector_widget_btn 的选中外观 */
    private int maxSelect;
    private int selectedPosition = -1;
    /** 上一次测量的排版结果(onLayout 用) */
    private FlowLineBreaker.Result lines;

    public FlowTagLayout(Context context) {
        this(context, null);
    }

    public FlowTagLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlowTagLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 排版本身保证子视图不越界(见 FlowLineBreakerTest);这里再关一层裁剪当保险 ——
        // 万一子视图在 layout 之后又被重新测量(marquee 之类),圆角也不会被容器切掉。
        setClipChildren(false);
        setClipToPadding(false);
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.FlowTagLayout);
            try {
                maxSelect = ta.getInt(R.styleable.FlowTagLayout_max_select, 0);
            } finally {
                ta.recycle();
            }
        }
    }

    // ───────────────────────── 数据 ─────────────────────────

    public void setAdapter(Adapter<?> adapter) {
        this.adapter = adapter;
        rebuildChildren();
    }

    /** 数据变了(同一份 adapter 内容被改过)时重建子视图 */
    public void notifyDataChanged() {
        rebuildChildren();
    }

    public Adapter<?> getAdapter() {
        return adapter;
    }

    public void setOnTagClickListener(OnTagClickListener listener) {
        this.tagClickListener = listener;
    }

    public void setMaxSelect(int maxSelect) {
        this.maxSelect = maxSelect;
    }

    /** 当前处于选中态的标签(没有则 null) */
    public View getSelectedView() {
        return selectedPosition >= 0 && selectedPosition < getChildCount()
                ? getChildAt(selectedPosition) : null;
    }

    private void rebuildChildren() {
        removeAllViews();
        selectedPosition = -1;
        if (adapter == null) return;
        int count = adapter.getCount();
        for (int i = 0; i < count; i++) {
            View child = buildChild(adapter, i);
            if (child == null) continue;
            final int position = i;
            // 旧的 TagFlowLayout 也是这么挂点击的:覆盖适配器里可能设置的 OnClickListener
            child.setOnClickListener(v -> {
                if (maxSelect > 0) toggleSelect(position);
                if (tagClickListener != null) tagClickListener.onTagClick(v, position, FlowTagLayout.this);
            });
            addView(child);
        }
        requestLayout();
    }

    /** 泛型通配下的取视图帮手:item 的类型与 adapter 的 T 一致,这里用原生类型收口 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private View buildChild(Adapter adapter, int position) {
        return (View) adapter.getView(this, position, adapter.getItem(position));
    }

    private void toggleSelect(int position) {
        selectedPosition = (selectedPosition == position) ? -1 : position;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setSelected(i == selectedPosition);
        }
    }

    // ───────────────────────── 测量 / 布局 ─────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int hPadding = getPaddingLeft() + getPaddingRight();
        // UNSPECIFIED(横向滚动容器里)时按"不限宽"排,避免把标签挤成竖排
        final int availWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? 0 : Math.max(0, widthSize - hPadding);

        List<FlowLineBreaker.Item> items = new ArrayList<>(getChildCount());
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            items.add(new FlowLineBreaker.Item(
                    child.getMeasuredWidth(), child.getMeasuredHeight(),
                    lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin));
        }
        lines = FlowLineBreaker.layout(items, availWidth);
        int desiredHeight = lines.height + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(widthSize, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (lines == null) return;
        final int count = Math.min(getChildCount(), lines.left.length);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int left = getPaddingLeft() + lines.left[i];
            int top = getPaddingTop() + lines.top[i];
            child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
        }
    }

    // ───────────────────────── LayoutParams(带外边距) ─────────────────────────

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }
}
