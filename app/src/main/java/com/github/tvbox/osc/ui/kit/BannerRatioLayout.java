package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.util.AttributeSet;

import com.lihang.ShadowLayout;

/**
 * 通栏卡片横图根布局:宽:高 = 3:2(高度 = 宽度 × 2/3)。
 * 宽度填满所在网格单元(match_parent),高度由宽度等比例反推,始终保持 3:2;
 * 外层 GridLayoutManager 按结果区可用宽度和单列宽度上限增加列数,
 * 避免大屏时横图宽高一起放大。
 * 屏幕旋转/窗口尺寸变化时 onMeasure 自动重算。
 */
public class BannerRatioLayout extends ShadowLayout {

    /** 高度 = 宽度 × H_W(宽:高 = 3:2) */
    private static final float H_W = 2f / 3f;

    public BannerRatioLayout(Context context) {
        super(context);
    }

    public BannerRatioLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BannerRatioLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width > 0) {
            int h = Math.round(width * H_W);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
