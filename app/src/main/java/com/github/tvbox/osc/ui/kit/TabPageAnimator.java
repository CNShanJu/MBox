package com.github.tvbox.osc.ui.kit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/** 两个已加载 Tab 页面在同一裁切容器内平移，保留各自列表的滚动位置。 */
public final class TabPageAnimator {

    private static final long DURATION_MS = 240L;

    private ValueAnimator animator;
    private View outgoing;
    private View incoming;

    /** 停止尚未完成的切换，并把两页恢复到确定的最终状态。 */
    public void finish() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        complete();
    }

    /** direction: -1 左滑到下一页，1 右滑到上一页。 */
    public void slide(ViewGroup container, View from, View to, int direction) {
        finish();
        if (container == null || from == null || to == null || from == to || direction == 0
                || container.getWidth() <= 0) {
            if (from != null && from != to) from.setVisibility(View.GONE);
            if (to != null) to.setVisibility(View.VISIBLE);
            return;
        }
        outgoing = from;
        incoming = to;
        final float distance = container.getWidth();
        from.setVisibility(View.VISIBLE);
        to.setVisibility(View.VISIBLE);
        from.setTranslationX(0f);
        to.setTranslationX(-direction * distance);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float progress = (float) a.getAnimatedValue();
            from.setTranslationX(direction * distance * progress);
            to.setTranslationX(-direction * distance * (1f - progress));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                complete();
            }
        });
        animator.start();
    }

    private void complete() {
        if (outgoing != null) {
            outgoing.setTranslationX(0f);
            outgoing.setVisibility(View.GONE);
        }
        if (incoming != null) {
            incoming.setTranslationX(0f);
            incoming.setVisibility(View.VISIBLE);
        }
        outgoing = null;
        incoming = null;
    }
}
