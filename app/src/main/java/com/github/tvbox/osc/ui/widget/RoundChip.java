package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.TextLineHeight;

/**
 * 统一的"圆角 + 文字"组件(选集格子/下载剧集格子等):
 * 选中 = **文字主色**(与首页顶部导航栏的选中文字同色);未选中 = 它的未选中文字色 `text_sub_foreground`;
 * 禁用 = 次要文字色;失败 = 红。背景/描边由外部提供,组件只统一文字与选中态。
 * <p>
 * 注意:这里**不再用**专门的高亮色(text_highlight),而是与首页顶部 tab 的选中/未选中色保持一致 ——
 * 要调整选中强度就改这一处,别在各页面各写一套色。
 * 选集依旧是"无边框无背景"(项目约定),选中只靠文字色区分。
 */
public class RoundChip extends FrameLayout {

    /** 选中态字号增量(sp):比未选中稍大一点,配合加粗读出"当前集" */
    private static final float SELECTED_SIZE_DELTA_SP = 1f;

    private final TextView mTextView;
    /** 基准字号(sp,默认 12):由 {@link #setChipTextSize(float)} 指定;选中态在此基础上 +{@link #SELECTED_SIZE_DELTA_SP} */
    private float mTextSizeSp = 12f;
    /** 禁用态(已下载/下载中不可选),文字置灰 */
    private boolean mDisabled = false;
    /** 失败态(下载失败可重下),文字红色 */
    private boolean mFailed = false;

    public RoundChip(Context context) {
        this(context, null);
    }

    public RoundChip(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTextView = new TextView(context);
        mTextView.setGravity(Gravity.CENTER);
        mTextView.setSingleLine(true);
        // 超出宽度用跑马灯滚动(如本地选集长文件名),不再尾部省略号截断
        mTextView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mTextView.setSelected(true);
        mTextView.setFocusable(true);
        mTextView.setFocusableInTouchMode(true);
        applyTextStyle(false);
        // 内部文字 MATCH_PARENT + 居中:宽度受限于格子(chip 外层决定),超出即跑马灯;
        // WRAP_CONTENT 会让长文字撑出格子,滚动也无从触发
        addView(mTextView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        updateColor(false);
    }

    public void setTitle(String title) {
        mTextView.setText(title);
    }

    public String getTitle() {
        return mTextView.getText().toString();
    }

    /** 基准字号(sp);选中态会自动再大 {@link #SELECTED_SIZE_DELTA_SP} 并加粗 */
    public void setChipTextSize(float sp) {
        mTextSizeSp = sp;
        applyTextStyle(isSelected());
    }

    /** 字重 + 字号:选中 = 加粗 + 大一号(选中/未选中都是同一套基准字号,只差这一档) */
    private void applyTextStyle(boolean selected) {
        mTextView.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        mTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                selected ? mTextSizeSp + SELECTED_SIZE_DELTA_SP : mTextSizeSp);
        // 关键:两种状态必须占同样的行高。芯片是 wrap_content,若只让选中项变大,
        // 该行会被撑高 → 后面的条目整体往下移(选中一集整片列表跳动)。这里按"选中态字号"
        // 给所有芯片预留行高,未选中项多出的空白在垂直居中里看不出来。
        mTextView.setMinHeight(TextLineHeight.forSp(mTextView, mTextSizeSp + SELECTED_SIZE_DELTA_SP));
    }

    /**
     * 状态图标(复合 drawable 贴在文字左侧,如 下载中蓝↓ / 已下载绿✓):
     *
     * @param resId    图标资源;0 表示清除图标
     * @param colorRes 图标着色(主题色);0 表示不着色
     */
    public void setStateIcon(int resId, int colorRes) {
        // 带状态图标时文字回到 WRAP_CONTENT:icon+文字整体居中,不被满宽文字钉到两端
        LayoutParams lp = (LayoutParams) mTextView.getLayoutParams();
        lp.width = resId == 0 ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT;
        mTextView.setLayoutParams(lp);
        if (resId == 0) {
            mTextView.setCompoundDrawables(null, null, null, null);
            return;
        }
        Drawable d = ContextCompat.getDrawable(getContext(), resId);
        if (d != null) {
            int size = Math.round(16 * getContext().getResources().getDisplayMetrics().density);
            d.setBounds(0, 0, size, size);
            if (colorRes != 0) {
                d.setColorFilter(ContextCompat.getColor(getContext(), colorRes), PorterDuff.Mode.SRC_IN);
            }
            mTextView.setCompoundDrawables(d, null, null, null);
            mTextView.setCompoundDrawablePadding(Math.round(3 * getContext().getResources().getDisplayMetrics().density));
        }
    }

    /** 禁用态:文字置灰(用于已下载/下载中不可重复选择的剧集) */
    public void setDisabled(boolean disabled) {
        mDisabled = disabled;
        updateColor(isSelected());
    }

    /** 失败态:文字红色(下载失败,可重新勾选下载) */
    public void setFailed(boolean failed) {
        mFailed = failed;
        updateColor(isSelected());
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        updateColor(selected);
    }

    private void updateColor(boolean selected) {
        // 字重/字号:选中加粗 + 大一号;颜色:选中 = **文字主色**(与首页顶部导航栏选中色同一档 ——
        applyTextStyle(selected);
        if (selected) {
            mTextView.setTextColor(ContextCompat.getColor(getContext(), R.color.text_foreground));
            return;
        }
        if (mFailed) {
            mTextView.setTextColor(ContextCompat.getColor(getContext(), R.color.red));
        } else if (mDisabled) {
            // 禁用(已下载/下载中):比"未选中"更淡一档,配合状态图标区分
            mTextView.setTextColor(ContextCompat.getColor(getContext(), R.color.disable_text));
        } else {
            // 未选中:与首页顶部导航栏的未选中文字同色
            mTextView.setTextColor(ContextCompat.getColor(getContext(), R.color.text_sub_foreground));
        }
    }
}
