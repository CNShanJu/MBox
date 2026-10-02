package com.github.tvbox.osc.update.github;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发版正文清洗:只保留"给用户看"的部分。
 * <p>
 * 约定(见 AGENTS.md「发布与版本纪律」):Release 正文里给开发者看的内容用注释围栏包起来——
 * <pre>
 * &lt;!-- dev --&gt;
 * 内部:重构爬虫线程模型,替换依赖版本
 * &lt;!-- /dev --&gt;
 * </pre>
 * 围栏词 {@code dev}/{@code dev-only}/{@code internal}/{@code 内部}/{@code 开发者} 大小写不敏感。
 * GitHub 渲染正文时会隐藏 HTML 注释本身,所以网页端仍是干净的发版说明,App 端则整段剔除;
 * 未闭合的围栏(从 {@code <!-- dev -->} 到文末)与其余 HTML 注释同样丢弃。
 * <p>
 * 围栏需**独占一行**(允许前后空白):只在正文里"提到"围栏写法(如"内部内容用 &lt;!-- dev --&gt; 包起来")
 * 不会被当成围栏,否则会把它之后所有用户可见内容一起吞掉;这类行内注释按普通 HTML 注释剔除、保留其余文字。
 */
final class ReleaseNotes {

    private ReleaseNotes() {
    }

    /** 成对的开发者段:整段剔除(围栏独占一行,行内提及不算围栏) */
    private static final Pattern DEV_BLOCK = Pattern.compile(
            "^[ \\t]*<!--\\s*(?:dev-only|dev|internal|内部|开发者)[^>]*-->[ \\t]*$.*?"
                    + "^[ \\t]*<!--\\s*/\\s*(?:dev-only|dev|internal|内部|开发者)\\s*-->[ \\t]*$",
            Pattern.DOTALL | Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 同一行内的成对围栏({@code <!-- dev -->x<!-- /dev -->}):两个围栏都在,意图明确,整段剔除 */
    private static final Pattern DEV_BLOCK_SAME_LINE = Pattern.compile(
            "<!--\\s*(?:dev-only|dev|internal|内部|开发者)[^>]*-->[^\\n]*?"
                    + "<!--\\s*/\\s*(?:dev-only|dev|internal|内部|开发者)\\s*-->",
            Pattern.CASE_INSENSITIVE);

    /** 只有起始围栏(漏写闭合)时,视为"以下全是内部内容" */
    private static final Pattern DEV_UNTIL_END = Pattern.compile(
            "^[ \\t]*<!--\\s*(?:dev-only|dev|internal|内部|开发者)[^>]*-->[ \\t]*$.*",
            Pattern.DOTALL | Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 其余 HTML 注释(GitHub 本就不渲染) */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** 行尾空白 */
    private static final Pattern TRAILING_SPACE = Pattern.compile("(?m)[ \\t]+$");

    /** 连续空行归并 */
    private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");

    /** 只写版本号的标题行,如 {@code ## v3.5.5} / {@code 3.5.5}(分组 1 为不含 v 的版本号) */
    private static final Pattern VERSION_LINE = Pattern.compile("^(?:#{1,6}\\s+)?[vV]?([\\d][\\d.]*)\\s*$");

    /** 版本正文里的过渡引言,如 {@code 相比 v3.5.4 的更新:} */
    private static final Pattern COMPARE_LINE = Pattern.compile("^相比\\s*[vV]?[\\d.]+\\s*的更新\\s*[:：]?\\s*$");

    /** 清洗为面向用户的正文;返回空串表示这条发版没有可展示的内容 */
    static String userFacing(String body) {
        if (body == null) return "";
        String text = body.replace("\r\n", "\n").replace('\r', '\n');
        text = DEV_BLOCK.matcher(text).replaceAll("");
        text = DEV_BLOCK_SAME_LINE.matcher(text).replaceAll("");
        text = DEV_UNTIL_END.matcher(text).replaceAll("");
        text = HTML_COMMENT.matcher(text).replaceAll("");
        text = TRAILING_SPACE.matcher(text).replaceAll("");
        text = BLANK_RUN.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    /** 一个版本的说明条目:展示用版本号(不含 {@code v})+ 远端原始正文 */
    static final class Note {
        final String version;
        final String body;

        Note(String version, String body) {
            this.version = version;
            this.body = body;
        }
    }

    /**
     * 汇总各版本说明(按版本新→旧传入)。
     * <p>
     * 只有一个版本时直接给正文(观感与旧版一致);跨版本升级给一句引言 + 各版本小节(新→旧),
     * 让用户看得到中间跳过的版本改了什么。整条都是开发者内容的发版不占小节。
     *
     * @param truncated 是否因超出上限省略了更早的版本
     */
    static String aggregate(List<Note> notes, boolean truncated) {
        List<Note> shown = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (Note n : notes) {
            // 旧版 CI 曾在手写说明前再加一次版本标题。先清掉标题/引言再判空，
            // 否则只有开发者围栏的 release 也会被当作一个用户可见版本。
            String body = dropVersionHeader(userFacing(n.body), n.version);
            if (body.isEmpty()) continue;
            shown.add(n);
            bodies.add(body);
        }
        if (shown.isEmpty()) return "";
        // 单版本的版本号已在弹窗标题中，不再在正文重复。
        if (shown.size() == 1) return bodies.get(0);
        StringBuilder sb = new StringBuilder();
        // 这里的数量是有用户可读说明的版本数，不是实际跨过的版本数；
        // 最早一条说明也未必是用户当前版本，不能用它拼升级区间。
        sb.append("本次升级包含 ").append(shown.size()).append(" 个版本的更新说明")
                .append(truncated ? "，以下展示最近的说明：" : "，各版本改动如下：").append("\n\n");
        for (int i = 0; i < shown.size(); i++) {
            sb.append("## v").append(shown.get(i).version).append("\n")
                    .append(bodies.get(i));
            if (i < shown.size() - 1) sb.append("\n\n");
        }
        return sb.toString();
    }

    /** 去掉正文开头连续重复的自身版本标题与"相比 vX 的更新:"引言 */
    private static String dropVersionHeader(String body, String version) {
        String[] lines = body.split("\n", -1);
        int from = 0;
        while (from < lines.length) {
            Matcher heading = VERSION_LINE.matcher(lines[from].trim());
            boolean ownHeading = heading.matches() && version.equals(heading.group(1));
            boolean intro = COMPARE_LINE.matcher(lines[from].trim()).matches();
            if (!ownHeading && !intro) break;
            from++;
            while (from < lines.length && lines[from].trim().isEmpty()) from++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }
}
