package com.github.tvbox.osc.util;

import android.graphics.Paint;
import android.text.TextPaint;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * 单行文字行高换算(纯换算,不依赖具体控件状态)。
 * <p>
 * 用途:选中态文字比未选中"大一号"的芯片(选集/下载的 RoundChip、详情页线路),芯片是
 * wrap_content,若不预留行高,选中那一项会把所在行撑高 → 后面的条目整体往下移(点一集整片跳动)。
 * 这里按"选中态字号"算出应有的行高,给所有同款芯片 {@code setMinHeight} 上去即可。
 */
public final class TextLineHeight {

    private TextLineHeight() {
    }

    /**
     * 按字号(sp)算单行高度(px)。
     * 口径与 TextView 单行高度一致(includeFontPadding 默认为 true 时用 font metrics 的 top/bottom);
     * 末尾 +1px 兜底 —— 算得略小一点就会出现"选中才撑高"的偶发跳动。
     */
    public static int forSp(TextView tv, float sp) {
        if (tv == null || sp <= 0f) return 0;
        TextPaint paint = new TextPaint(tv.getPaint());
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                tv.getResources().getDisplayMetrics()));
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (int) Math.ceil(fm.bottom - fm.top) + 1;
    }
}
