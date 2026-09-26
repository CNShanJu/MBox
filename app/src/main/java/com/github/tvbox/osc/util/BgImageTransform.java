package com.github.tvbox.osc.util;

/**
 * 背景图变换的纯计算(不依赖 Android,可 JVM 单测)。
 * <p>
 * 用于全局背景层({@code ui/kit/PageBackgroundView})与"背景图设置"页的拖动/双指缩放,
 * 两边共用同一套模型,保证"设置页所见 = 页面最终所现"。
 * <p>
 * 变换模型(与设备无关,换分辨率/横竖屏都还原成同一观感):
 * <ul>
 *   <li>基准缩放 {@link #coverScale} = max(viewW/imgW, viewH/imgH),即 centerCrop 铺满屏幕;</li>
 *   <li>{@code zoom} 是基准之上的倍率(1=铺满);{@code zoom<=0} 视为"自动",
 *       由 {@link #defaultZoom} 决定(大图铺满、很小的图按原始像素,避免被硬放大到糊);</li>
 *   <li>{@code anchorX/anchorY} 是<b>锚点比例(0~1)</b>,表示图片摆在该轴"可移动范围"的哪个位置:
 *       图片比视图小 → 可移动范围 = 视图尺寸 - 图片尺寸(&gt;0,整张图都在屏内);
 *       比视图大 → 可移动范围是负的(图必然超出去,只能决定裁哪一边)。
 *       <b>0 = 起始边贴边(左/上)、0.5 = 居中、1 = 结束边贴边(右/下)</b>,摆放式:
 *       {@code left = anchorX * (viewW - imgW*scale)}。</li>
 * </ul>
 * <b>为什么锚点是比例而不是"图片中心相对屏心的位移"</b>(旧版模型,按屏宽归一化 {0=居中,±0.5=贴边}):
 * 那个量只有在"写入时的那块屏幕几何"下才有意义 —— 竖屏设成左下角的图,转到横屏屏宽一变,
 * 同一个数就换算到完全不同的位置(实测会跑到右侧去,即"图片自己往里跑")。
 * 锚点表达的是"贴哪条边/偏多少"的意图,与屏幕尺寸无关,所以换横竖屏、换分辨率都保持同一观感。
 * 旧配置由 {@link #anchorFromLegacyOffset} 在拿到图片尺寸后一次性换算(当屏视觉不变)。
 */
public final class BgImageTransform {

    /**
     * "自动缩放"阈值:铺满屏幕所需的放大倍数超过该值时(图片相对屏幕很小),
     * 不铺满而按原始像素显示 —— 例如 100×100 的小图不会被放大 20 多倍糊成一片,
     * 用户可以自己拖到角上做点缀。
     */
    public static final float AUTO_COVER_MAX_UPSCALE = 3f;

    /** 缩放下限/上限(相对铺满基准的倍率) */
    public static final float MIN_ZOOM = 0.01f;
    public static final float MAX_ZOOM = 20f;

    /** 锚点合法范围(0=起始边贴边,1=结束边贴边) */
    public static final float MIN_ANCHOR = 0f;
    public static final float MAX_ANCHOR = 1f;
    /** 居中锚点 */
    public static final float ANCHOR_CENTER = 0.5f;

    /** 该轴可移动范围小于该像素值时,视为"没有可移动空间"(锚点固定居中,拖动无效) */
    private static final float MIN_SLACK_PX = 0.5f;

    private BgImageTransform() {
    }

    /** 铺满视图所需的最小缩放(centerCrop 语义);任一边长非法时返回 1 */
    public static float coverScale(int imgW, int imgH, int viewW, int viewH) {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return 1f;
        return Math.max((float) viewW / imgW, (float) viewH / imgH);
    }

    /**
     * 新选背景图的默认缩放:
     * 普通图片(铺满所需放大倍数 &le; {@link #AUTO_COVER_MAX_UPSCALE}) → 1,铺满屏幕;
     * 很小的图片 → 1/coverScale,按原始像素显示(不放大到糊),由用户自己拖位置。
     */
    public static float defaultZoom(int imgW, int imgH, int viewW, int viewH) {
        float cover = coverScale(imgW, imgH, viewW, viewH);
        if (cover > AUTO_COVER_MAX_UPSCALE) return 1f / cover;
        return 1f;
    }

