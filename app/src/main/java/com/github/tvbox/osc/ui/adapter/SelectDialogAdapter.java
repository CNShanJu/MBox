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
        if (rowStyle == null || holder.baseBackground == null) return;
        int iconRes = rowStyle.rowIcon(value);
        if (iconRes == 0) {
            if (holder.itemView.getBackground() != holder.baseBackground) {
                holder.itemView.setBackground(holder.baseBackground);
            }
            return;
        }
        Context ctx = holder.itemView.getContext();
        Drawable icon = androidx.core.content.ContextCompat.getDrawable(ctx, iconRes);
        if (icon == null) return;
        icon = icon.mutate();
        int tint = rowStyle.rowIconTint(value);
        if (tint != 0) icon.setTint(tint);
        icon.setAlpha(Math.max(0, Math.min(255, rowStyle.rowIconAlpha(value))));
        LayerDrawable layer = new LayerDrawable(new Drawable[]{holder.baseBackground, icon});
        int size = Math.round(ICON_SIZE_DP * ctx.getResources().getDisplayMetrics().density);
        layer.setLayerSize(1, size, size);
        layer.setLayerGravity(1, Gravity.CENTER);
        holder.itemView.setBackground(layer);
    }
}
