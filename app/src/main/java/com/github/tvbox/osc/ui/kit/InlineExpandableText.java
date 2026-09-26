package com.github.tvbox.osc.ui.kit;

import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.View;

/**
 * 文本“收起态内联展开”排版工具:与 TextView 同款排版(同宽/行距)预测量,
 * 生成“前 N 行 + 第 N 行截断内容 + 行末可点后缀(如 … 展开)”的 Spannable。
 * <p>用途:长文本折叠展示(简介/摘要/条目说明等),保证“展开”按钮与截断省略同一行,
 * 避免依赖 TextView maxLines+ellipsize 时按钮被截掉。用法:
 * <pre>
 *   int lines = InlineExpandableText.lineCount(text, tv.getPaint(), width, spacing);
 *   tv.setText(InlineExpandableText.buildCollapsed(text, tv.getPaint(), width, spacing,
 *           5, "… 展开", () -> expand(), linkColor));
 * </pre>
 */
public final class InlineExpandableText {

    private InlineExpandableText() {
    }

    /** 与 TextView(同宽 + lineSpacingExtra)排版一致时的实际行数 */
    public static int lineCount(String text, TextPaint paint, int widthPx, int spacingExtraPx) {
        if (TextUtils.isEmpty(text) || widthPx <= 0) return 0;
        return layout(text, paint, widthPx, spacingExtraPx).getLineCount();
    }

    /**
     * 生成收起态文本:文本截断到 maxLines 行(前 maxLines-1 行完整,
     * 第 maxLines 行内容 + 行末可点 suffix),整段共 maxLines 行。
     *
     * @param suffix 追加在截断文本行末的可点后缀(如 “… 展开”)
     * @param onSuffixClick 点后缀回调(通常触发展开/收回)
     * @return 全文 ≤maxLines 行时原样返回;否则返回 Spannable(后缀可点)
     */
    public static CharSequence buildCollapsed(String full, TextPaint paint, int widthPx,
                                              int spacingExtraPx, int maxLines,
                                              String suffix, Runnable onSuffixClick, int linkColor) {
        if (TextUtils.isEmpty(full) || widthPx <= 0 || maxLines <= 1) return full;
        StaticLayout fullLayout = layout(full, paint, widthPx, spacingExtraPx);
        if (fullLayout.getLineCount() <= maxLines) return full;

        float suffixW = paint.measureText(suffix);
        // 第 maxLines 行起始 = 前 maxLines-1 行完整内容之后
        int startLast = fullLayout.getLineStart(maxLines - 1);
        String prefix = full.substring(0, startLast);
        String tail = full.substring(startLast);

        float avail = widthPx - suffixW;
        int k = 0;
        float w = 0f;
        while (k < tail.length() && w + paint.measureText(tail, k, k + 1) <= avail) {
            w += paint.measureText(tail, k, k + 1);
            k++;
        }
        String collapsed = prefix + tail.substring(0, k) + suffix;
        // 保险:若仍溢出到 maxLines+1 行,逐字退让
        if (layout(collapsed, paint, widthPx, spacingExtraPx).getLineCount() > maxLines && k > 0) {
            while (k > 0) {
                k--;
                collapsed = prefix + tail.substring(0, k) + suffix;
                if (layout(collapsed, paint, widthPx, spacingExtraPx).getLineCount() <= maxLines) {
                    break;
                }
            }
        }
        // 仅末尾“展开”可点并可点色;前导省略号“… ”保持正文色(不随链接变蓝)
        int linkStart = collapsed.length() - trimClickableLen(suffix);
        SpannableString sp = new SpannableString(collapsed);
        sp.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                if (onSuffixClick != null) onSuffixClick.run();
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(linkColor);
                ds.setUnderlineText(false);
            }
        }, linkStart, collapsed.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /**
     * 生成展开态文本:全文 + 行末可点后缀(如 “ 收回”),后缀上链接色。
     * 让“收回”跟在结束文本后面(内联),而不是落到独立一行。
     */
    public static CharSequence buildExpanded(String full, String suffix,
                                             Runnable onSuffixClick, int linkColor) {
        if (TextUtils.isEmpty(full)) return full;
        SpannableString sp = new SpannableString(full + suffix);
        int linkStart = full.length();
        sp.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                if (onSuffixClick != null) onSuffixClick.run();
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(linkColor);
                ds.setUnderlineText(false);
            }
        }, linkStart, linkStart + suffix.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** 计算可点部分长度:suffix 去掉前导省略/空格等(如 “… 展开” → “展开”) */
    private static int trimClickableLen(String suffix) {
        int len = suffix.length();
        int s = 0;
        while (s < len && (suffix.charAt(s) == '…' || suffix.charAt(s) == '.'
                || suffix.charAt(s) == ' ')) {
            s++;
        }
        return len - s;
    }

    private static StaticLayout layout(String text, TextPaint paint, int widthPx,
                                       int spacingExtraPx) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, widthPx)
                .setLineSpacing(spacingExtraPx, 1.0f)
                .setIncludePad(false)
                .build();
    }
}
