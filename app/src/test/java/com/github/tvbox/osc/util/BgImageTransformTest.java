package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 背景图变换纯计算单测(纯 JVM,不碰真机):覆盖设置页拖动/缩放直接依赖的语义。
 * <p>
 * 视图取竖屏 1080×2400;涉及横竖屏的用例另取平板 1600×2560 / 2560×1600。
 * <p>
 * 位置模型是<b>锚点比例</b>(0=起始边贴边、0.5=居中、1=结束边贴边),不依赖屏幕尺寸 ——
 * 这正是"竖屏摆好的图转横屏不再自己往中间跑"的保证(见 {@code anchorKeepsCornerAcrossRotation})。
 */
public class BgImageTransformTest {

    private static final int VW = 1080;
    private static final int VH = 2400;

    /** 普通壁纸(比例接近屏幕):铺满所需放大倍数 &lt; 3 → 默认缩放 1(铺满) */
    @Test
    public void normalImage_defaultZoomIsCover() {
        assertEquals(1f, BgImageTransform.defaultZoom(941, 1672, VW, VH), 1e-6);
    }

    /** 很小的图(100×100):默认按原始像素显示,不被硬放大 */
    @Test
    public void tinyImage_defaultZoomKeepsNativePixels() {
        float cover = BgImageTransform.coverScale(100, 100, VW, VH); // 24
        assertEquals(24f, cover, 1e-6);
        float zoom = BgImageTransform.defaultZoom(100, 100, VW, VH);
        assertEquals(1f / 24f, zoom, 1e-6);
        // 实际缩放 = cover × zoom = 1 → 100×100 像素原样显示
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, 0f, 0.5f, 0.5f);
        assertEquals(1f, r[0], 1e-6);
    }

    /** 默认铺满时:图片正好盖住整个视图(左右或上下不留边) */
    @Test
    public void coverFillsView() {
        float[] r = BgImageTransform.resolve(941, 1672, VW, VH, 1f, 0.5f, 0.5f);
        float scale = r[0], left = r[1], top = r[2];
        assertTrue("左边不留缝", left <= 0f);
        assertTrue("上边不留缝", top <= 0f);
        assertTrue("右边不留缝", left + 941 * scale >= VW - 1e-3);
        assertTrue("下边不留缝", top + 1672 * scale >= VH - 1e-3);
    }

    /** 小图摆右下角:锚点 (1,1) → 图片右下边缘正好贴住视图右下边缘,整张图都在屏内 */
    @Test
    public void tinyImagePlacedBottomRight() {
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, 0f, 1f, 1f);
        float scale = r[0], left = r[1], top = r[2];
        assertEquals(1f, scale, 1e-6);
        assertEquals("右边缘贴视图右边", VW, left + 100 * scale, 1e-3);
        assertEquals("下边缘贴视图下边", VH, top + 100 * scale, 1e-3);
    }

    /** 锚点钳制:越界收敛到 0~1(图片永远拖不出屏、也拖不出缝),NaN 视为居中 */
    @Test
    public void anchorsAreClamped() {
        assertEquals(1f, BgImageTransform.clampAnchor(9f), 1e-6);
        assertEquals(0f, BgImageTransform.clampAnchor(-9f), 1e-6);
        assertEquals(0.5f, BgImageTransform.clampAnchor(Float.NaN), 1e-6);
        float[] a = BgImageTransform.resolve(100, 100, VW, VH, 0f, 9f, 9f);
        float[] b = BgImageTransform.resolve(100, 100, VW, VH, 0f, 1f, 1f);
        assertEquals(b[1], a[1], 1e-6);
        assertEquals(b[2], a[2], 1e-6);
    }

    /** 缩放钳制:NaN / 越界都收敛到合法区间 */
    @Test
    public void zoomIsClamped() {
        assertEquals(1f, BgImageTransform.clampZoom(Float.NaN), 1e-6);
        assertEquals(BgImageTransform.MIN_ZOOM, BgImageTransform.clampZoom(1e-9f), 1e-6);
        assertEquals(BgImageTransform.MAX_ZOOM, BgImageTransform.clampZoom(1e9f), 1e-6);
    }

    /** anchorFromLeft/Top 与 resolve 互为逆运算(设置页按焦点缩放要用) */
    @Test
    public void anchorRoundTrip() {
        float zoom = 1.7f;
        float ax = 0.31f, ay = 0.22f;
        float[] r = BgImageTransform.resolve(941, 1672, VW, VH, zoom, ax, ay);
        assertEquals(ax, BgImageTransform.anchorFromLeft(r[1], 941, VW, r[0]), 1e-4);
        assertEquals(ay, BgImageTransform.anchorFromTop(r[2], 1672, VH, r[0]), 1e-4);
    }

    /**
     * 捏合缩放的核心不变量:焦点下的那个图像点在缩放前后仍在焦点位置
     * (模拟设置页双指缩放的算法,确保"缩哪就停在哪儿")。
     */
    @Test
    public void pinchKeepsFocusAnchored() {
        int imgW = 941, imgH = 1672;
        float zoom0 = 1f, ax0 = 0.6f, ay0 = 0.45f;
        float focusX = 300f, focusY = 1800f;

        float[] before = BgImageTransform.resolve(imgW, imgH, VW, VH, zoom0, ax0, ay0);
        float imgPointX = (focusX - before[1]) / before[0];
        float imgPointY = (focusY - before[2]) / before[0];

        float zoom1 = 1.3f;
        float scale1 = BgImageTransform.coverScale(imgW, imgH, VW, VH) * zoom1;
        float left1 = focusX - imgPointX * scale1;
        float top1 = focusY - imgPointY * scale1;
        float ax1 = BgImageTransform.anchorFromLeft(left1, imgW, VW, scale1);
        float ay1 = BgImageTransform.anchorFromTop(top1, imgH, VH, scale1);

        float[] after = BgImageTransform.resolve(imgW, imgH, VW, VH, zoom1, ax1, ay1);
        assertEquals(before[0] * zoom1 / zoom0, after[0], 1e-3);
        // 钳制可能让焦点略微偏移,但必须在 1px 量级内
        float pointX = (focusX - after[1]) / after[0];
        float pointY = (focusY - after[2]) / after[0];
        assertEquals(imgPointX, pointX, 1e-3);
        assertEquals(imgPointY, pointY, 1e-3);
    }

    /** 非法尺寸(未加载完)不抛异常,返回可用兜底值 */
    @Test
    public void invalidSizesFallBack() {
        assertEquals(1f, BgImageTransform.coverScale(0, 0, VW, VH), 1e-6);
        float[] r = BgImageTransform.resolve(0, 0, 0, 0, 0f, 0.5f, 0.5f);
        assertEquals(1f, r[0], 1e-6);
        assertEquals(0f, r[1], 1e-6);
        assertEquals(0f, r[2], 1e-6);
    }

    /**
     * 平板横竖屏来回转:左下角的图必须还贴在左下角(用户实测过的故障 ——
     * 竖屏摆好、转横屏后图片"自己往里跑")。
     */
    @Test
    public void anchorKeepsCornerAcrossRotation() {
        int imgW = 2000, imgH = 3000;
        int pw = 1600, ph = 2560;   // 竖屏
        int lw = 2560, lh = 1600;   // 横屏

        float portraitZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, imgW, imgH, pw, ph);
        float landscapeZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, imgW, imgH, lw, lh);
        float[] anchor = BgImageTransform.anchorsForPosition(BgImageTransform.POS_BOTTOM_LEFT);
        assertEquals(0f, anchor[0], 1e-6);
        assertEquals(1f, anchor[1], 1e-6);

        float[] portrait = BgImageTransform.resolve(imgW, imgH, pw, ph, portraitZoom, anchor[0], anchor[1]);
        assertEquals("竖屏:左边缘贴屏幕左边", 0f, portrait[1], 1e-3);
        assertEquals("竖屏:下边缘贴屏幕下边", ph, portrait[2] + imgH * portrait[0], 1e-3);

        float[] landscape = BgImageTransform.resolve(imgW, imgH, lw, lh, landscapeZoom, anchor[0], anchor[1]);
        assertEquals("横屏:仍贴屏幕左边", 0f, landscape[1], 1e-3);
        assertEquals("横屏:仍贴屏幕下边", lh, landscape[2] + imgH * landscape[0], 1e-3);

        // 旧版模型(按屏宽归一化的"图片中心位移")在横屏会漂:同一个数把图推到离左边缘 600px 处
        float legacyOx = (imgW * portrait[0] - pw) / (2f * pw);           // 竖屏贴左算出来的旧值
        float legacyLeft = (lw - imgW * landscape[0]) / 2f + legacyOx * lw; // 横屏套用同一个旧值
        assertTrue("旧模型横屏会漂离左边缘(实测落在 600px 处)", legacyLeft > 400f);
    }

    /**
     * 旧版"中心位移"配置一次性迁移:换算成锚点后,<b>当屏落点分毫不差</b>,
     * 之后换横竖屏则按锚点走(不再漂)。
     */
    @Test
    public void legacyOffsetMigratesToSamePlacement() {
        int imgW = 2000, imgH = 3000, vw = 1600, vh = 2560;
        float zoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, imgW, imgH, vw, vh);
        float scale = BgImageTransform.coverScale(imgW, imgH, vw, vh) * zoom;

        // 旧版"左下角"预设算出来的位移(见旧版 offsetsForPosition):x 贴左、y 贴下
        float legacyOx = (imgW * scale - vw) / (2f * vw);
        float legacyOy = (vh - imgH * scale) / (2f * vh);
        float leftOld = (vw - imgW * scale) / 2f + legacyOx * vw;
        float topOld = (vh - imgH * scale) / 2f + legacyOy * vh;
        assertEquals("旧值确实是贴左", 0f, leftOld, 1e-3);
        assertEquals("旧值确实是贴下", vh, topOld + imgH * scale, 1e-3);

        float ax = BgImageTransform.anchorFromLegacyOffset(legacyOx, imgW * scale, vw);
        float ay = BgImageTransform.anchorFromLegacyOffset(legacyOy, imgH * scale, vh);
        float[] r = BgImageTransform.resolve(imgW, imgH, vw, vh, zoom, ax, ay);
        assertEquals("迁移后当屏落点不变(x)", leftOld, r[1], 1e-3);
        assertEquals("迁移后当屏落点不变(y)", topOld, r[2], 1e-3);

        // 迁移后换横屏:仍是左下角
        float landZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, imgW, imgH, vh, vw);
        float[] land = BgImageTransform.resolve(imgW, imgH, vh, vw, landZoom, ax, ay);
        assertEquals(0f, land[1], 1e-3);
        assertEquals(vw, land[2] + imgH * land[0], 1e-3);

        // 该轴没有可移动范围时(图片刚好铺满)退化为居中,不产生 NaN
        assertEquals(0.5f, BgImageTransform.anchorFromLegacyOffset(0.4f, VW, VW), 1e-6);
    }

    // ── 预设(全屏铺满 / 适应屏幕 / 原图大小 / 居中 / 四角) ──

    /** 全屏铺满 = 盖满屏幕(与"超出裁剪"同一个效果) */
    @Test
    public void presetFillCoversScreen() {
        assertEquals(1f, BgImageTransform.zoomForSize(BgImageTransform.SIZE_FILL, 941, 1672, VW, VH), 1e-6);
        float zoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_FILL, 100, 100, VW, VH);
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, zoom, 0.5f, 0.5f);
        // 100×100 小图"铺满"=放大到盖住整屏
        assertTrue(r[1] <= 0f && r[2] <= 0f);
        assertTrue(r[1] + 100 * r[0] >= VW - 1e-3);
        assertTrue(r[2] + 100 * r[0] >= VH - 1e-3);
    }

    /** 适应屏幕 = 整图可见:按屏幕算,宽或高其中一边刚好铺满、另一边留边(contain,无裁切) */
    @Test
    public void presetFitShowsWholeImage() {
        int imgW = 941, imgH = 1672;
        float zoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_FIT, imgW, imgH, VW, VH);
        // 比铺满小(否则就有裁切了)
        assertTrue(zoom < 1f);
        float[] r = BgImageTransform.resolve(imgW, imgH, VW, VH, zoom, 0.5f, 0.5f);
        float scale = r[0], left = r[1], top = r[2];
        assertTrue("左边不出屏", left >= -1e-3f);
        assertTrue("上边不出屏", top >= -1e-3f);
        assertTrue("右边不出屏", left + imgW * scale <= VW + 1e-3f);
        assertTrue("下边不出屏", top + imgH * scale <= VH + 1e-3f);
        // 这张图比屏幕"矮胖":宽刚好铺满,上下留边
        assertEquals(VW, imgW * scale, 1e-2);
        assertTrue(imgH * scale < VH - 1f);

        // 比屏幕更瘦长的图反过来:高刚好铺满、左右留边
        float tall = BgImageTransform.zoomForSize(BgImageTransform.SIZE_FIT, 600, 2400, VW, VH);
        float[] rt = BgImageTransform.resolve(600, 2400, VW, VH, tall, 0.5f, 0.5f);
        assertEquals(VH, 2400 * rt[0], 1e-2);
        assertTrue(600 * rt[0] < VW - 1f);
    }

    /** 适应屏幕的高亮判定:命中才亮;非法尺寸(尺寸未知/未加载完)不抛异常、兜底 1 */
    @Test
    public void presetFitMatchingAndFallback() {
        float fit = BgImageTransform.zoomForSize(BgImageTransform.SIZE_FIT, 941, 1672, VW, VH);
        assertTrue(BgImageTransform.matchesSize(fit, BgImageTransform.SIZE_FIT, 941, 1672, VW, VH));
        assertFalse(BgImageTransform.matchesSize(1f, BgImageTransform.SIZE_FIT, 941, 1672, VW, VH));
        assertFalse(BgImageTransform.matchesSize(fit, BgImageTransform.SIZE_FIT, 0, 0, VW, VH));
        assertEquals(1f, BgImageTransform.fitZoom(0, 0, VW, VH), 1e-6);
        assertEquals(1f, BgImageTransform.fitZoom(941, 1672, 0, 0), 1e-6);
    }

    /** 原图大小 = 原始像素 1:1(小图不被放大) */
    @Test
    public void presetNativeKeepsPixelSize() {
        float zoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 100, 100, VW, VH);
        float[] r = BgImageTransform.resolve(100, 100, VW, VH, zoom, 0.5f, 0.5f);
        assertEquals(1f, r[0], 1e-4);
        assertEquals("居中", VW / 2f, r[1] + 50f, 1e-3);
        assertEquals("居中", VH / 2f, r[2] + 50f, 1e-3);
        // 大图的"原始像素"会小于铺满倍率
        float bigZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 941, 1672, VW, VH);
        assertTrue(bigZoom < 1f);
    }

    /** 四角预设:锚点就是 (0|1, 0|1),与倍率/屏幕尺寸无关;小图摆角上整张都在屏内 */
    @Test
    public void presetCorners() {
        float nativeZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 100, 100, VW, VH);
        float[] tl = BgImageTransform.resolve(100, 100, VW, VH, nativeZoom,
                BgImageTransform.anchorsForPosition(BgImageTransform.POS_TOP_LEFT)[0],
                BgImageTransform.anchorsForPosition(BgImageTransform.POS_TOP_LEFT)[1]);
        assertEquals(1f, tl[0], 1e-4);
        assertEquals("左边缘贴屏幕左边", 0f, tl[1], 1e-3);
        assertEquals("上边缘贴屏幕上边", 0f, tl[2], 1e-3);

        float[] brAnchor = BgImageTransform.anchorsForPosition(BgImageTransform.POS_BOTTOM_RIGHT);
        float[] br = BgImageTransform.resolve(100, 100, VW, VH, nativeZoom, brAnchor[0], brAnchor[1]);
        assertEquals("右边缘贴屏幕右边", VW, br[1] + 100 * br[0], 1e-3);
        assertEquals("下边缘贴屏幕下边", VH, br[2] + 100 * br[0], 1e-3);

        float[] centerAnchor = BgImageTransform.anchorsForPosition(BgImageTransform.POS_CENTER);
        assertEquals(0.5f, centerAnchor[0], 1e-6);
        assertEquals(0.5f, centerAnchor[1], 1e-6);
    }

    /** 高亮判定:命中对应预设为真,偏差过大为假;尺寸未知时尺寸项一律不亮 */
    @Test
    public void presetHighlightMatching() {
        assertTrue(BgImageTransform.matchesSize(1f, BgImageTransform.SIZE_FILL, 941, 1672, VW, VH));
        assertFalse(BgImageTransform.matchesSize(1.2f, BgImageTransform.SIZE_FILL, 941, 1672, VW, VH));
        float nativeZoom = BgImageTransform.zoomForSize(BgImageTransform.SIZE_NATIVE, 941, 1672, VW, VH);
        assertTrue(BgImageTransform.matchesSize(nativeZoom, BgImageTransform.SIZE_NATIVE, 941, 1672, VW, VH));
        assertFalse(BgImageTransform.matchesSize(nativeZoom, BgImageTransform.SIZE_NATIVE, 0, 0, VW, VH));

        assertTrue(BgImageTransform.matchesPosition(0.5f, 0.5f, BgImageTransform.POS_CENTER));
        assertFalse(BgImageTransform.matchesPosition(0.8f, 0.5f, BgImageTransform.POS_CENTER));
        assertTrue(BgImageTransform.matchesPosition(1f, 0f, BgImageTransform.POS_TOP_RIGHT));
        assertTrue(BgImageTransform.matchesPosition(0f, 1f, BgImageTransform.POS_BOTTOM_LEFT));
        assertFalse(BgImageTransform.matchesPosition(0f, 1f, BgImageTransform.POS_TOP_LEFT));
    }
}
