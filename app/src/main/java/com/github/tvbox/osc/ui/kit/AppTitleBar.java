package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.hjq.bar.TitleBar;

/**
 * 统一的二级页面标题栏组件:
 * 背景 / 标题文字 / 分割线统一跟随主题配置(悬浮面 bg_float / 文字 text_main)。
 * <p>
 * 背景用悬浮面色 bg_float(与底部导航栏同色同透明度,94%),与底栏是同一个面;
 * 改底栏颜色/透明度这里跟着一起变,不要再单独指定背景色。
 * <p>
 * 注意:标题栏与底栏、页面内容卡片/面板({@code bg_large_round_float})是<b>同一个面</b>
 * (bg_float 色 + 92% 透明度),改底栏色/透明度这里跟着一起变,不要再单独指定背景色。
 * 大面板用半透明会把背后底图的明暗差透出来(轻微噪斑),这是"全站同一个面"的既定取舍
 * (见 app/src/main/res/values/colors.xml 注释)。
 * <p>
 * 右侧图标:TitleBar 原生的 rightIcon 机制(compound drawable)尺寸/位置受限
 * (setRightIconSize 固定、无垂直位置控制),这里改为直接挂自绘 ImageView,
 * 尺寸与右边距完全可自定义。
 */
public class AppTitleBar extends TitleBar {

    private ImageView mRightIconView;
    /** 用户是否设置了自定义右侧图标 */
    private boolean mCustomRightIconSet = false;

    public AppTitleBar(Context context) {
        this(context, null);
    }

    public AppTitleBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDefault();
    }

    private void initDefault() {
        int textColor = ContextCompat.getColor(getContext(), R.color.text_main);
        // 悬浮面:与底部导航栏(bg_float)同色同透明度,不另调色
        setBackgroundColor(ContextCompat.getColor(getContext(), R.color.bg_float));
        setTitleColor(textColor);
        setLeftIconTint(textColor);
        setLineVisible(false);
        // 右侧 view 垂直居中与标题文字对齐(原生 rightView 去掉字体内边距)
        if (getRightView() != null) {
            android.widget.TextView rv = getRightView();
            rv.setGravity(Gravity.CENTER);
            rv.setIncludeFontPadding(false);
            rv.setSingleLine(true);
        }
    }

    /**
     * 设置自定义右侧图标(自绘 ImageView,替代受限的原生 rightIcon 机制):
     * 尺寸/右边距完全可控,垂直居中于标题栏。
     *
     * @param resId      图标资源
     * @param widthDp    图标宽(dp)
     * @param heightDp   图标高(dp)
     * @param rightMarginDp 距右边缘(dp)
     */
    public void setRightIconCustom(int resId, float widthDp, float heightDp, float rightMarginDp) {
        removeCustomRightIcon();
        ImageView iv = new ImageView(getContext());
        int w = dp2px(widthDp);
        int h = dp2px(heightDp);
        int margin = dp2px(rightMarginDp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        lp.rightMargin = margin;
        iv.setLayoutParams(lp);
        Drawable d = ContextCompat.getDrawable(getContext(), resId);
        if (d != null) {
            d.setTint(ContextCompat.getColor(getContext(), R.color.text_main));
            iv.setImageDrawable(d);
        }
        iv.setOnClickListener(v -> {
            if (getRightView() != null) {
                getRightView().performClick();
            }
        });
        addView(iv);
        mRightIconView = iv;
        mCustomRightIconSet = true;
        // 隐藏原生的 rightView 内容(避免两者重叠)
        if (getRightView() != null) {
            getRightView().setVisibility(INVISIBLE);
        }
    }

    /** 移除自定义右侧图标,恢复原生 rightIcon */
    public void removeCustomRightIcon() {
        if (mRightIconView != null) {
            try {
                removeView(mRightIconView);
            } catch (Throwable ignored) {
            }
            mRightIconView = null;
        }
        if (mCustomRightIconSet && getRightView() != null) {
            getRightView().setVisibility(VISIBLE);
        }
        mCustomRightIconSet = false;
    }

    private int dp2px(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
