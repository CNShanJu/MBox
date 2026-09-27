package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.menu.MenuItemImpl;
import androidx.appcompat.widget.TooltipCompat;

import com.google.android.material.bottomnavigation.BottomNavigationItemView;
import com.google.android.material.bottomnavigation.BottomNavigationMenuView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarItemView;
import com.google.android.material.navigation.NavigationBarMenuView;

/**
 * 底栏:<b>整条区域</b>长按都放一簇烟花(发射点=手指按下的位置),不再弹系统提示。
 * <p>
 * 两件事分开做:
 * <ul>
 *   <li><b>长按检测放在整条底栏上</b>({@link #dispatchTouchEvent}):Material 的条目有最大宽度
 *       (design_bottom_navigation_active_item_max_width),条目数量少时只占中间一段,两侧是空白 ——
 *       检测挂在条目上就"只有条目那块区域生效"。挂在底栏自己身上,条目上与空白处一视同仁,发射点也
 *       直接是手指在底栏内的坐标。</li>
 *   <li><b>清系统提示(Tooltip)放在条目上</b>({@link NavigationBarItemView#initialize}):
 *       Material 每次初始化条目(首次构建菜单、切 tab 或换图标引起的菜单更新)都会调
 *       {@code TooltipCompat.setTooltipText(itemView, 标题)} 把提示重新挂上;在外面清一次会被后续的
 *       菜单更新覆盖(API&lt;26 的 AppCompat 路径上,那一步还会把长按监听一并顶掉,提示又回来了)。
 *       在 {@code super.initialize} 之后立刻清并接管长按(返回 true 即吞掉,框架便不再走 Tooltip 分支),
 *       天然跟着每一次菜单更新走。</li>
 * </ul>
 * 触摸长按统一由底栏发射;条目上的长按只在<b>非触摸</b>(遥控器/键盘长按,没有手指位置)时兜底,
 * 从条目中心发射,避免同一次长按放两簇。
 * <p>
 * <b>震动同样覆盖整条底栏</b>:条目上的长按由框架给反馈(条目长按回调返回 true 时框架震一下),
 * 空白区没有子视图接手势、框架不知道,由本类在长按触发时自己补一次;靠
 * {@link #onTouchEvent} 只在"没有子视图消费"时才会被调用这一点区分两者,所以不会一次长按震两下。
 */
public class FireworksBottomNavigationView extends BottomNavigationView {

    /**
     * 换肤:选中/未选中项的文字与图标色。
     *
     * <p>为什么写在这里:这两个色由 {@code app:itemTextColor} / {@code app:itemIconTint} 给,
     * 值是 {@code @drawable/bottom_navigation_item_selector} —— 而实测**这个容器拿不到换肤注入**
     * (它在 MainActivity 的布局里,注入器抓不到;同一个原因让这条底栏的"底"也一直是内置色,
     * 后来靠 ThemeSweep 补色才修好)。所以在组件自己身上按主题色显式设一遍最稳、也不依赖 inflater。
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyThemedItemColors();
    }

    /** 用主题色重建底栏的选择器,再塞回 Material 的两个入口(重建不出时退回系统那份) */
    private void applyThemedItemColors() {
        try {
            if (!com.github.tvbox.osc.theme.ThemeRuntime.active()) return;
            int selector = com.github.tvbox.osc.R.drawable.bottom_navigation_item_selector;
            android.content.res.ColorStateList csl =
                    com.github.tvbox.osc.theme.ThemeDrawables.rebuildColorStateList(selector, getResources());
            if (csl == null) {
                csl = androidx.core.content.ContextCompat.getColorStateList(getContext(), selector);
            }
            if (csl == null) return;
            setItemIconTintList(csl);
            setItemTextColor(csl);
        } catch (Throwable ignored) {
        }
    }

    /** 与框架长按同一时长:触发时机和条目自身的震动反馈对得上 */
    private static final long LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