    /** 缩放钳制(含 NaN 兜底) */
    public static float clampZoom(float zoom) {
        if (Float.isNaN(zoom)) return 1f;
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /** 锚点钳制(含 NaN 兜底):越界收敛到 0~1,NaN 视为居中 */
    public static float clampAnchor(float anchor) {
        if (Float.isNaN(anchor)) return ANCHOR_CENTER;
        return Math.max(MIN_ANCHOR, Math.min(MAX_ANCHOR, anchor));
    }

    /**
     * 某轴上的"可移动范围"(px):图片在该轴的实际尺寸 = {@code imgSpan * scale}。
     * {@code >0} 图片整张在视图内(可左右/上下摆);{@code 0} 刚好铺满;
     * {@code <0} 图片必然超出视图(负值大小 = 该轴上被裁掉的总量)。
     */
    public static float slack(int imgSpan, int viewSpan, float scale) {
        return viewSpan - imgSpan * scale;
    }

    /**
     * 解析成实际绘制参数。
     *
     * @param zoom    &le;0 表示自动(见 {@link #defaultZoom})
     * @param anchorX 横向锚点 0~1(0=贴左、0.5=居中、1=贴右)
     * @param anchorY 纵向锚点 0~1(0=贴上、0.5=居中、1=贴下)
     * @return 长度 3 的数组:{@code [0]=实际缩放, [1]=图片左上角 x, [2]=图片左上角 y}
     */
    public static float[] resolve(int imgW, int imgH, int viewW, int viewH,
                                  float zoom, float anchorX, float anchorY) {
        float z = zoom > 0 ? clampZoom(zoom) : defaultZoom(imgW, imgH, viewW, viewH);
        float scale = coverScale(imgW, imgH, viewW, viewH) * z;
        float left = clampAnchor(anchorX) * slack(imgW, viewW, scale);
        float top = clampAnchor(anchorY) * slack(imgH, viewH, scale);
        return new float[]{scale, left, top};
    }

    /** 由图片左边缘位置反推横向锚点(捏合时保持焦点下的图像点不动用);该轴没有可移动空间时返回居中 */
    public static float anchorFromLeft(float left, int imgW, int viewW, float scale) {
        return anchorFromEdge(left, slack(imgW, viewW, scale));
    }

    /** 由图片上边缘位置反推纵向锚点;该轴没有可移动空间时返回居中 */
    public static float anchorFromTop(float top, int imgH, int viewH, float scale) {
        return anchorFromEdge(top, slack(imgH, viewH, scale));
    }

    private static float anchorFromEdge(float edge, float slack) {
        if (Math.abs(slack) < MIN_SLACK_PX) return ANCHOR_CENTER;
        return clampAnchor(edge / slack);
    }

    // ── 预设(设置页的"全屏铺满 / 适应屏幕 / 原图大小 / 居中显示 / 四角") ──

    /** 预设尺寸:全屏铺满(等比盖满屏幕、超出部分裁掉;等价于"超出裁剪") */
    public static final int SIZE_FILL = 0;
    /** 预设尺寸:原图大小(按原始像素 1:1 显示,不放大到糊) */
    public static final int SIZE_NATIVE = 1;
    /** 预设尺寸:适应屏幕(整图可见:等比缩放到宽或高其中一边刚好铺满,另一边留边) */
    public static final int SIZE_FIT = 2;

    /** 预设位置:居中 */
    public static final int POS_CENTER = 0;
    public static final int POS_TOP_LEFT = 1;
    public static final int POS_TOP_RIGHT = 2;
    public static final int POS_BOTTOM_LEFT = 3;
    public static final int POS_BOTTOM_RIGHT = 4;

    /** 高亮判定容差:倍率 2%(相对)/ 锚点 0.02 */
    private static final float ZOOM_TOLERANCE = 0.02f;
    private static final float ANCHOR_TOLERANCE = 0.02f;

    /** 尺寸预设对应的缩放倍率(相对铺满基准):铺满=1;适应屏幕=各边取小(见 {@link #fitZoom});
     *  原图大小=1/coverScale(1:1 像素) */
    public static float zoomForSize(int size, int imgW, int imgH, int viewW, int viewH) {
        if (size == SIZE_NATIVE) {
            float cover = coverScale(imgW, imgH, viewW, viewH);
            return cover > 0f ? 1f / cover : 1f;
        }
        if (size == SIZE_FIT) return fitZoom(imgW, imgH, viewW, viewH);
        return 1f;
    }

    /**
     * 适应屏幕所需的倍率(相对铺满基准):按屏幕算 —— 等比缩放到"宽或高其中一边刚好铺满屏幕,
     * 另一边不足留边"(contain 语义),也就是 min/max 的比值,恒 &le; 1(比铺满小,所以没有裁切)。
     * 任一边长非法时返回 1。
     */
    public static float fitZoom(int imgW, int imgH, int viewW, int viewH) {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return 1f;
        float cover = Math.max((float) viewW / imgW, (float) viewH / imgH);
        float contain = Math.min((float) viewW / imgW, (float) viewH / imgH);
        return cover > 0f ? contain / cover : 1f;
    }

    /**
     * 位置预设对应的锚点:返回 {@code {anchorX, anchorY}}。
     * <p>
     * 四角/两侧就是"边贴边"(图片左边缘贴屏幕左边、右边缘贴右边…),锚点直接就是 0 或 1;
     * 与倍率、屏幕尺寸都无关,所以横竖屏切换后仍然贴在那个角上(旧模型要按当前倍率算,
     * 于是换屏就漂)。
     */
    public static float[] anchorsForPosition(int position) {
        switch (position) {
            case POS_TOP_LEFT:
                return new float[]{0f, 0f};
            case POS_TOP_RIGHT:
                return new float[]{1f, 0f};
            case POS_BOTTOM_LEFT:
                return new float[]{0f, 1f};
            case POS_BOTTOM_RIGHT:
                return new float[]{1f, 1f};
            default:
                return new float[]{ANCHOR_CENTER, ANCHOR_CENTER};
        }
    }

    /** 当前倍率是否等于某个尺寸预设(用于设置页高亮;尺寸未知/无效时返回 false) */
    public static boolean matchesSize(float zoom, int size, int imgW, int imgH, int viewW, int viewH) {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return false;
        float target = zoomForSize(size, imgW, imgH, viewW, viewH);
        return Math.abs(zoom - target) <= Math.max(0.01f, target * ZOOM_TOLERANCE);
    }

    /** 当前锚点是否等于某个位置预设(用于设置页高亮) */
    public static boolean matchesPosition(float anchorX, float anchorY, int position) {
        float[] target = anchorsForPosition(position);
        return Math.abs(clampAnchor(anchorX) - target[0]) <= ANCHOR_TOLERANCE
                && Math.abs(clampAnchor(anchorY) - target[1]) <= ANCHOR_TOLERANCE;
    }

    // ── 旧配置迁移 ──

    /**
     * 旧版"图片中心位移"换算成锚点(<b>老配置一次性迁移用</b>)。
     * <p>
     * 旧式:{@code left = slack/2 + offset*viewSpan};锚点式:{@code left = anchor*slack},
     * 于是 {@code anchor = 0.5 + offset*viewSpan/slack}。在"写入时那块屏幕的几何"上换算,
     * 当屏观感一模一样;之后换横竖屏就按锚点走,不会再漂。该轴没有可移动空间时返回居中。
     *
     * @param offset     旧版位移(-0.5..0.5,按屏宽/屏高归一化)
     * @param imgSpanPx  该轴上图片的实际像素尺寸(图片尺寸 × 实际缩放)
     * @param viewSpanPx 该轴上视图的像素尺寸
     */
    public static float anchorFromLegacyOffset(float offset, float imgSpanPx, float viewSpanPx) {
        float slack = viewSpanPx - imgSpanPx;
        if (Math.abs(slack) < MIN_SLACK_PX) return ANCHOR_CENTER;
        return clampAnchor(ANCHOR_CENTER + offset * viewSpanPx / slack);
    }
}
