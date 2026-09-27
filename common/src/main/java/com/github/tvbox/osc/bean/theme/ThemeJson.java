package com.github.tvbox.osc.bean.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主题定义 ⇄ JSON 的编解码(纯数据,不碰文件系统)。
 *
 * <p>格式刻意设计成<b>人可读、手可改、可分享</b>:
 * <pre>
 * {
 *   "kind": "mbox-theme",          // 认这个字段才知道"这是 MBox 主题"
 *   "schema": 1,                   // 格式版本
 *   "id": "t1738...",              // 本地主键(导入时会换新的)
 *   "name": "暗夜紫",
 *   "type": "dark",                // bright | dark(与内置主题文件同词)
 *   "bg_body": "#141218",          // 25 个可配置键,键名/写法与 assets/theme/*.json 完全一致
 *   "bg_card_alpha": 60,      // 透明度写数字(与内置主题文件一致)
 *   ...
 *   "background": { "mode": "image", "ref": "theme_bg/3f2a....webp" }
 * }
 * </pre>
 *
 * <p>解析是<b>宽容</b>的:不认识的键只记一条警告(比如别人拿内置主题文件当模板时带的 {@code desc});
 * 缺键不算错 —— 调用方会用同类型内置主题补齐({@link ThemeDef#materialize})。
 * 但 {@code kind} 不对、{@code schema} 高于本版本、或没有一个可用键时,算<b>失败</b>(别把别的 JSON 当主题导进来)。
 */
public final class ThemeJson {

    /** 已知的非颜色顶层字段(解析时不算"不认识的键") */
    private static final String[] META_FIELDS = {
            "kind", "schema", "id", "name", "type", "createdAt", "desc", "background"};

    /**
     * <b>旧键名迁移表</b>(v1 → v2,2026-09 那批重命名;已经写进用户手机的 {@code filesDir/themes/*.json}
     * 与用户之间互相分享的旧主题包都靠它读回来,别再删)。
     *
     * <p>为什么必须有:主题文件是<b>按键名存值</b>的,键名一改,老文件里的值就变成"不认识的项"被丢掉,
     * 补齐逻辑再从内置主题取值 → 用户看到的是<b>自定义主题的值被悄悄换成了内置主题的值</b>
     * (用户原话:"为啥自定义的主题颜色,值会变?变成和我当前使用的默认主题数据一致")。
     *
     * <p>值为空串 = 该键已废弃(值丢弃,由内置主题/固定字面量接手),并给一条提示。
     */
    private static final java.util.LinkedHashMap<String, String> LEGACY_RENAMES =
            new java.util.LinkedHashMap<String, String>() {{
                // 底色与面
                // 注意:**不能**把当前键名放进来当"老文件标记" —— bg_float_alpha 一度是浮层透明度,
                // 现在仍然是这个名字(改名绕回来了),把它当标记会让新主题文件被判成老文件、
                // 从而把 text_highlight/text_accent 反过来换错(ThemeJsonTest 的往返用例当场变红)。
                put("bg_float", "bg_surface");
                put("bg_component_alpha", "bg_card_alpha");
                put("bg_component", "");          // 颜色并入 bg_surface(页面层与浮层共用),不再单独配
                put("bg_float_fab", "");          // 一直是派生名,从来不是可配置键
                // 状态色
                put("switch_track_on", "success");
                put("download_done", "success");
                put("accent_on_dark", "");        // 已移除:直播页选中态走主题(btn_select_bg/btn_select_text)
                // 危险三色已固定成 res 字面量,不再随主题走
                put("text_danger", "");
                put("swipe_red", "");
                put("swipe_red_text", "");
            }};

    /**
     * <b>中间态键名</b>(本仓开发过程中短暂用过的名字,同样按名字存过盘):无条件按新名读。
     *
     * <p>它们**不能**进 {@link #LEGACY_RENAMES} —— 那会让整份文件被判定成"老文件"从而触发下面那对键的
     * 语义对调,而用中间态写的文件其实已经是新语义了(会被反过来换错)。
     */
    private static final java.util.LinkedHashMap<String, String> INTERMEDIATE_RENAMES =
            new java.util.LinkedHashMap<String, String>() {{
                put("bg_panel_alpha", "bg_float_alpha");   // 浮层透明度一度叫 bg_panel_alpha
            }};

    /**
     * v1 里这两个键的<b>语义与现在正好对调</b>(v1: highlight=强调、accent=高亮;v2 按英文词义摆正:
     * accent=强调、highlight=高亮),所以读到老文件时要把两者的值互相换位。
     */
    private static final String SWAP_A = "text_highlight";
    private static final String SWAP_B = "text_accent";

    /** 这份 JSON 是不是旧键名的老文件(出现任意一个旧键名即认为是) */
    /**
     * 已取消的配置项:读到它们时给一句专门提示(比"不认识的项"好懂),值不参与派生、也不进识别计数。
     *
     * <p>{@code text_main}(正文文字)= 与主题主色 {@code brand} <b>合并</b>:两者永远同一个值,
     * 所以不再单独配(用户口径:"正文颜色和主题主色共用,移除正文颜色的key")。
     * 资源名 {@code text_main} 仍然存在,由 {@code brand} 派生 —— 布局与代码一行都不用改。
     *
     * <p>{@code brand_text}(主色上的文字)= 唯一用途是派生 {@code btn_select_text},
     * 现在 {@code btn_select_text} 直接取 {@code btn_confirm_text}(用户口径:"选中/小组件选中态的文字
     * 走主按钮文字"),于是它也没有输入了,一并移除。
     *
     * <p>{@code btn_cancel_text}(次按钮文字)= 已删除:空心按钮的文字走<b>文字主色</b>{@code brand}
     * (用户口径:"次按钮文字没有,空心按钮的文字颜色走文字主色")。
     *
     * <p>{@code text_sub}(次要文字)与 {@code text_disable}(禁用文字)= 已删除:两级改为<b>计算</b>得出
     * —— 文字主色 @60% 不透明度(用户口径:"移除次要文件颜色和禁用文字颜色,这两块的文字颜色通过计算获得,
     * 其值为文字主色透明度 60%")。资源名照旧生成,老文件里的值丢弃并给提示。
     */
    private static final java.util.Set<String> REMOVED_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("text_main", "brand_text", "btn_cancel_text", "text_sub", "text_disable"));

    private static boolean isLegacyFile(JsonObject o) {
        for (String k : LEGACY_RENAMES.keySet()) {
            if (o.has(k)) return true;
        }
        return false;
    }

    /** 解析结果 */
    public static final class Result {
        /** 解析出的主题;失败时为 null */
        public final ThemeDef def;
        /** 失败原因(可直接提示用户);成功时为 null */
        public final String error;
        /** 成功时的提醒(不认识的键、缺的键等),可直接拼进提示 */
        public final List<String> warnings;

        private Result(ThemeDef def, String error, List<String> warnings) {
            this.def = def;
            this.error = error;
            this.warnings = warnings;
        }
    }

    private ThemeJson() {
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 主题 → JSON 文本(键顺序固定:元信息 → 25 个可配置项 → 背景),带缩进便于阅读/手改 */
    public static String toJson(ThemeDef def) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", ThemeDef.KIND);
        o.addProperty("schema", ThemeDef.SCHEMA);
        o.addProperty("id", def.getId());
        o.addProperty("name", def.getName());
        o.addProperty("type", def.getType().jsonValue);
        o.addProperty("createdAt", def.getCreatedAt());

        for (ThemeKey k : ThemeSpec.all()) {
            String v = def.color(k.key);
            if (k.isAlpha()) {
                // 透明度写成数字:与 assets/theme/*.json 一致,手改时也不用加引号
                o.addProperty(k.key, ThemePalette.parsePercent(v, 0));
            } else {
                o.addProperty(k.key, v);
            }
        }

        JsonObject bg = new JsonObject();
        bg.addProperty("mode", def.getBackground().getMode());
        if (def.getBackground().isImage()) {
            bg.addProperty("ref", def.getBackground().getRef());
            // 摆放跟着主题走(与"设置背景图"页同一套模型);纯色/跟随默认时不必写,省得 JSON 里全是没用的数
            ThemeDef.Background b = def.getBackground();
            bg.addProperty("zoom", b.getZoom());
            bg.addProperty("anchorX", b.getAnchorX());
            bg.addProperty("anchorY", b.getAnchorY());
            bg.addProperty("alpha", b.getAlpha());
            bg.addProperty("scrim", b.isScrim());
        }
        o.add("background", bg);

        return INDENTED.toJson(o);
    }

    /** 带缩进的输出(自己拼,避免依赖宿主 app 的 Gson 单例配置) */
    private static final com.google.gson.Gson INDENTED = new com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 解析主题 JSON 文本 */
    public static Result parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Result(null, "内容是空的", null);
        }
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(text.trim());
            if (!el.isJsonObject()) return new Result(null, "不是主题文件(顶层不是 JSON 对象)", null);
            o = el.getAsJsonObject();
        } catch (Throwable th) {
            return new Result(null, "JSON 格式不正确,请检查内容", null);
        }

        // 认不认这个包:要么显式声明 kind,要么"有 type + 若干已知颜色键"(兼容直接拿内置主题文件当主题用)
        String kind = str(o, "kind");
        if (kind != null && !kind.isEmpty() && !ThemeDef.KIND.equalsIgnoreCase(kind)) {
            return new Result(null, "这不是 MBox 主题文件(kind=" + kind + ")", null);
        }
        int schema = num(o, "schema", ThemeDef.SCHEMA);
        if (schema > ThemeDef.SCHEMA) {
            return new Result(null, "主题文件版本过高(需要更新 App 后再导入)", null);
        }

        ThemeDef def = new ThemeDef();
        def.setSchema(schema == 0 ? ThemeDef.SCHEMA : schema);
        def.setKind(ThemeDef.KIND);
        def.setId(orEmpty(str(o, "id")));
        def.setName(orEmpty(str(o, "name")));
        def.setType(ThemeType.fromJson(str(o, "type")));
        def.setCreatedAt(longNum(o, "createdAt", 0L));

        List<String> warnings = new ArrayList<>();
        int recognized = 0;
        boolean legacy = isLegacyFile(o);

        // ① 老文件先整份搬到新键名(含 text_highlight ⇄ text_accent 对调)——
        //    这一步必须在按新键名读之前做,否则老值会被当成"不认识的项"丢掉、再被内置主题补齐
        if (legacy) {
            warnings.add("这是旧版本的主题文件,已自动升级键名");
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                String old = e.getKey();
                String target = LEGACY_RENAMES.containsKey(old)
                        ? LEGACY_RENAMES.get(old)
                        : (SWAP_A.equals(old) ? SWAP_B : (SWAP_B.equals(old) ? SWAP_A : null));
                if (target == null) continue;         // 不是旧键名:留给下面按新键名读
                if (target.isEmpty()) {
                    warnings.add("「" + old + "」这一项已取消,已忽略");
                    continue;
                }
                String raw = scalar(e.getValue());
                if (raw == null) continue;
                ThemeKey k = ThemeSpec.byKey(target);
                if (k != null) {
                    def.setColor(target, raw);
                    recognized++;
                }
            }
        }

        // ② 再按当前键名读一遍;老文件里"语义对调过"的那两个名字要跳过,免得把刚搬好的值又按新语义读回去
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String key = e.getKey();
            if (legacy && (LEGACY_RENAMES.containsKey(key) || SWAP_A.equals(key) || SWAP_B.equals(key))) {
                continue;
            }
            // 中间态键名:无条件按新名读(与"是不是老文件"无关,见 INTERMEDIATE_RENAMES)
            String target = INTERMEDIATE_RENAMES.containsKey(key) ? INTERMEDIATE_RENAMES.get(key) : key;
            ThemeKey def0 = ThemeSpec.byKey(target);
            if (def0 != null) {
                String raw = scalar(e.getValue());
                if (raw == null) {
                    warnings.add("「" + def0.label + "」的值看不懂,已按内置主题补齐");
                    continue;
                }
                def.setColor(target, raw);
                recognized++;
            } else if (REMOVED_KEYS.contains(key)) {
                warnings.add("「" + key + "」已与「主题主色」合并,调主色即可(这一项已忽略)");
            } else if (!isMeta(key)) {
                warnings.add("不认识的项「" + key + "」已忽略");
            }
        }
        if (recognized == 0) {
            return new Result(null, "主题文件里没有任何可识别的颜色项", null);
        }

        JsonObject bg = o.has("background") && o.get("background").isJsonObject()
                ? o.getAsJsonObject("background") : null;
        if (bg != null) {
            ThemeDef.Background b = new ThemeDef.Background();
            String mode = orEmpty(str(bg, "mode"));
            if (ThemeDef.Background.MODE_IMAGE.equals(mode)) {
                String ref = orEmpty(str(bg, "ref"));
                if (ref.isEmpty()) {
                    // 声明了图片却没有引用:退回"跟随默认",不能留个空引用让页面去加载空路径
                    warnings.add("背景图引用缺失,已改为跟随默认背景");
                    b.setMode(ThemeDef.Background.MODE_DEFAULT);
                } else {
                    b.setMode(ThemeDef.Background.MODE_IMAGE);
                    b.setRef(ref);
                    // 摆放:缺省值即"自动/居中/原图/开遮罩"(与"设置背景图"页的默认一致)
                    b.setZoom(dec(bg, "zoom", 0f));
                    b.setAnchorX(dec(bg, "anchorX", ThemeDef.Background.DEFAULT_ANCHOR));
                    b.setAnchorY(dec(bg, "anchorY", ThemeDef.Background.DEFAULT_ANCHOR));
                    b.setAlpha((int) Math.max(0, Math.min(100, num(bg, "alpha", ThemeDef.Background.DEFAULT_ALPHA))));
                    b.setScrim(bool(bg, "scrim", true));
                }
            } else if (ThemeDef.Background.MODE_SOLID.equals(mode)) {
                b.setMode(ThemeDef.Background.MODE_SOLID);
            } else {
                b.setMode(ThemeDef.Background.MODE_DEFAULT);
            }
            def.setBackground(b);
        }

        return new Result(def, null, warnings);
    }

    private static boolean isMeta(String key) {
        for (String m : META_FIELDS) {
            if (m.equals(key)) return true;
        }
        return false;
    }

    /** JSON 标量 → 字符串(数字也接受:透明度可能被手写成字符串,颜色也可能漏了引号外的 # 前缀) */
    private static String scalar(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            try {
                return el.getAsString();
            } catch (Throwable th) {
                return null;
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || !el.isJsonPrimitive() ? null : el.getAsString();
    }

    private static int num(JsonObject o, String key, int fallback) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsInt();
        } catch (Throwable th) {
            return fallback;
        }
    }

    /** 毫秒时间戳必须按 long 读:int 会截断成 1970 年的值,主题顺序就乱套了 */
    private static long longNum(JsonObject o, String key, long fallback) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsLong();
        } catch (Throwable th) {
            return fallback;
        }
    }

    /** 小数(缩放倍率/锚点比例):手改过的文件可能写成字符串,一律认 */
    private static float dec(JsonObject o, String key, float fallback) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsFloat();
        } catch (Throwable th) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsBoolean();
        } catch (Throwable th) {
            return fallback;
        }
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
