package com.github.tvbox.osc.ui.dialog;

/** “上次看到”卡片相对直播悬浮球的位置，参数与结果均为屏幕像素坐标。 */
final class LastViewedBubblePlacement {
    private LastViewedBubblePlacement() {
    }

    static Position calculate(int viewportLeft, int viewportRight,
                              int ballLeft, int ballTop, int ballBottom,
                              int cardWidth, int cardHeight,
                              int sideInset, int horizontalGap, int verticalGap) {
        int centeredLeft = viewportLeft + (viewportRight - viewportLeft - cardWidth) / 2;
        int centeredRight = centeredLeft + cardWidth;
        boolean sameRow = centeredLeft >= viewportLeft + sideInset
                && centeredRight <= viewportRight - sideInset
                && centeredRight + horizontalGap <= ballLeft;
        if (sameRow) {
            return new Position(centeredLeft,
                    ballTop + (ballBottom - ballTop - cardHeight) / 2, true);
        }
        return new Position(centeredLeft, ballTop - verticalGap - cardHeight, false);
    }

    static final class Position {
        final int left;
        final int top;
        final boolean sameRow;

        Position(int left, int top, boolean sameRow) {
            this.left = left;
            this.top = top;
            this.sameRow = sameRow;
        }
    }
}
