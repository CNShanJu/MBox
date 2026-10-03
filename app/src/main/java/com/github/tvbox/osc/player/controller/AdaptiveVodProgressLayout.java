package com.github.tvbox.osc.player.controller;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.github.tvbox.osc.R;

/** 点播和本地播放共用的进度栏：空间够时单行，太窄时把时间和滑轨移到上一行。 */
final class AdaptiveVodProgressLayout {
    private static final int MIN_SEEK_WIDTH_DP = 120;

    private final LinearLayout bottomRoot;
    private final LinearLayout progressRow;
    private final LinearLayout transportRow;
    private final View spacer;
    private final TextView currentTime;
    private final SeekBar seekBar;
    private final TextView totalTime;
    private final Runnable updateRunnable = this::updateForAvailableWidth;
    private final View.OnLayoutChangeListener widthListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft) requestUpdate();
            };
    private boolean adaptive;

    AdaptiveVodProgressLayout(View controller) {
        bottomRoot = controller.findViewById(R.id.bottom_container);
        progressRow = controller.findViewById(R.id.progress_row);
        transportRow = controller.findViewById(R.id.transport_row);
        spacer = controller.findViewById(R.id.transport_spacer);
        currentTime = controller.findViewById(R.id.curr_time);
        seekBar = controller.findViewById(R.id.seekBar);
        totalTime = controller.findViewById(R.id.total_time);
        bottomRoot.addOnLayoutChangeListener(widthListener);
        currentTime.addOnLayoutChangeListener(widthListener);
        totalTime.addOnLayoutChangeListener(widthListener);
        for (int i = 0; i < transportRow.getChildCount(); i++) {
            transportRow.getChildAt(i).addOnLayoutChangeListener(widthListener);
        }
    }

    void setAdaptive(boolean adaptive) {
        if (this.adaptive == adaptive) {
            if (adaptive) requestUpdate();
            else showSeparateRow(false);
            return;
        }
        this.adaptive = adaptive;
        if (adaptive) {
            // 宽度要等控制栏真正显示并完成布局后才能计算。
            showSeparateRow(false);
            requestUpdate();
        } else {
            bottomRoot.removeCallbacks(updateRunnable);
            showSeparateRow(false);
        }
    }

    void cancelPendingUpdate() {
        bottomRoot.removeCallbacks(updateRunnable);
    }

    void requestUpdate() {
        if (!adaptive) return;
        bottomRoot.removeCallbacks(updateRunnable);
        bottomRoot.post(updateRunnable);
    }

    private void updateForAvailableWidth() {
        if (!adaptive || bottomRoot.getVisibility() == View.GONE) return;
        int available = transportRow.getWidth() - transportRow.getPaddingLeft() - transportRow.getPaddingRight();
        if (available <= 0 || currentTime.getMeasuredWidth() == 0 || totalTime.getMeasuredWidth() == 0) return;

        int occupied = occupiedWidth(currentTime) + occupiedWidth(totalTime);
        for (int i = 0; i < transportRow.getChildCount(); i++) {
            View child = transportRow.getChildAt(i);
            if (child != seekBar && child != spacer && child != currentTime && child != totalTime) {
                occupied += occupiedWidth(child);
            }
        }
        int minSeekWidth = Math.round(MIN_SEEK_WIDTH_DP * bottomRoot.getResources().getDisplayMetrics().density);
        showSeparateRow(available - occupied < minSeekWidth);
    }

    private static int occupiedWidth(View view) {
        if (view.getVisibility() == View.GONE) return 0;
        int width = view.getMeasuredWidth();
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            width += margins.getMarginStart() + margins.getMarginEnd();
        }
        return width;
    }

    private void showSeparateRow(boolean separate) {
        LinearLayout target = separate ? progressRow : transportRow;
        if (currentTime.getParent() != target) {
            LinearLayout source = (LinearLayout) currentTime.getParent();
            source.removeView(currentTime);
            source.removeView(seekBar);
            source.removeView(totalTime);
            int index = separate ? 0 : transportRow.indexOfChild(spacer);
            target.addView(currentTime, index++);
            target.addView(seekBar, index++);
            target.addView(totalTime, index);
        }
        progressRow.setVisibility(separate ? View.VISIBLE : View.GONE);
        spacer.setVisibility(separate ? View.VISIBLE : View.GONE);
    }
}
