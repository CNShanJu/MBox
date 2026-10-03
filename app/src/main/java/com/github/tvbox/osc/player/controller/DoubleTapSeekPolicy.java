package com.github.tvbox.osc.player.controller;

/** 点播双击的左右弧形区域与连续跳转计算；不依赖播放器或 Android 视图。 */
final class DoubleTapSeekPolicy {
    static final long STEP_MS = 10_000L;
    private static final long CHAIN_WINDOW_MS = 900L;

    private long lastTapAt = -1L;
    private long lastTarget;
    private int lastDirection;
    private int sameDirectionTaps;

    /** 侧边在视频中线伸入约三分之一宽度；以播放器实际尺寸为准。 */
    static float sideRadiusX(float width, float height) {
        if (width <= 0 || height <= 0) return 0;
        return width / 3f;
    }

    /** 横屏保留圆弧感，竖屏让弧线贯穿画面上下边缘，但不强行接到四角。 */
    static float sideRadiusY(float width, float height) {
        if (width <= 0 || height <= 0) return 0;
        return Math.max(height * 0.53f, sideRadiusX(width, height) * 0.95f);
    }

    /** 遮罩在当前高度伸入播放器的宽度；双击命中区使用同一轮廓。 */
    static float sideWidth(float y, float width, float height) {
        if (width <= 0 || height <= 0) return 0;
        float distance = (y - height / 2f) / sideRadiusY(width, height);
        return sideRadiusX(width, height)
                * (float) Math.sqrt(Math.max(0f, 1f - distance * distance));
    }

    static int direction(float x, float y, float width, float height) {
        if (width <= 0 || height <= 0 || x < 0 || x > width || y < 0 || y > height) return 0;
        float side = sideWidth(y, width, height);
        if (side <= 0) return 0;
        if (x < side) return -1;
        if (x >= width - side) return 1;
        return 0;
    }

    Result seek(int direction, long currentMs, long durationMs, long eventTimeMs) {
        if ((direction != -1 && direction != 1) || durationMs <= 0) return null;
        boolean chained = lastTapAt >= 0 && eventTimeMs >= lastTapAt
                && eventTimeMs - lastTapAt <= CHAIN_WINDOW_MS;
        long current = Math.max(0L, Math.min(currentMs, durationMs));
        long base = chained ? lastTarget : current;
        long target = direction < 0 ? Math.max(0L, base - STEP_MS)
                : durationMs - base <= STEP_MS ? durationMs : base + STEP_MS;
        sameDirectionTaps = chained && direction == lastDirection ? sameDirectionTaps + 1 : 1;
        lastTapAt = eventTimeMs;
        lastTarget = target;
        lastDirection = direction;
        return new Result(target, direction, sameDirectionTaps, target != base);
    }

    void reset() {
        lastTapAt = -1L;
        lastTarget = 0L;
        lastDirection = 0;
        sameDirectionTaps = 0;
    }

    static final class Result {
        final long targetMs;
        final int direction;
        final int taps;
        final boolean moved;

        Result(long targetMs, int direction, int taps, boolean moved) {
            this.targetMs = targetMs;
            this.direction = direction;
            this.taps = taps;
            this.moved = moved;
        }
    }
}
