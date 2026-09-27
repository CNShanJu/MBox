package com.github.tvbox.osc.ui.kit;

import java.util.List;

/**
 * 流式排版的**纯逻辑**(不含任何 Android 类型,可直接 JVM 单测)。
 *
 * <p>为什么单独抽出来:自研 {@link FlowTagLayout} 替代第三方 {@code com.hyman:flowlayout-lib} 的起因,
 * 就是那条库的行高算法漏算了子视图的外边距 —— 标签在"只剩边框线"的形态下,最后一行会被容器裁掉下半截,
 * 表现成"圆角只对顶部有效、下边看着是方的"。这种事靠肉眼调一天也调不准,所以把排版算术抽成纯函数,
 * 由 {@code FlowLineBreakerTest} 钉住:
 *
 * <ul>
 *   <li><b>行高 = 该行所有块"外边距 + 自身高"的最大值</b> —— 只要这条成立,块永远不会溢出容器被裁;</li>
 *   <li>换行判据用"外边距 + 自身宽"(左右外边距也要算进去,否则贴着容器右边);</li>
 *   <li>单块比容器还宽时自己占一行(不换行、不死循环,靠左按外边距定位)。</li>
 * </ul>
 */
public final class FlowLineBreaker {

    private FlowLineBreaker() {
    }

    /** 一个"带外边距的块":标签的宽度/高度 + 四边外边距(顺序与 Android 的 MarginLayoutParams 一致) */
    public static final class Item {

        public final int width;
        public final int height;
        public final int marginLeft;
        public final int marginTop;
        public final int marginRight;
        public final int marginBottom;

        public Item(int width, int height, int marginLeft, int marginTop, int marginRight, int marginBottom) {
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.marginLeft = Math.max(0, marginLeft);
            this.marginTop = Math.max(0, marginTop);
            this.marginRight = Math.max(0, marginRight);
            this.marginBottom = Math.max(0, marginBottom);
        }

        /** 四边同一外边距的简写(标签之间 4dp 间距这种) */
        public Item(int width, int height, int margin) {
            this(width, height, margin, margin, margin, margin);
        }

        /** 占位宽度(含左右外边距) */
        public int outerWidth() {
            return marginLeft + width + marginRight;
        }

        /** 占位高度(含上下外边距)—— 行高按它算,块才不会被裁 */
        public int outerHeight() {
            return marginTop + height + marginBottom;
        }
    }

    /** 排版结果:逐块的左上角(不含容器内边距)、容器内容高度、行数 */
    public static final class Result {

        public final int[] left;
        public final int[] top;
        public final int[] lineOf;
        public final int height;
        public final int lineCount;

        Result(int[] left, int[] top, int[] lineOf, int height, int lineCount) {
            this.left = left;
            this.top = top;
            this.lineOf = lineOf;
            this.height = height;
            this.lineCount = lineCount;
        }
    }

    /**
     * 把若干块排成多行。
     *
     * @param items    顺序即摆放顺序
     * @param maxWidth 每行的可用宽度(容器内容宽,已扣掉内边距);&lt;= 0 视为不限宽(全部排一行)
     */
    public static Result layout(List<Item> items, int maxWidth) {
        int count = items == null ? 0 : items.size();
        int[] left = new int[count];
        int[] top = new int[count];
        int[] lineOf = new int[count];
        if (count == 0) {
            return new Result(left, top, lineOf, 0, 0);
        }
        final boolean wrap = maxWidth > 0;
        int x = 0;
        int y = 0;
        int lineHeight = 0;
        int line = 0;
        for (int i = 0; i < count; i++) {
            Item it = items.get(i);
            // 换行:本行已有内容,且再放一块会超出可用宽度(单块超宽时不换行,自己占一行)
            if (wrap && x > 0 && x + it.outerWidth() > maxWidth) {
                y += lineHeight;
                x = 0;
                lineHeight = 0;
                line++;
            }
            left[i] = x + it.marginLeft;
            top[i] = y + it.marginTop;
            lineOf[i] = line;
            x += it.outerWidth();
            lineHeight = Math.max(lineHeight, it.outerHeight());
        }
        return new Result(left, top, lineOf, y + lineHeight, line + 1);
    }
}
