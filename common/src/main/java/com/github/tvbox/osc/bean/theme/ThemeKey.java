package com.github.tvbox.osc.bean.theme;

/**
 * 主题可配置项的定义(纯数据,不依赖 Android):一个键、它属于哪个类目、界面上叫什么、说明是什么、
 * 值是颜色还是透明度。
 *
 * <p>存在的意义:主题文件里有多少个可配置键、每个键怎么分类、每项怎么跟用户解释,<b>全仓只有这一份</b> ——
 * 编辑页的类目/行、导出导入的字段校验、离线校验脚本都从 {@link ThemeSpec#ALL} 派生。
 * 新增一个主题键时只改这里 + 两个内置主题文件 + {@code build.gradle} 的派生表,四处漏一处会被单测/脚本挡住。
 */
public final class ThemeKey {

    /** 可配置项属于哪一类(编辑页按类目分组显示) */
    public enum Group {
        /** ①底色与面:页面底、组件/卡片底、悬浮面(含各自透明度) */
        SURFACE("底色与面"),
        /** ②文字分级:主色(正文/空心按钮文字)/占位/强调/高亮/危险。次要与禁用两级由主色计算(alpha 60%),不再是键 */
        TEXT("文字分级"),
        /** ③主色与按钮:主色、主色上的字、主/次按钮底色与文字 */
        BRAND("主色与按钮"),
        /** ④状态与开关:开关轨道与圆点、固定深色面强调色、下载状态、危险操作红底 */
        STATE("状态与开关");

        /** 编辑页的小标题 */
        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    /** 值的种类 */
    public enum Kind {
        /** 颜色:{@code #RRGGBB} 或 {@code #AARRGGBB}(后者的 AA 是自带透明度) */
        COLOR,
        /** 透明度:0-100 的整数(百分比),配合同类目里的某个底色使用 */
        ALPHA
    }

    /** 主题文件里的键名(与 {@code theme_colors.json} 完全一致) */
    public final String key;

    /** 所属类目 */
    public final Group group;

    /** 值的种类 */
    public final Kind kind;

    /** 编辑页那一行的标题(短,几个字) */
    public final String label;

    /** 编辑页那一行的说明(长,说清"这个颜色用在哪") */
    public final String desc;

    /**
     * 只允许纯色(不允许自带透明度)。
     *
     * <p>文字主色与实心按钮背景色必须不透明:前者控制正文及空心件,后者需要始终保持实心。
     * 编辑页不给这些键透明度滑杆,只收 {@code #RRGGBB}。
     */
    public final boolean opaqueOnly;

    ThemeKey(String key, Group group, Kind kind, String label, String desc) {
        this(key, group, kind, label, desc, false);
    }

    ThemeKey(String key, Group group, Kind kind, String label, String desc, boolean opaqueOnly) {
        this.key = key;
        this.group = group;
        this.kind = kind;
        this.label = label;
        this.desc = desc;
        this.opaqueOnly = opaqueOnly;
    }

    /** 是否透明度项(编辑页给它一根滑杆而不是取色板) */
    public boolean isAlpha() {
        return kind == Kind.ALPHA;
    }
}
