package com.github.tvbox.osc.util;

import android.view.ViewGroup;

import com.airbnb.lottie.LottieAnimationView;

/** Keeps a configured loading animation inside the space actually assigned to it. */
public final class LoadingAnimFit {
    private LoadingAnimFit() {
    }

    /** Refit when a fixed loading slot changes height (for example a dragged bottom sheet). */
    public static void bindToSlot(LottieAnimationView animation, ViewGroup slot, int insetDp) {
        if (animation == null || slot == null) return;
        slot.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) ->
                fitToSlot(animation, slot, insetDp));
        fitToSlot(animation, slot, insetDp);
    }

    public static void fitToSlot(LottieAnimationView animation, ViewGroup slot, int insetDp) {
        if (animation == null || slot == null) return;
        int inset = Math.max(0, Math.round(insetDp * slot.getResources().getDisplayMetrics().density));
        int width = slot.getWidth() - slot.getPaddingLeft() - slot.getPaddingRight() - 2 * inset;
        int height = slot.getHeight() - slot.getPaddingTop() - slot.getPaddingBottom() - 2 * inset;
        ViewGroup.LayoutParams params = animation.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            width -= margins.leftMargin + margins.rightMargin;
            height -= margins.topMargin + margins.bottomMargin;
        }
        fitWithinPixels(animation, width, height);
    }

    /** Returns the resulting square side, preserving the configured size when it fits. */
    public static int fitWithinPixels(LottieAnimationView animation, int maxWidth, int maxHeight) {
        if (animation == null || maxWidth <= 0 || maxHeight <= 0) return 0;
        ViewGroup.LayoutParams params = animation.getLayoutParams();
        if (params == null) return 0;
        int configured = Math.round(LoadingAnim.getSizeDp()
                * animation.getResources().getDisplayMetrics().density);
        int size = Math.max(1, Math.min(configured, Math.min(maxWidth, maxHeight)));
        if (params.width != size || params.height != size) {
            params.width = size;
            params.height = size;
            animation.setLayoutParams(params);
        }
        return size;
    }
}
