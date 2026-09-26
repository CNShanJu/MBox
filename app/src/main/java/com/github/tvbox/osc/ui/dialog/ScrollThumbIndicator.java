package com.github.tvbox.osc.ui.dialog;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView 自绘滚动指示条(滚动条 thumb)。
 * <p>背景:androidx RecyclerView 的系统滚动条仅在触摸滚动瞬间绘制,静止时即使内容超高也不显示,
 * 无法提示"可滚动"。本类在列表右侧叠一条细 thumb——内容超高时显示、随滚动更新位置/长度,否则隐藏。
 * <p>用法:布局里给目标 RecyclerView 同级放一个 {@code scroll_thumb} View(约束 top/right 到列表),
 * 然后 {@code ScrollThumbIndicator.attach(listView, thumbView)}。
 */
public final class ScrollThumbIndicator {

    private ScrollThumbIndicator() {
    }

    /** 挂到列表:内容超高显示 thumb,滚动时更新位置;不超高隐藏 */
    public static void attach(@NonNull RecyclerView list, @NonNull View thumb) {
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                update(rv, thumb);
            }
        });
        // 布局完成后先算一次(数据已 set、clamp 已应用)
        thumb.post(() -> update(list, thumb));
    }

    private static void update(RecyclerView list, View thumb) {
        if (list == null || thumb == null) return;
        int extent = list.computeVerticalScrollExtent();   // 可视高
        int range = list.computeVerticalScrollRange();     // 内容全高
        int offset = list.computeVerticalScrollOffset();   // 已滚高
        int track = list.getHeight();
        if (track <= 0 || range <= extent) {
            thumb.setVisibility(View.GONE);
            return;
        }
        thumb.setVisibility(View.VISIBLE);
        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) thumb.getLayoutParams();
        int minThumb = Math.round(thumb.getResources().getDisplayMetrics().density * 24);
        int thumbH = Math.max(minThumb, Math.round(track * (float) extent / range));
        lp.height = thumbH;
        // 可滚余量内的位置比例:offset/(range-extent) → [0,1],映射到 track-thumbH
        int scrollable = range - extent;
        int maxTop = track - thumbH;
        int top = scrollable > 0 ? Math.round(maxTop * (float) offset / scrollable) : 0;
        lp.topMargin = top;
        thumb.setLayoutParams(lp);
    }
}
