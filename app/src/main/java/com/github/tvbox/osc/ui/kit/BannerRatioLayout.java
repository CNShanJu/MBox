package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.util.AttributeSet;

import com.lihang.ShadowLayout;

/**
 * 通栏卡片横图根布局:宽:高 = 2:1(高度 = 宽度 × 1/2)。
 * 宽度填满所在网格单元(match_parent),高度由宽度等比例反推,始终保持 2:1;
 * 最大显示高度由外层 GridLayoutManager 的列数间接限制(列数按"单卡宽≤2×限高"反推,
 * 若单卡放一行高度就超限,则增加列数让每卡变窄,直到能并排且不超限高)。
 * 屏幕旋转/窗口尺寸变化时 onMeasure 自动重算。
 */
public class BannerRatioLayout extends ShadowLayout {

    /** 高度 = 宽度 × H_W(宽:高 = 2:1) */
    private static final float H_W = 1f / 2f;

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
