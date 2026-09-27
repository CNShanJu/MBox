package com.github.tvbox.osc.ui.dialog;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * RecyclerView 自绘滚动指示条(滚动条 thumb)。
 * <p>背景:androidx RecyclerView 的系统滚动条仅在触摸滚动瞬间绘制,静止时即使内容超高也不显示,
 * 无法提示"可滚动"。本类在列表右侧叠一条细 thumb——内容超高时随滚动出现、位置与长度跟着更新。
 *
 * <h3>什么时候显示(与系统滚动条同口径)</h3>
 * <ul>
 *   <li><b>只在滚动时显示</b>,停手后 {@link #HIDE_DELAY_MS} 毫秒淡出 —— 用户口径:
 *       "会一直显示,不合理"。所以打开弹窗那一刻<b>不预亮</b>(内容够长不等于用户在看它),
 *       免得每个设置弹窗右侧都挂着一条常亮的灰条;</li>
 *   <li>内容不再超高(不 scrollable)时立刻隐藏;</li>
 *   <li>不可见/滑出屏幕时撤掉待执行的淡出任务(弹窗关掉后不留悬着的回调)。</li>
 * </ul>
 *
 * <p>颜色见 {@code drawable/bg_scroll_thumb}(主题色,亮/暗与自定义主题都跟着走)。
 *
 * <p>用法:布局里给目标 RecyclerView 同级放一个 {@code scroll_thumb} View(约束 top/right 到列表),
 * 然后 {@code ScrollThumbIndicator.attach(listView, thumbView)}。
 */
public final class ScrollThumbIndicator {

    /** 停手后多久淡出(与系统滚动条"滚完就消失"的观感一致) */
    private static final long HIDE_DELAY_MS = 700L;
    /** 淡出时长 */
    private static final long FADE_MS = 180L;
    /** thumb 的 tag key:是否已挂过监听(重排后会再调一次 attach,不能重复挂) */
    private static final int TAG_ATTACHED = R.id.scroll_thumb;
    /** 每个 thumb 待执行的淡出任务(WeakHashMap:弹窗销毁后不拦着回收) */
    private static final Map<View, Runnable> HIDE_TASKS = new WeakHashMap<>();

    private ScrollThumbIndicator() {
    }

    /** 挂到列表:内容超高时随滚动显示 thumb,滚动时更新位置;不超高隐藏 */
    public static void attach(@NonNull RecyclerView list, @NonNull View thumb) {
        if (list == null || thumb == null) return;
        // 固定高模式会在重排后再次 attach:同一个 thumb 只挂一次,避免监听器越积越多
        if (Boolean.TRUE.equals(thumb.getTag(TAG_ATTACHED))) return;
        thumb.setTag(TAG_ATTACHED, Boolean.TRUE);
        // 打开时不预亮:等用户真的滚了再出现
        thumb.setAlpha(0f);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                update(rv, thumb, true);
            }
        });
        thumb.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                cancelHide(v);
                v.setAlpha(0f);
            }
        });
        // 布局完成后先算一次几何(数据已 set、clamp 已应用);这一次不算"滚动",不点亮 thumb
        thumb.post(() -> update(list, thumb, false));
    }

    /**
     * 重算 thumb 的长度与位置。
     *
     * @param fromScroll true = 由滚动触发(点亮 thumb 并安排淡出);
     *                   false = 由布局/测量触发(只摆位置,保持当前是否可见)
     */
    private static void update(RecyclerView list, View thumb, boolean fromScroll) {
        if (list == null || thumb == null) return;
        int extent = list.computeVerticalScrollExtent();   // 可视高
        int range = list.computeVerticalScrollRange();     // 内容全高
        int offset = list.computeVerticalScrollOffset();   // 已滚高
        int track = list.getHeight();
        if (track <= 0 || range <= extent) {
            // 内容装得下:指示条没有意义,直接收掉
            cancelHide(thumb);
            thumb.setAlpha(0f);
            thumb.setVisibility(View.GONE);
            return;
        }
        thumb.setVisibility(View.VISIBLE);
        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) thumb.getLayoutParams();
        int minThumb = Math.round(thumb.getResources().getDisplayMetrics().density * 24);
        int thumbH = Math.max(minThumb, Math.round(track * (float) extent / range));
        // 可滚余量内的位置比例:offset/(range-extent) → [0,1],映射到 track-thumbH
        int scrollable = range - extent;
        int maxTop = track - thumbH;
        int top = scrollable > 0 ? Math.round(maxTop * (float) offset / scrollable) : 0;
        // 只有几何真的变了才 setLayoutParams:它一动就 requestLayout,而本方法是**每帧**被 onScrolled 调的,
        // 每帧都设一次等于让整个弹窗每帧重新测量/布局(滚动时白烧 CPU、容易掉帧)。
        // 像素级结果与以前逐帧设完全一致(值一样就不需要再设一次)。
        if (lp.height != thumbH || lp.topMargin != top) {
            lp.height = thumbH;
            lp.topMargin = top;
            thumb.setLayoutParams(lp);
        }
        // 只有"用户真的在滚"才点亮;布局/测量触发的这一次只摆位置,
        // 保持 attach 时定下的"透明"(打开弹窗不预亮)
        if (fromScroll) {
            showTransiently(thumb);
        }
    }

    /** 点亮并安排淡出(连续滚动会不断顺延) */
    private static void showTransiently(View thumb) {
        cancelHide(thumb);
        thumb.animate().cancel();
        thumb.setAlpha(1f);
        Runnable hide = () -> {
            synchronized (HIDE_TASKS) {
                HIDE_TASKS.remove(thumb);
            }
            thumb.animate().alpha(0f).setDuration(FADE_MS).start();
        };
        synchronized (HIDE_TASKS) {
            HIDE_TASKS.put(thumb, hide);
        }
        thumb.postDelayed(hide, HIDE_DELAY_MS);
    }

    /** 撤掉待执行的淡出(不清 alpha:是否可见由各调用点自己决定) */
    private static void cancelHide(View thumb) {
        Runnable pending;
        synchronized (HIDE_TASKS) {
            pending = HIDE_TASKS.remove(thumb);
        }
        if (pending != null) thumb.removeCallbacks(pending);
    }
}
