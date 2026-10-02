package com.github.tvbox.osc.bean.theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题可配置项的<b>唯一清单</b>(纯数据):编辑页列出哪些项、怎么分类、每项叫什么、说明是什么,
 * 全从这份表派生;导出导入的字段校验、离线校验脚本也认它。
 *
 * <p>键名与 {@code app/src/main/assets/theme/themes/{bright,dark}/*.json} 一一对应 ——
 * 内置主题是"照这份表写出来的",自定义主题是"照这份表填出来的",两者格式完全一致,
 * 所以内置主题文件可以直接当模板发给别人改。
 *
 * <p><b>类目</b>(编辑页按此分组,顺序即展示顺序):
 * <ol>
 *   <li>{@link ThemeKey.Group#SURFACE} 底色与面:页面底、组件/卡片底、悬浮面 + 各自的透明度;</li>
 *   <li>{@link ThemeKey.Group#TEXT} 文字分级:文字主色与高亮色;次要/占位/禁用等由主色派生;</li>
 *   <li>{@link ThemeKey.Group#BRAND} 主色与按钮:主色、主色上的字、主/次按钮底色与文字;</li>
 *   <li>{@link ThemeKey.Group#STATE} 状态与开关:开关轨道与圆点、固定深色面强调色、下载状态、
 *       危险操作红底三件套之一(红底与红底上的字)。</li>
 * </ol>
 *
 * <p>不在可配置范围内的那两个键:{@code type}(主题类型,编辑页单独一项)、{@code desc}(说明,
 * 跟着本表走,不进主题文件)。文件里出现本表之外的键会被忽略并告警(见 ThemeDef 解析)。
 */
public final class ThemeSpec {

    /** 内置亮色主题的资源名(assets 下的文件名,也是"跟随系统"在亮色下的出厂默认) */
    public static final String BUILTIN_BRIGHT = "bright";
    /** 内置暗色主题的资源名 */
    public static final String BUILTIN_DARK = "dark";

    private static final List<ThemeKey> ALL;
    private static final Map<String, ThemeKey> BY_KEY;
    private static final List<ShapeKey> SHAPES;
    private static final Map<String, ShapeKey> SHAPE_BY_KEY;

    /** 形状编辑项。数值统一是 dp,不带单位后缀。 */
    public static final class ShapeKey {
        public enum Kind { RADIUS, STROKE }

        public final String key;
        public final Kind kind;
        public final String label;
        public final String description;
        public final float defaultDp;
        public final float maxDp;

        ShapeKey(String key, Kind kind, String label, String description,
                 float defaultDp, float maxDp) {
            this.key = key;
            this.kind = kind;
            this.label = label;
            this.description = description;
            this.defaultDp = defaultDp;
            this.maxDp = maxDp;
        }
    }

    static {
        List<ThemeKey> list = new ArrayList<>();

        // ① 底色与面
        // 说明文字会被编辑页直接显示在每一项下面(不再起标题行),所以这里的 desc 要求:
        // **一句短语说清"这颜色用在哪",不要重复键名** —— 编辑页一行放得下、扫一眼就懂。
        //
        // 命名与作用域口径(2026-09,踩过五次坑,别再改回去):
        //   · 最早是两个颜色键 bg_component(小块控件)+ bg_float(页面级面),各配一个自己的 _alpha;
        //     用户调不出差别、还经常调错(说明写反过一次),于是**颜色合并成一个** bg_surface;
        //   · 合并时一度把"卡片透明度"一起删了(用户:"我只是让你合并颜色,没让你合并透明度")
        //     → **透明度保持两档**;两个透明度也不能叫成"_alpha of 某个颜色键"→ 按作用域平铺命名;
        //   · **两档各管哪一层,逐次校正到现在的口径**:
        //       bg_card_alpha  = **页面层**:页面卡片(我的页/设置页/本地视频页…)、二级页标题栏、
        //                        底部导航栏、主页搜索框、海报占位,以及这些卡片**里面的行/块**
        //                        (弹窗列表行、筛选块、Material 容器);
        //       bg_float_alpha = **浮层**:弹窗、抽屉、**气泡(长按动作气泡 / 上次看到 xxx)**、
        //                        **首页直播·筛选悬浮钮与更新悬浮圈**(用户口径:"这些也走 bg_float_alpha")。
        //     即:页面上的东西(含卡片里的 item)跟 bg_card_alpha;浮起来的那一层跟 bg_float_alpha。
        // 资源名对应:页面层 = bg_surface / bg_card;浮层 = bg_float。
        // 页面背景(bg_body)不在这张卡里:它归编辑页下面的「背景」块(纯色就是它),别两处都列。
        list.add(new ThemeKey("bg_body", ThemeKey.Group.SURFACE, ThemeKey.Kind.COLOR, "页面背景",
                "所有页面的底(纯色背景时就是它)"));
        list.add(new ThemeKey("bg_surface", ThemeKey.Group.SURFACE, ThemeKey.Kind.COLOR, "卡片与浮层底色",
                "页面卡片、标题栏、底栏、弹窗、列表行共用的颜色"));
        list.add(new ThemeKey("bg_card_alpha", ThemeKey.Group.SURFACE, ThemeKey.Kind.ALPHA, "卡片透明度",
                "页面层:卡片/标题栏/底栏/搜索框/占位/卡片里的行"));
        list.add(new ThemeKey("bg_float_alpha", ThemeKey.Group.SURFACE, ThemeKey.Kind.ALPHA, "浮层透明度",
                "弹窗、抽屉、气泡、首页直播/筛选悬浮钮"));
        // 卡片类目底色/文字这一对键已按用户要求**移除**(从未接到任何组件,留着只是让主题编辑器多两行看不懂的项)。
        // 海报卡角上那个角标依旧是**故意固定**的深底白字(见 res/values/colors.xml 的 poster_badge_bg),
        // 别拿它当"类目"重新认领。

        // ② 文字分级
        // **文字主色(brand)排在最前**:用户口径 —— "主题主色改成文字主色,并把文字主色调整到次要文字前面"。
        // 它同时是"正文默认色"与空心件的文字/描边色(与 text_main 是同一个值,见 ThemePaletteFactory)。
        // **不允许带透明度**(opaqueOnly):它是正文、空心按钮文字、勾选框填充,还是次要/禁用两级的计算来源,
        // 一带透明度整片发虚 —— 派生时 alpha 强制归 100,编辑页也不给透明度滑杆。
        list.add(new ThemeKey("brand", ThemeKey.Group.TEXT, ThemeKey.Kind.COLOR, "文字主色",
                "正文/列表文字的默认色;空心与纯文字按钮的文字和描边、进度条、勾选框也取它。"
                        + "只能纯色(不允许设透明度)", true));
        // **正文颜色(text_main)的键已移除**:它与文字主色共用同一个值(用户口径:
        // "正文颜色和主题主色共用,移除正文颜色的key")—— 资源名 text_main 仍然存在,由 brand 派生,
        // 所以布局/代码里的 @color/text_main、@color/text_foreground 一处都不用改。
        // **次要文字(text_sub)/ 禁用文字(text_disable)/ 占位提示(text_hint)三个键都已移除**:
        // 用户口径 —— "这两块的文字颜色通过计算获得,其值为文字主色透明度 60%"、
        // "搜索框里的提示文本颜色没走文字主色透明度那种"。现在
        //   text_sub = text_disable = 文字主色 @60%;
        //   text_hint                = 文字主色 @40%。
        // 资源名三个都照旧生成(@color/text_hint / text_sub_foreground / disable_text 等别名也在),
        // 布局与代码里那 100 多处引用一行都不用改,只是不再单独配。
        // 强调文字 text_accent 也不单独配：标题与普通选中文字跟 brand，
        // 链接与搜索来源仍由 text_highlight 单独控制。
        list.add(new ThemeKey("text_highlight", ThemeKey.Group.TEXT, ThemeKey.Kind.COLOR, "高亮文字",
                "链接、搜索结果来源、展开/复制"));
        // 危险文字(text_danger)与危险红底(swipe_red / swipe_red_text)已**固定成字面量**,
        // 不再进主题文件(用户口径:"删除,对应组件固定就是这个颜色")—— 见 res/values/colors.xml。

        // ③ 按钮:实心按钮与选中项的底、描边由 btn_confirm_bg 独立控制;
        //    上面的文字由 btn_confirm_text 独立配置,让填充与文字有足够对比。
        //      · 空心按钮与未选中小组件的描边跟文字主色同源;
        //        btn_cancel_bg 仅用于无文字容器与输入框(原键名保留兼容);
        //      · 「次按钮文字」已删除:空心按钮的文字走**文字主色**;
        //      · 「主色上的文字」(brand_text)已删除:btn_select_text 从实心按钮文字派生。
        list.add(new ThemeKey("btn_confirm_bg", ThemeKey.Group.BRAND, ThemeKey.Kind.COLOR, "实心按钮背景色",
                "主按钮和实心选中项的填充与描边;可与文字主色分别设置,只允许不透明色", true));
        list.add(new ThemeKey("btn_confirm_text", ThemeKey.Group.BRAND, ThemeKey.Kind.COLOR, "实心按钮文字",
                "主按钮和实心选中项上的文字;可与文字主色分别设置"));
        list.add(new ThemeKey("btn_cancel_bg", ThemeKey.Group.BRAND, ThemeKey.Kind.COLOR, "无字容器边框颜色",
                "**已从主题编辑器移除**(2026-10-01,用户口径):空心按钮的描边一律跟自己的文字色走,"
                        + "这个键只剩内部用途 —— 输入框边线/无文字描边容器(btn_stroke 同值);"
                        + "改它请编辑 assets/theme/themes/ 下的主题 JSON"));

        // ④ 状态与开关
        // 「开关-开」与「下载完成」原来是两个键、值也一直是同一个绿(#08CA2C),合并成一个
        // **正向状态色 success**:开关打开、下载完成、更新完成都取它
        // (派生名 switch_track_on / download_done 仍然存在,组件代码不用动,见 build.gradle 的派生表)。
        list.add(new ThemeKey("success", ThemeKey.Group.STATE, ThemeKey.Kind.COLOR, "完成/开启色",
                "开关打开、下载完成这类正向状态"));
        // 「开关-关」已**不再是配置项**(2026-10-01,用户口径"开关关闭颜色不自定义,改成开关开启色 透明度30%,
        // 同时移除主题配置里的开关-关选项"):它现在是派生值 = 开关开启色 @30% 透明
        // (生成侧见 build.gradle 的派生表,运行期见 ThemePaletteFactory)——
        // 与 switch_track_on / download_done 同一档:调色板仍有这个名字,只是不占配置、不进编辑器。
        list.add(new ThemeKey("switch_thumb", ThemeKey.Group.STATE, ThemeKey.Kind.COLOR, "开关圆点",
                "开关上的圆点"));
        // 直播页"选中/聚焦那一条频道"的底原来是写死的蓝 accent_on_dark(唯一还在用那支蓝的地方),
        // 已按用户口径**移除**:直播页那两张列表的选中态现在走全站同一套主题色 ——
        // 底与描边取实心按钮背景色 btn_confirm_bg,文字取 btn_confirm_text,
        // 见 btn_select_bg / btn_select_text / btn_select_stroke 与 res/color/live_channel_text.xml。
        // 所以主题文件里不再有 accent_on_dark 这个键。
        list.add(new ThemeKey("download_active", ThemeKey.Group.STATE, ThemeKey.Kind.COLOR, "下载中",
                "下载中的状态色"));

        ALL = Collections.unmodifiableList(list);
        Map<String, ThemeKey> map = new LinkedHashMap<>();
        for (ThemeKey k : list) map.put(k.key, k);
        BY_KEY = Collections.unmodifiableMap(map);

        List<ShapeKey> shapes = new ArrayList<>();
        shapes.add(shape(ThemeShapePalette.RADIUS_BACKGROUND, ShapeKey.Kind.RADIUS, "大面板圆角", "页面大卡片", 26f));
        shapes.add(shape(ThemeShapePalette.RADIUS_DIALOG, ShapeKey.Kind.RADIUS, "弹窗圆角", "弹窗与抽屉面板", 16f));
        shapes.add(shape(ThemeShapePalette.RADIUS_CARD, ShapeKey.Kind.RADIUS, "卡片圆角", "列表行、海报与卡片小块", 16f));
        shapes.add(shape(ThemeShapePalette.RADIUS_BTN, ShapeKey.Kind.RADIUS, "按钮圆角", "主按钮、空心按钮与文字按钮", 12f));
        shapes.add(shape(ThemeShapePalette.RADIUS_WIDGET_BTN, ShapeKey.Kind.RADIUS, "小组件按钮圆角", "0–15 为圆角矩形;16–17 会形成胶囊", 12f));
        shapes.add(shape(ThemeShapePalette.RADIUS_SEARCH, ShapeKey.Kind.RADIUS, "搜索框圆角", "搜索框专用", 16f));
        shapes.add(shape(ThemeShapePalette.COMMON_CORNERS, ShapeKey.Kind.RADIUS, "小件圆角", "弹窗与抽屉内的小元素", 12f));
        // 缩略图专用(下载页小封面 / 本地视频小图)。**列在这里是为了让主题文件能解析/校验这个键**
        // (自定义主题 JSON 的 radii 段照旧能存它);它**不出现在主题编辑器界面**里 ——
        // ThemeEditorActivity 会跳过 Kind.RADIUS 的项(用户口径"主题配置里不给圆角配置选项")。
        shapes.add(shape(ThemeShapePalette.RADIUS_THUMB, ShapeKey.Kind.RADIUS, "缩略图圆角", "下载页小封面与本地视频小图", 8f));
        shapes.add(shape(ThemeShapePalette.STROKE_WIDGET_BTN, ShapeKey.Kind.STROKE, "小组件描边", "chip 与小组件按钮描边宽度", 0.5f));
        SHAPES = Collections.unmodifiableList(shapes);
        Map<String, ShapeKey> shapeMap = new LinkedHashMap<>();
        for (ShapeKey shape : shapes) shapeMap.put(shape.key, shape);
        SHAPE_BY_KEY = Collections.unmodifiableMap(shapeMap);
    }

    private static ShapeKey shape(String key, ShapeKey.Kind kind, String label,
                                  String description, float fallback) {
        float value = kind == ShapeKey.Kind.RADIUS
                ? ThemeShapePalette.defaultRadius(key) : ThemeShapePalette.defaultStroke(key);
        if (value == 0f && fallback != 0f) value = fallback;
        return new ShapeKey(key, kind, label, description, value, ThemeShapePalette.maxOf(key));
    }

    private ThemeSpec() {
    }

    /** 全部可配置项(顺序即编辑页展示顺序) */
    public static List<ThemeKey> all() {
        return ALL;
    }

    /** 按类目取(顺序即 {@link ThemeKey.Group} 的声明顺序) */
    public static List<ThemeKey> of(ThemeKey.Group group) {
        List<ThemeKey> out = new ArrayList<>();
        for (ThemeKey k : ALL) {
            if (k.group == group) out.add(k);
        }
        return out;
    }

    /** 类目列表(编辑页小标题的顺序) */
    public static List<ThemeKey.Group> groups() {
        return Collections.unmodifiableList(Arrays.asList(ThemeKey.Group.values()));
    }

    /** 按键名取定义;未知键返回 null(解析主题文件时用它识别"不认识的键") */
    public static ThemeKey byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** 键名是否是可配置项 */
    public static boolean isConfigurable(String key) {
        return byKey(key) != null;
    }

    /** 可配置项总数(内置主题文件必须一个不少) */
    public static int size() {
        return ALL.size();
    }

    public static List<ShapeKey> shapeKeys() {
        return SHAPES;
    }

    /**
     * 该颜色键是否**不进主题编辑器界面**(2026-10-01):
     * 用户口径"移除主题配置里的空心按钮边框线颜色"、"移除开关-关选项"。
     * 与"派生键"(如 switch_track_on / switch_track_off,压根不在 {@link #ALL} 里)的区别:
     * 这些键**仍然是配置项**(主题文件里照旧解析/校验/保存),只是编辑器不再列它 ——
     * 想让用户改就得编辑主题文件。圆角项是另一条路(编辑器跳过 {@code Kind.RADIUS},不在这里)。
     */
    public static boolean isHidden(String key) {
        if (key == null) return false;
        switch (key) {
            case "btn_cancel_bg":
                // 空心按钮的描边已一律跟文字色走,这个键只剩内部用途(输入框底/无文字描边容器)
                return true;
            default:
                return false;
        }
    }

    public static ShapeKey shapeByKey(String key) {
        return key == null ? null : SHAPE_BY_KEY.get(key);
    }
}
