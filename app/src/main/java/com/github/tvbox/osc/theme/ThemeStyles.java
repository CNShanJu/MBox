package com.github.tvbox.osc.theme;

import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「颜色写在样式里」的共享样式 → 随主题走的属性表(运行时换肤的第四条通道)。
 *
 * <p><b>为什么必须有它</b>:{@link ThemeInflaterFactory} 只重刷<b>布局里内联写的</b>颜色属性;
 * 而全 App 的按钮颜色恰恰写在 {@code styles.xml} 的 {@code BtnPrimary} / {@code BtnSecondary} 这些
 * 共享样式里(布局里只有一句 {@code style="@style/BtnSecondary"})。样式里的 {@code @color/xxx}
 * 由系统在 native 侧解析,{@link ThemeResources} 那层 Java 包装拦不到它 ——
 * 于是自定义主题生效时页面卡片/文字都跟着变了、<b>按钮却停在编译期那套颜色</b>
 * (浅色下 = 次按钮永远浅灰 {@code #ECECF4}、主按钮永远 {@code #1F2937},夜间 = 永远透明+内置描边),
 * 用户看到的就是"按钮和主题对不上"(编辑主题页的「删除 / 导出主题」正是这么暴露出来的)。
 *
 * <p>这里手写一张表:<b>样式 id → 这个样式里"随主题走"的属性</b>(属性名 + 资源 id),
 * 由 {@code ThemeInflaterFactory#applyStyleAttrs} 在建视图时按表补一遍。
 * 布局内联写的同名属性仍然优先(内联那遍历在样式之后跑,会覆盖样式值),
 * 所以像日志页「清空」那种"样式给底色 + 内联给 {@code text_danger}"的写法不会被这张表打乱。
 *
 * <p><b>漏登一项的后果和 {@link ThemeColorAliases} 一样是静默的</b> —— 那一处颜色不跟着主题走,
 * 界面上只表现为"某几个按钮没变"。所以配套 JVM 绊线 {@code ThemeStyleCoverageTest}:
 * 把 {@code res/layout} 里用到的每个样式(含 {@code styles.xml} 里的父样式链)声明过的主题色属性
 * 扫一遍,要求本表一条不漏。**新增/修改共享样式里的颜色时必须同步这张表**。
 *
 * <p>只登记"随主题走"的属性和"需要按原 XML 重建"的 drawable:
 * 固定字面量(危险三色 {@code text_danger}/{@code swipe_red}、{@code @android:color/transparent})
 * 不登记 —— 它们本来就该和内置主题一致。
 */
public final class ThemeStyles {

    /** 一条"样式里写的、随主题走的属性":属性名(按布局里的写法,不带命名空间)+ 资源 id */
    public static final class Attr {
        /** 属性名,如 {@code textColor} / {@code backgroundTint} / {@code strokeColor} / {@code background} */
        public final String name;
        /** 属性值指向的资源(颜色或 drawable) */
        public final int resId;

        Attr(String name, int resId) {
            this.name = name;
            this.resId = resId;
        }
    }

    private static volatile Map<Integer, List<Attr>> TABLE;

    private ThemeStyles() {
    }

    /** 该样式里"随主题走"的属性;没登记过返回空表(不是 null,调用方不用判空) */
    public static List<Attr> of(int styleResId) {
        List<Attr> list = all().get(styleResId);
        return list == null ? Collections.<Attr>emptyList() : list;
    }

    /** 整张表(样式的资源 id → 属性表);绊线测试与离线脚本按它核对覆盖 */
    public static Map<Integer, List<Attr>> all() {
        Map<Integer, List<Attr>> t = TABLE;
        if (t != null) return t;
        synchronized (ThemeStyles.class) {
            if (TABLE == null) TABLE = build();
            return TABLE;
        }
    }

    private static Map<Integer, List<Attr>> build() {
        Map<Integer, List<Attr>> m = new HashMap<>();

        // ① 纯色按钮(确定/保存/开始下载):底色 + 字色 + 描边全在样式里
        List<Attr> primary = attrs(
                "textColor", R.color.btn_confirm_text,
                "backgroundTint", R.color.btn_confirm_bg,
                "strokeColor", R.color.btn_stroke);
        m.put(R.style.BtnPrimary, primary);

        // ② 空心按钮(取消/重置/下载管理):底色(默认透明) + 字色 + 描边
        List<Attr> secondary = attrs(
                "textColor", R.color.btn_cancel_text,
                "backgroundTint", R.color.btn_cancel_bg,
                "strokeColor", R.color.btn_stroke);
        m.put(R.style.BtnSecondary, secondary);

        // ③ 纯文字按钮(复制/展开/选集下载):底色透明**且无描边**,只有字色随主题
        List<Attr> ghost = attrs("textColor", R.color.btn_plain_text);
        m.put(R.style.BtnGhost, ghost);
        // 纯文字按钮(TextView 版):同上一档观感,只是没有 MaterialButton 那层;
        // 布局里个别键覆盖成强调色(如"复制"),这里登记默认那支,运行时照样跟着主题换
        m.put(R.style.TextButton, ghost);

        // 危险按钮:红底/红字是**固定字面量**(危险三色不进主题文件),只有描边继承自主按钮(随主题)
        List<Attr> dangerStroke = attrs("strokeColor", R.color.btn_stroke);
        m.put(R.style.BtnDanger, dangerStroke);
        // 危险入口(无底色 + 红字 + 描边):红字同样是固定字面量,描边随主题
        m.put(R.style.BtnDangerGhost, dangerStroke);

        // 字幕弹窗按钮:字色挂在主题文字色上(底色/描边是透明,不用换)
        m.put(R.style.SubtitleTextButton, attrs("textColor", R.color.text_foreground));

        // chip / 预设(背景图设置页):底色是主题色板画的 selector,文字色也是 selector,
        // 两个都得按原 XML 重建才吃得到自定义主题(见 ThemeDrawables)
        m.put(R.style.PageBgChip, attrs(
                "textColor", R.color.chip_text,
                "background", R.drawable.selector_chip_theme));

        return m;
    }

    private static List<Attr> attrs(Object... nameAndResId) {
        List<Attr> list = new ArrayList<>(nameAndResId.length / 2);
        for (int i = 0; i + 1 < nameAndResId.length; i += 2) {
            list.add(new Attr((String) nameAndResId[i], (Integer) nameAndResId[i + 1]));
        }
        return list;
    }
}