    /** 按下不动到时长就放烟花;此时手指位置已记在 touchX/touchY */
    private final Runnable longPressLaunch = new Runnable() {
        @Override
        public void run() {
            if (!pressing) return;
            pressing = false;
            // 震动跟着整条底栏走:条目上的长按由框架给反馈(条目的长按回调返回 true 时框架会震一下),
            // 而按在条目没铺满的空白区时没有任何子视图接这个手势,框架不知道,这里自己补上,
            // 否则"只有条目长按才震"。两处都只在真正长按时震一次,不会叠成两下。
            if (!itemTouch) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            FireworksView.celebrate(FireworksBottomNavigationView.this, touchX, touchY);
        }
    };

    private boolean pressing;
    /** 本次手势的 DOWN 是否落在条目上(条目上的长按震动交给框架,避免同一次长按震两下) */
    private boolean itemTouch;
    private float downX, downY, touchX, touchY;

    public FireworksBottomNavigationView(@NonNull Context context) {
        super(context);
    }

    public FireworksBottomNavigationView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FireworksBottomNavigationView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = touchX = ev.getX();
                downY = touchY = ev.getY();
                pressing = true;
                itemTouch = true;   // 先当落在条目上;真落到空白区会在 onTouchEvent 里翻掉
                removeCallbacks(longPressLaunch);
                postDelayed(longPressLaunch, LONG_PRESS_TIMEOUT);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pressing) {
                    touchX = ev.getX();
                    touchY = ev.getY();
                    if (Math.abs(touchX - downX) > touchSlop || Math.abs(touchY - downY) > touchSlop) {
                        stopLongPress();   // 划动(切 tab 的滑动、拖拽误触)不算长按
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopLongPress();
                break;
            default:
                break;
        }
        // 一律放行给子视图:条目点击/选中逻辑照旧,长按由上面自己计时(不依赖 View 的长按回调,也就不受
        // Material 在菜单更新时重设监听的影响)
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 条目没铺满底栏时,空白区不会被任何子视图消费 —— 这里自己收下这个手势,否则 DOWN 之后框架不再把
     * MOVE/UP 派发过来,长按就没法在手指划走时取消。只影响空白区:落在条目上的手势由条目自己消费。
     * <p>
     * "没有子视图消费才会走到这里"正好可以用来判断这一按落在条目上还是空白区({@link #itemTouch}),
     * 长按震动据此决定是让框架给还是自己补。
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) itemTouch = false;
        return true;
    }

    private void stopLongPress() {
        pressing = false;
        removeCallbacks(longPressLaunch);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLongPress();
        super.onDetachedFromWindow();
    }

    /** 只为了把条目换成 {@link FireworksItemView},布局与测量仍旧走 Material 的 BottomNavigationMenuView */
    @NonNull
    @Override
    protected NavigationBarMenuView createNavigationBarMenuView(@NonNull Context context) {
        return new FireworksMenuView(context);
    }

    private static class FireworksMenuView extends BottomNavigationMenuView {

        FireworksMenuView(Context context) {
            super(context);
        }

        @NonNull
        @Override
        protected NavigationBarItemView createNavigationBarItemView(@NonNull Context context) {
            return new FireworksItemView(context);
        }
    }

    /** 条目视图:只负责"别弹系统 Tooltip" + 非触摸长按的兜底发射 */
    private static class FireworksItemView extends BottomNavigationItemView {

        /** 本次手势是不是触摸(触摸长按已由整条底栏处理,这里就别再放一簇) */
        private boolean touchGesture;

        FireworksItemView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchGesture = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchGesture = false;   // 长按回调在 UP 之前,清在这里不会漏判
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public void initialize(@NonNull MenuItemImpl itemData, int menuType) {
            super.initialize(itemData, menuType);
            // 先清 Tooltip:API<26 走 AppCompat 兼容路径,这一步会顺带摘掉它自己的长按监听并关掉 longClickable
            TooltipCompat.setTooltipText(this, null);
            setOnLongClickListener(v -> {
                if (!touchGesture) FireworksView.celebrate(v);   // 遥控器/键盘长按:没有手指位置,从条目中心发射
                return true;   // 吞掉长按:framework 见长按已被处理,便不再走 Tooltip 分支(顺带给出震动反馈)
            });
            setLongClickable(true);
        }
    }
}
