package com.github.tvbox.osc.ui.kit;

import android.view.MotionEvent;
import android.view.View;

/**
 * 主题按钮的点击特效:按下去整键(边框 + 文字 + 图标)透明度 90%,抬手后恢复。
 *
 * <p>为什么用 {@link View#setAlpha} 而不是在 selector 里写按下态:空心键的描边和文字需同步变化，
 * drawable 的 {@code state_pressed} 只能换底,换不了字与图标。
 *
 * <p>返回 {@code false} 不消费事件 —— 原有的 OnClickListener、水波纹(foreground)、长按照常工作。
 * 主题布局工厂会给标准按钮挂载；代码创建的小组件仍可手动挂载。
 */
public final class WidgetPressEffect {

    /** 按下时整体降低 10% 透明度。 */
    private static final float PRESSED_ALPHA = 0.9f;

    private WidgetPressEffect() {
    }

    /** 给按钮挂上点击特效(重复调用无害;空视图直接忽略)。 */
    public static void attach(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.setAlpha(PRESSED_ALPHA);
                    break;
                case MotionEvent.ACTION_MOVE:
                    boolean inside = event.getX() >= 0 && event.getX() < v.getWidth()
                            && event.getY() >= 0 && event.getY() < v.getHeight();
                    v.animate().cancel();
                    v.setAlpha(inside ? PRESSED_ALPHA : 1f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().alpha(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
