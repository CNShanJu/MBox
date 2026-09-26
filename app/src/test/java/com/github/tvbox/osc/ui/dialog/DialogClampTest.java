package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 弹窗内容区限高计算单测。
 * 背景:更新说明弹窗(标题 + 可滚动说明 + 稍后/立即更新)说明很长时按钮被顶出屏幕
 * ("按钮没有露出来"),故"内容区可用高度"必须按「上限 − 固定区」算准。
 */
public class DialogClampTest {

    /** 整体放得下:保持自然高度,不限高 */
    @Test
    public void keepsNaturalHeightWhenFits() {
        // 固定区 400 + 说明 800 = 1200 ≤ 上限 1404
        DialogClamp.Clamp c = DialogClamp.clampScrollHeight(1404, 400, 800);
        assertFalse(c.clamped);
        assertEquals(800, c.height);
    }

    /** 说明超长:压到「上限 − 固定区」,固定区(标题+按钮)空间被保住 */
    @Test
    public void squeezesScrollAreaWhenOverflow() {
        DialogClamp.Clamp c = DialogClamp.clampScrollHeight(1404, 400, 2000);
        assertTrue(c.clamped);
        assertEquals(1004, c.height);
        assertTrue("压完应不超过上限", 400 + c.height <= 1404);
    }

    /** 多版本说明(拼接 5 个版本)是真实场景:说明更长,仍必须留出按钮位置 */
    @Test
    public void handlesVeryLongMultiVersionNotes() {
        DialogClamp.Clamp c = DialogClamp.clampScrollHeight(1404, 420, 6000);
        assertTrue(c.clamped);
        assertEquals(984, c.height);
        assertTrue(420 + c.height <= 1404);
    }

    /** 说明区在可用高度内:回推自然高度(权重新布局会把卡片压到最小值,必须能长回来) */
    @Test
    public void restoresNaturalHeightWhenFitsInsideAllowed() {
        DialogClamp.Clamp c = DialogClamp.clampScrollHeight(1404, 400, 600);
        assertFalse(c.clamped);
        assertEquals(600, c.height);          // 不是 1004(可用高度),而是内容真实需要的高度
        assertEquals(1000, 400 + c.height);   // 卡片按内容撑开
    }

    /** maxH<=0(未配置上限):不限高 */
    @Test
    public void noLimitWhenMaxIsZero() {
        assertFalse(DialogClamp.clampScrollHeight(0, 400, 2000).clamped);
        assertFalse(DialogClamp.clampScrollHeight(-1, 400, 2000).clamped);
    }

    /** 固定区自己吃满上限(极端窄屏/超大字号):不硬压,交由布局兜底,不能算出负高度 */
    @Test
    public void fixedAreaEatsAllSpace() {
        DialogClamp.Clamp c = DialogClamp.clampScrollHeight(400, 500, 1500);
        assertFalse(c.clamped);
        assertEquals(1500, c.height);
    }

    /** 刚好卡在上限:不限高(边界) */
    @Test
    public void exactlyAtLimit() {
        DialogClamp.Clamp c = DialogClamp.clampScrollHeight(1404, 404, 1000);
        assertFalse(c.clamped);
        assertEquals(1000, c.height);
    }

    /** 说明区自然高度为 0(空内容):不处理 */
    @Test
    public void ignoresEmptyScrollArea() {
        assertFalse(DialogClamp.clampScrollHeight(1404, 400, 0).clamped);
    }
}
