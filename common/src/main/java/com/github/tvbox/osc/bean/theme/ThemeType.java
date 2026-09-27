package com.github.tvbox.osc.bean.theme;

/**
 * 主题类型:决定这个主题"是亮色还是暗色"。
 *
 * <p>它不只是个标签,而是三件事的依据:
 * <ol>
 *   <li><b>夜间模式</b>:选中某个自定义主题时,{@code AppCompatDelegate} 按它的类型强制亮/暗
 *       (所以"浅色主题"在暗色系统上也是浅色);</li>
 *   <li><b>弹窗/气泡/状态栏</b>:{@code Utils.isAppDarkTheme()} 与 XPopup 的 {@code isDarkTheme(...)}
 *       都按它取口径(ROM 上 {@code uiMode} 与 App 模式不同步时不会误判);</li>
 *   <li><b>"跟随系统"取哪一份默认</b>:跟随系统时按当前系统明暗,取<b>该类型</b>的默认主题
 *       (亮/暗各有一份,见 {@code ThemeSelection});</li>
 *   <li><b>重置默认值的来源</b>:每个色值"恢复默认"取的都是<b>同类型</b>内置主题的同名键
 *       (见 {@code ThemeSpec#defaultsFor})。</li>
 * </ol>
 *
 * <p>取值与主题文件里的 {@code "type"} 字段一致({@code theme_colors.json} 是 {@code "bright"},
 * {@code theme_colors_night.json} 是 {@code "dark"}),这样内置主题文件、自定义主题 JSON、
 * 导出的主题包三者可以互认。
 */
public enum ThemeType {

    /** 亮色(对应 {@code theme_colors.json} 的 {@code "bright"}) */
    BRIGHT("bright", "亮色"),

    /** 暗色(对应 {@code theme_colors_night.json} 的 {@code "dark"}) */
    DARK("dark", "暗色");

    /** 主题文件里的取值(小写,写进 JSON 的就是它) */
    public final String jsonValue;

    /** 界面上的中文名 */
    public final String label;

    ThemeType(String jsonValue, String label) {
        this.jsonValue = jsonValue;
        this.label = label;
    }

    /** 解析主题文件里的 type;认不出(含大小写差异/多余空白)一律按亮色兜底 */
    public static ThemeType fromJson(String value) {
        if (value != null) {
            String v = value.trim();
            for (ThemeType t : values()) {
                if (t.jsonValue.equalsIgnoreCase(v)) return t;
            }
            // 容错:手写主题包时容易写成 light/dark 之外的常见说法
            if ("light".equalsIgnoreCase(v)) return BRIGHT;
            if ("night".equalsIgnoreCase(v)) return DARK;
        }
        return BRIGHT;
    }

    /** 另一个类型(亮↔暗);"跟随系统"解析默认主题时用得上 */
    public ThemeType opposite() {
        return this == BRIGHT ? DARK : BRIGHT;
    }

    /** 是否暗色(夜间模式判定的唯一依据) */
    public boolean isDark() {
        return this == DARK;
    }
}
