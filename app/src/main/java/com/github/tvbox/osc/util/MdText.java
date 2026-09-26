package com.github.tvbox.osc.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown → Spannable 渲染(供更新说明等展示,不引入三方 md 库):
 * <ul>
 *   <li>行首 {@code #/##/### } 标题 → 加粗;</li>
 *   <li>行首 {@code - } / {@code * } 列表项 → 「• 」前缀(段内缩进);</li>
 *   <li>{@code `code`}、{@code **bold**}、{@code *italic*} → 去除标记符(代码不转义、bold 加粗);</li>
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
        // 列表项 → 「• 」前缀
        Matcher li = LIST.matcher(line);
        if (li.matches()) {
            sb.append("• ");
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

    /** 处理行内标记:`` `code` `` 去反引号、`**bold**` 加粗、`*italic*` 斜体、`[t](url)` 取文本,追加到 sb */
    private static void appendStyled(SpannableStringBuilder sb, String text, boolean forceBold) {
        String clean = text;
        // 行内链接 [t](url) → t
        clean = clean.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
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

        int idx = 0;
        appendRich(sb, clean, bolds, italics, codes, idx, forceBold);
    }

    private static void appendRich(SpannableStringBuilder sb, String clean,
                                   java.util.ArrayList<String> bolds,
                                   java.util.ArrayList<String> italics,
                                   java.util.ArrayList<String> codes,
                                   int idx, boolean forceBold) {
        // 按占位符顺序还原(用简单从左到右替换)
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
}
