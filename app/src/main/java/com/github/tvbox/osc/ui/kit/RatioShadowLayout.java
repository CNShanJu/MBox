package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.util.AttributeSet;

import com.lihang.ShadowLayout;

/**
 * 固定宽高比的剧集卡片根布局:宽:高 = 3:4(高度 = 宽度 × 4/3)。
 * 宽度由 RecyclerView 网格列数决定(自适应屏幕宽度);
 * 每次 onMeasure 都按当前宽度重算高度,因此屏幕旋转 / 窗口尺寸变化(大屏横竖屏切换)时
 * 会自动更新,无需手动刷新。
 */
public class RatioShadowLayout extends ShadowLayout {

    /** 高度 = 宽度 × RATIO_H_W(宽:高 = 3:4) */
    private static final float RATIO_H_W = 4f / 3f;

    public RatioShadowLayout(Context context) {
        super(context);
    }

    public RatioShadowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioShadowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        // 高度未由父布局固定(如 wrap_content)时,按当前宽度计算高度
        if (width > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            int height = Math.round(width * RATIO_H_W);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
