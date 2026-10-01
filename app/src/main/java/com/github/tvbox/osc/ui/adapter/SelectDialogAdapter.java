package com.github.tvbox.osc.ui.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.google.android.material.checkbox.MaterialCheckBox;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SelectDialogAdapter<T> extends ListAdapter<T, SelectDialogAdapter.SelectViewHolder> {

    /** 行背景图标的尺寸(dp):当背景水印用,别再放大(太大会压过行文字) */
    private static final float ICON_SIZE_DP = 22f;

    /**
     * 选择行的底色与页面底色之间的混合比例(0~1)。
     *
     * <p>用户口径:"单个item背景颜色看能不能通过 {@code bg_surface} 计算出来,有点区别就行,
     * 纯色不透明"。所以这里**不新增主题键**,直接由主题的"实心面"({@code bg_card} 去掉透明度的
     * 不透明版)向页面底色 {@code bg_body} 稍微靠一点:亮色主题略微压深、暗色主题略微提亮,
     * 与页面有分别但同属一个色系。
     *
     * <p>2026-10-01 真机实测:0.12 时行底 {@code #4642F1} 与弹窗底 {@code #4848F9}
     * **几乎看不出差别**(用户要的是"有点区别"),故提到 0.3(实测 {@code #3833CB},能看出分区)。
     */
    private static final float ROW_MIX = 0.30f;

    class SelectViewHolder extends RecyclerView.ViewHolder {

        /** 行布局自带的背景(圆角底色):加了居中图标后要与它合成,所以留一份原样 */
        final Drawable baseBackground;

        public SelectViewHolder(@NonNull @NotNull View itemView) {
            super(itemView);
            this.baseBackground = itemView.getBackground();
        }
    }

    public interface SelectDialogInterface<T> {
        void click(T value, int pos);

        /** 行文字 */
        CharSequence getDisplay(T val);

        /**
         * 可选:行尾的高亮小字标记(如「亮色默认」「暗色默认」「使用中」),居右 + 垂直居中显示;
         * 返回空串 = 这一行没有标记(默认实现,既有调用点不用改)。
         */
        default CharSequence getMarkers(T val) {
            return "";
        }
    }

    /** 可选:长按某一项(不设则列表不响应长按;用于"长按出气泡"这类额外操作) */
    public interface OnItemLongClickListener<T> {
        /**
         * @param itemView 被长按的那一行(气泡要贴着它弹)
         * @return true = 已消费,不再走点击
         */
        boolean onItemLongClick(T value, int position, View itemView);
    }

    /**
     * 可选:行样式微调 —— 目前用来给行加一个<b>居中背景图标</b>(如太阳/月亮表示主题的亮暗)。
     * <p>不额外加视图、也不改行布局:图标被合成到行根视图的 <b>background</b> 层
     * (原来的圆角底色 + 居中图标,见 {@link #applyRowIcon}),所以它在文字与勾选框之下,
     * 行内元素的位置与其它行逐像素一致。
     */
    public interface RowStyle<T> {
        /** 行背景的居中图标资源;0 = 这一行不加图标 */
        int rowIcon(T value);

        /** 图标着色(通常取主题色);0 = 不改色,用图标自带颜色 */
        int rowIconTint(T value);

        /** 图标不透明度 0-255(当背景水印用,别给满) */
        int rowIconAlpha(T value);
    }


    public static DiffUtil.ItemCallback<String> stringDiff = new DiffUtil.ItemCallback<String>() {

        @Override
        public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }
    };


    private ArrayList<T> data = new ArrayList<>();

    private int select = 0;

    private SelectDialogInterface dialogInterface;

    /** 可选的长按回调 / 行样式(不设时行为与以前完全一致) */
    private OnItemLongClickListener<T> longClickListener;
    private RowStyle<T> rowStyle;

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback) {
        super(diffCallback);
        this.dialogInterface = dialogInterface;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener<T> l) {
        this.longClickListener = l;
    }

    public void setRowStyle(RowStyle<T> style) {
        this.rowStyle = style;
        notifyDataSetChanged();
    }

    public void setData(List<T> newData, int defaultSelect) {
        data.clear();
        data.addAll(newData);
        select = defaultSelect;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }


    @Override
    public SelectDialogAdapter.SelectViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new SelectDialogAdapter.SelectViewHolder(
                LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_select, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull SelectDialogAdapter.SelectViewHolder holder, @SuppressLint("RecyclerView") int position) {
        T value = data.get(position);
        MaterialCheckBox view = holder.itemView.findViewById(R.id.tvName);
        view.setChecked(position == select);
        view.setText(dialogInterface.getDisplay(value));
        bindMarkers(holder, value);
        applyRowIcon(holder, value);
        holder.itemView.setOnClickListener(v -> {
            if (position == select)
                return;
            notifyItemChanged(select);
            select = position;
            notifyItemChanged(position);
            dialogInterface.click(value, position);
        });
        if (longClickListener != null) {
            holder.itemView.setOnLongClickListener(v -> longClickListener.onItemLongClick(value, position, holder.itemView));
        } else {
            holder.itemView.setOnLongClickListener(null);
        }
    }

    /** 行尾标记(「亮色默认」/「暗色默认」/「使用中」):有内容才显示,样式由布局统一给(居右 + 垂直居中) */
    private void bindMarkers(SelectViewHolder holder, T value) {
        TextView markers = holder.itemView.findViewById(R.id.tv_markers);
        CharSequence text = dialogInterface.getMarkers(value);
        boolean show = text != null && text.length() > 0;
        markers.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) markers.setText(text);
    }

    /**
     * 居中背景图标:把行原本的圆角底色与图标合成成一个 LayerDrawable 当背景。
     * <p>为什么走 background 而不是加视图/画 foreground:background 在文字与勾选框<b>之下</b>,
     * 天然就是"背景水印"的效果,而且一个视图都不用加、行内布局分毫不动。
     * <p>图标按 {@link #ICON_SIZE_DP} 居中摆放(比 intrinsic 大一圈,当背景才有存在感),
     * 透明度由 {@link RowStyle#rowIconAlpha} 决定(背景图标必须压暗,否则会盖住文字的可读性)。
     */
    private void applyRowIcon(SelectDialogAdapter.SelectViewHolder holder, T value) {
        if (holder.baseBackground == null) return;
        // 行底:统一换成"由主题面派生出来的不透明色",与页面有轻微分别(用户口径:
        // "对应单个 item 背景颜色看能不能通过 bg_surface 计算出来,有点区别就行,纯色不透明")。
        // 布局里那份 bg_small_round_gray 是**半透明**的,直接用它当底会让下面透出来,所以这里整层替换。
        Drawable base = createRowBackground(holder.itemView.getContext(), holder.baseBackground);
        int iconRes = rowStyle == null ? 0 : rowStyle.rowIcon(value);
        if (iconRes == 0) {
            if (holder.itemView.getBackground() != base) holder.itemView.setBackground(base);
            return;
        }
        Context ctx = holder.itemView.getContext();
        Drawable icon = androidx.core.content.ContextCompat.getDrawable(ctx, iconRes);
        if (icon == null) {
            holder.itemView.setBackground(base);
            return;
        }
        icon = icon.mutate();
        int tint = rowStyle.rowIconTint(value);
        if (tint != 0) icon.setTint(tint);
        int alpha = Math.max(0, Math.min(255, rowStyle.rowIconAlpha(value)));
        icon.setAlpha(alpha);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{base, icon});
        int size = Math.round(ICON_SIZE_DP * ctx.getResources().getDisplayMetrics().density);
        layer.setLayerSize(1, size, size);
        layer.setLayerGravity(1, Gravity.CENTER);
        holder.itemView.setBackground(layer);
    }

    /**
     * 选择行的底:由主题的"不透明面"向页面底色轻微混合,并保留行布局原本的圆角。
     *
     * <p>为什么要保留圆角:行布局那份底是"圆角色块",直接 {@code setBackgroundColor} 会把圆角一起丢掉。
     * 所以这里用原 drawable 的 ConstantState 复制一份、只改颜色 —— 圆角/形状/内边距全都不动。
     */
    public static Drawable createRowBackground(Context ctx, Drawable base) {
        try {
            com.github.tvbox.osc.bean.theme.ThemeColorPalette palette =
                    com.github.tvbox.osc.theme.ThemeRuntime.palette();
            if (palette == null) return base;
            int mixed = mix(opaqueSurface(palette), palette.get("bg_body", 0xFFFAF8FF), ROW_MIX);
            Drawable copy = base.getConstantState() == null
                    ? base : base.getConstantState().newDrawable();
            if (copy instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) copy).setColor(mixed);
                return copy;
            }
            return base;
        } catch (Throwable th) {
            return base;
        }
    }

    /** 主题的"实心面":bg_card 的**不透明版**(行底要求纯色不透明,而 bg_card 带透明度档) */
    private static int opaqueSurface(com.github.tvbox.osc.bean.theme.ThemeColorPalette palette) {
        int card = palette.get("bg_card", 0xFFECECF4);
        return (card & 0x00FFFFFF) | 0xFF000000;
    }

    /** 两色按比例混合(返回不透明色) */
    private static int mix(int base, int target, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int a = Math.round(((base >>> 24) & 0xFF) * (1 - r) + ((target >>> 24) & 0xFF) * r);
        int red = Math.round(((base >> 16) & 0xFF) * (1 - r) + ((target >> 16) & 0xFF) * r);
        int green = Math.round(((base >> 8) & 0xFF) * (1 - r) + ((target >> 8) & 0xFF) * r);
        int blue = Math.round((base & 0xFF) * (1 - r) + (target & 0xFF) * r);
        return (a << 24) | (red << 16) | (green << 8) | blue;
    }
}
