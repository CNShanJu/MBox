package com.github.tvbox.osc.util;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown → Spannable 渲染(供更新说明等展示,不引入三方 md 库):
 * <ul>
 *   <li>行首 {@code #/##/### } 标题 → 加粗;</li>
 *   <li>行首 {@code - } / {@code * } 列表项 → 「• 」前缀(放大圆点、段内缩进);</li>
 *   <li>{@code `code`}、{@code **bold**}、{@code *italic*} → 去除标记符(代码不转义、bold 加粗);</li>
 *   <li>{@code <font color="#RRGGBB">…</font>} → 文字着色(支持 HTML 颜色名/十六进制);</li>
 *   <li>连续空行归并为一段,普通行保留原换行。</li>
 * </ul>
 */
public final class MdText {

    private MdText() {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern LIST = Pattern.compile("^([-*+])\\s+(.*)$");
    private static final Pattern CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern FONT = Pattern.compile("(?is)<font\\s+color\\s*=\\s*[\"']?([^\"'>\\s]+)[\"']?\\s*>([^<]*)</font>");
    /** 列表圆点半径(占正文字号的倍数) */
    private static final float BULLET_RADIUS_SCALE = 0.22f;
    /** 圆点与文字的水平间距(占正文字号的倍数) */
    private static final float BULLET_GAP_SCALE = 0.55f;

    /** 渲染 markdown 文本为可读 Spannable(调用方放入 TextView.setMovementMethod 可点链接等场景自行处理) */
    public static CharSequence render(String md) {
        if (md == null || md.isEmpty()) return "";
        String[] rawLines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        boolean first = true;
        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                // 空行 → 分段
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
                continue;
            }
            if (!first) {
                sb.append('\n');
            }
            first = false;
            appendLine(sb, line);
        }
        return sb;
    }

    private static void appendLine(SpannableStringBuilder sb, String line) {
        // 标题 → 加粗 + 保留 # 前缀语义可读(直接去掉 #,标题大字由外层字号决定)
        Matcher h = HEADING.matcher(line);
        if (h.matches()) {
            appendStyled(sb, h.group(2), true);
            return;
        }
        // 列表项 → 自定义实心圆点(与文字中线对齐,大小/间距可调)
        Matcher li = LIST.matcher(line);
        if (li.matches()) {
            int bulletStart = sb.length();
            sb.append('\u0005');
            sb.setSpan(new CircleBulletSpan(), bulletStart, bulletStart + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            appendStyled(sb, li.group(2), false);
            return;
        }
        // 引用/分割线等 → 去符号
        if (line.startsWith(">")) {
            appendStyled(sb, line.substring(1).trim(), false);
            return;
        }
        if (line.matches("[-*_]{3,}")) {
            sb.append("──────────");
            return;
        }
        appendStyled(sb, line, false);
    }

    /** 处理行内标记:`` `code` `` 去反引号、`**bold**` 加粗、`*italic*` 斜体、`<font color>` 着色、`[t](url)` 取文本,追加到 sb */
    private static void appendStyled(SpannableStringBuilder sb, String text, boolean forceBold) {
        String clean = text;
        // 行内链接 [t](url) → t
        clean = clean.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // 颜色:<font color="...">文字</font> 分段处理,颜色段内的文字不再参与 bold/italic 解析
        Matcher font = FONT.matcher(clean);
        int last = 0;
        while (font.find()) {
            // 普通段(颜色标签之前)
            if (font.start() > last) {
                appendRichSegment(sb, clean.substring(last, font.start()), forceBold, 0);
            }
            // 颜色段
            int color = parseColor(font.group(1));
            appendRichSegment(sb, font.group(2), forceBold, color);
            last = font.end();
        }
        if (last < clean.length()) {
            appendRichSegment(sb, clean.substring(last), forceBold, 0);
        }
    }

    /** 解析颜色:支持 {@code #RRGGBB} / {@code #AARRGGBB} 十六进制,以及 HTML 颜色名(red/orange/gray 等) */
    private static int parseColor(String value) {
        if (value == null) return 0;
        String v = value.trim();
        if (v.startsWith("#")) {
            try {
                return Color.parseColor(v);
            } catch (IllegalArgumentException ignored) {
                return 0;
            }
        }
        // HTML 颜色名(常用子集)
        switch (v.toLowerCase()) {
            case "red": return 0xFFF44336;
            case "orange": return 0xFFFF9800;
            case "yellow": return 0xFFFFC107;
            case "green": return 0xFF4CAF50;
            case "blue": return 0xFF2196F3;
            case "gray": case "grey": return 0xFF9E9E9E;
            case "white": return 0xFFFFFFFF;
            case "black": return 0xFF000000;
            default:
                try {
                    return Color.parseColor(v);
                } catch (IllegalArgumentException ignored) {
                    return 0;
                }
        }
    }

    /** 渲染一段文本(可能含 code/bold/italic),整段可选加粗,可选着色 */
    private static void appendRichSegment(SpannableStringBuilder sb, String text, boolean forceBold, int color) {
        String clean = text;
        // 代码:先取出(code 内不做样式)
        Matcher code = Pattern.compile("`([^`]*)`").matcher(clean);
        java.util.ArrayList<String> codes = new java.util.ArrayList<>();
        while (code.find()) codes.add(code.group(1));
        clean = clean.replaceAll("`([^`]*)`", "\u0001");
        // 加粗
        Matcher b = BOLD.matcher(clean);
        java.util.ArrayList<String> bolds = new java.util.ArrayList<>();
        while (b.find()) bolds.add(b.group(1));
        clean = clean.replaceAll("\\*\\*([^*]+)\\*\\*", "\u0002");
        // 斜体
        Matcher i = ITALIC.matcher(clean);
        java.util.ArrayList<String> italics = new java.util.ArrayList<>();
        while (i.find()) italics.add(i.group(1));
        clean = clean.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "\u0003");

        // 按占位符顺序还原
        int bi = 0, ii = 0, ci = 0;
        StringBuilder out = new StringBuilder();
        java.util.ArrayList<int[]> boldRanges = new java.util.ArrayList<>();
        java.util.ArrayList<int[]> italicRanges = new java.util.ArrayList<>();
        for (int n = 0; n < clean.length(); n++) {
            char c = clean.charAt(n);
            if (c == '\u0002' && bi < bolds.size()) {
                int s = out.length();
                out.append(bolds.get(bi++));
                boldRanges.add(new int[]{s, out.length()});
            } else if (c == '\u0003' && ii < italics.size()) {
                int s = out.length();
                out.append(italics.get(ii++));
                italicRanges.add(new int[]{s, out.length()});
            } else if (c == '\u0001' && ci < codes.size()) {
                out.append(codes.get(ci++));
            } else {
                out.append(c);
            }
        }
        String seg = out.toString();
        int start = sb.length();
        sb.append(seg);
        int end = sb.length();
        for (int[] r : boldRanges) {
            int s = start + r[0];
            int e = Math.min(start + r[1], end);
            if (s < e) sb.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (int[] r : italicRanges) {
            int s = start + r[0];
            int e = Math.min(start + r[1], end);
            if (s < e) sb.setSpan(new StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (color != 0 && start < end) {
            sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (forceBold && start < end) {
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 便捷:是否包含 md 结构(标题/列表),供 UI 决定是否启用渲染 */
    public static boolean hasStructure(String md) {
        if (md == null) return false;
        for (String raw : md.split("\n")) {
            String t = raw.trim();
            if (HEADING.matcher(t).matches() || LIST.matcher(t).matches()) return true;
        }
        return false;
    }

    /**
     * 列表项圆点:自己绘制实心圆,圆心对齐文字视觉中线(基于 ascent/descent,而非不可靠的 top/bottom),
     * 半径与间距按正文字号等比缩放,避免 {@code •} 字符基线对齐导致的「圆点偏小、离文字远」。
     */
    private static final class CircleBulletSpan extends ReplacementSpan {

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float textSize = paint.getTextSize();
            int radius = Math.round(textSize * BULLET_RADIUS_SCALE);
            int gap = Math.round(textSize * BULLET_GAP_SCALE);
            // 宽度 = 圆点直径 + 右侧间距
            return radius * 2 + gap;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            float textSize = paint.getTextSize();
            int radius = Math.max(1, Math.round(textSize * BULLET_RADIUS_SCALE));
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            // 视觉中线:baseline(y) 偏移 (ascent+descent)/2;ascent 为负,descent 为正
            float cy = y + (fm.ascent + fm.descent) / 2f;
            float cx = x + radius;
            Paint.Style oldStyle = paint.getStyle();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(oldStyle);
        }
    }
}
