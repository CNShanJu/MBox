package com.github.tvbox.osc.ui.kit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 自研流式容器({@link FlowTagLayout})的排版算术绊线(纯 JVM)。
 *
 * <p>它替代第三方 {@code flowlayout-lib} 的直接原因就是<b>行高漏算子视图外边距</b>:
 * 标签只剩一圈边框线时,最后一行会被容器裁掉下半截,看着就是"圆角只对顶部有效、下边是方的",
 * 而且改条目外边距观感跟着变 —— 这类"没有报错只是看着不对"的坑,只能靠断言钉住。
 */
public class FlowLineBreakerTest {

    private static FlowLineBreaker.Item item(int w, int h, int margin) {
        return new FlowLineBreaker.Item(w, h, margin);
    }

    /**
     * 回归:行高必须 = 子视图高 + 上下外边距。
     * (旧库只按子视图高算行高 → 带 margin 的最后一行溢出容器被裁,标签底部圆角被切掉。)
     */
    @Test
    public void lineHeightIncludesChildMargins() {
        // 34dp 的标签 + 四边 4dp:占位高 42dp
        FlowLineBreaker.Result r = FlowLineBreaker.layout(
                java.util.Collections.singletonList(item(100, 34, 4)), 500);
        assertEquals("容器高度必须是 34+4+4=42,少算外边距就会把标签下半截裁掉", 42, r.height);
        assertEquals("标签左上角要落在自己的外边距内", 4, r.left[0]);
        assertEquals(4, r.top[0]);
        assertEquals(1, r.lineCount);
    }

    @Test
    public void wrapsWhenLineOverflows() {
        List<FlowLineBreaker.Item> items = Arrays.asList(
                item(40, 34, 4), item(40, 34, 4), item(40, 34, 4));
        // 每块占位宽 48:一行放得下两块(96 ≤ 100),第三块换行
        FlowLineBreaker.Result r = FlowLineBreaker.layout(items, 100);
        assertEquals(2, r.lineCount);
        assertEquals("两行 × 42", 84, r.height);
        assertEquals(0, r.lineOf[0]);
        assertEquals(0, r.lineOf[1]);
        assertEquals(1, r.lineOf[2]);
        assertEquals("第二行第一块的左边距", 4, r.left[2]);
        assertEquals("第二行第一块的顶边 = 第一行行高 + 自己的上边距", 42 + 4, r.top[2]);
        assertEquals("同一行第二块紧跟其后(前块 4+40+4=48)", 48 + 4, r.left[1]);
    }

    /** 单块比容器还宽:自己占一行,靠左按外边距放,不能死循环也不能被挤走 */
    @Test
    public void singleWiderItemStillTakesOneLine() {
        FlowLineBreaker.Result r = FlowLineBreaker.layout(
                java.util.Collections.singletonList(item(300, 34, 4)), 100);
        assertEquals(1, r.lineCount);
        assertEquals(42, r.height);
        assertEquals(4, r.left[0]);
    }

    /** 同一行高度不一致时,行高取最大的占位高(否则矮的会被拉齐、高的会被裁) */
    @Test
    public void lineHeightIsMaxOfItems() {
        List<FlowLineBreaker.Item> items = Arrays.asList(
                item(30, 34, 4), item(30, 44, 4));
        FlowLineBreaker.Result r = FlowLineBreaker.layout(items, 500);
        assertEquals(1, r.lineCount);
        assertEquals("取 44+8 而不是 34+8", 52, r.height);
    }

    /** 四边外边距分别生效(左右参与摆放,上下参与行高) */
    @Test
    public void marginsOffsetChildInsideItsLine() {
        List<FlowLineBreaker.Item> items = new ArrayList<>();
        items.add(new FlowLineBreaker.Item(50, 20, 10, 6, 2, 8));
        items.add(new FlowLineBreaker.Item(50, 20, 10, 6, 2, 8));
        FlowLineBreaker.Result r = FlowLineBreaker.layout(items, 500);
        assertEquals(10, r.left[0]);
        assertEquals(6, r.top[0]);
        assertEquals("前块占位宽 10+50+2=62", 62 + 10, r.left[1]);
        assertEquals("行高 = 6+20+8", 34, r.height);
    }

    @Test
    public void emptyGivesZeroHeight() {
        FlowLineBreaker.Result r = FlowLineBreaker.layout(new ArrayList<FlowLineBreaker.Item>(), 100);
        assertEquals(0, r.height);
        assertEquals(0, r.lineCount);
        assertEquals(0, r.left.length);
    }

    /** 不限宽(容器给 UNSPECIFIED)时全部排一行 */
    @Test
    public void unboundedWidthKeepsOneLine() {
        List<FlowLineBreaker.Item> items = Arrays.asList(
                item(200, 34, 4), item(200, 34, 4));
        FlowLineBreaker.Result r = FlowLineBreaker.layout(items, 0);
        assertEquals(1, r.lineCount);
        assertEquals(42, r.height);
    }
}
