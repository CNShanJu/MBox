package com.github.tvbox.osc.ui.kit;

import android.view.MotionEvent;
import android.view.View;

/**
 * 「点击型」小组件按钮的点击特效:按下去**整键**(底 + 文字 + 图标)透明度 80%,抬手或滑出取消时恢复 100%。
 *
 * <p>为什么用 {@link View#setAlpha} 而不是在 selector 里写按下态:用户口径要的是"整体做一次透明度 80% 的点击特效",
 * 也就是连文字与图标一起变淡;而 drawable 的 {@code state_pressed} 只能换底,换不了字与图标。
 *
 * <p>返回 {@code false} 不消费事件 —— 原有的 OnClickListener、水波纹(foreground)、长按照常工作。
 * 只给"点击型"(搜索历史/热词/联想、字幕/音轨/重播/刷新/重置、粘贴)用;"选择型"(日志分类键、
 * 背景图预设、播放器倍速)靠选中态换色反馈,不套这个特效。
 */
public final class WidgetPressEffect {

    /** 按下时的整体透明度(用户口径:80%) */
    private static final float PRESSED_ALPHA = 0.8f;

    private WidgetPressEffect() {
    }

    /** 给一个小组件按钮挂上点击特效(重复调用无害;空视图直接忽略) */
    public static void attach(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(PRESSED_ALPHA);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
