package com.github.tvbox.osc.bean.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个主题的完整定义(纯数据,Bright 亮色 / Dark 暗色都算):<b>用户自定义主题</b>的落盘形态,
 * 也是导出/导入主题包的内容本体。
 *
 * <p>字段与 {@code assets/theme/theme_colors*.json} 保持同一套键名(多了 {@code kind/id/name/schema/background}),
 * 所以内置主题文件可以直接当模板发给别人改,改完导入即可。
 *
 * <p>几个刻意的设计:
 * <ul>
 *   <li><b>{@link #id} 与 {@link #name} 分开</b>:id 是内部主键(生成,不随改名变化,背景图引用等
 *       都以它为准),name 是用户起的显示名(唯一,可改);</li>
 *   <li><b>{@link #colors} 永远物化全部 {@link ThemeSpec#all()} 键</b>({@link #materialize}):
 *       缺键从同类型内置主题补齐。这样"编辑页每行都有值可显示""导出给别人用时对方拿到的是完整主题",
 *       也避免了"少写一个键 → 运行时按别的主题的色兜底"这种难查的观感问题;</li>
 *   <li><b>背景只有三种模式</b>(见 {@link Background}):跟随默认 / 纯色 / 图片。摆放(缩放、位置、
 *       透明度、遮罩)仍归全局的"设置背景图"页,主题只管"用哪张图/什么纯色",避免同一件事两处配置;</li>
 *   <li><b>图片用相对路径引用</b>:{@code theme_bg/<hash>.webp},相对应用私有目录 ——
 *       导出成 zip 后路径依然成立,导入方解出来的图落在同一个相对位置,JSON 不用改写。</li>
 * </ul>
 */
public final class ThemeDef {

    /** 主题包标识:认这个字段才知道"这是 MBox 的主题",避免把别的 JSON 当主题导入 */
    public static final String KIND = "mbox-theme";

    /**
     * 格式版本:加字段/改键名时靠它做兼容(导入方不认识的更高版本会被拒绝)。
     *
     * <p>v2(2026-09):底色/状态/危险色那批键改过名；v3 增加 radii/strokes。
     * schema 1/2 读入时自动迁移并继承同类型内置形状，不必让用户重建主题。
     */
    public static final int SCHEMA = 3;

    private int schema = SCHEMA;
    private String kind = KIND;
    private String id = "";
    private String name = "";
    private ThemeType type = ThemeType.BRIGHT;
    /** 创建时间(毫秒):主题列表按它升序(新建的排在后面,与"保存后插到列表下方"的观感一致) */
    private long createdAt = 0L;
    /** 25 个可配置项:键 → 值(颜色 {@code #RRGGBB}/{@code #AARRGGBB},透明度 {@code "0".."100"}) */
    private final LinkedHashMap<String, String> colors = new LinkedHashMap<>();
    /** schema 3:语义圆角(dp,无单位后缀) */
    private final LinkedHashMap<String, Float> radii = new LinkedHashMap<>();
    /** schema 3:描边宽度(dp,无单位后缀) */
    private final LinkedHashMap<String, Float> strokes = new LinkedHashMap<>();
    private Background background = new Background();

    /** 背景图引用(相对应用私有目录;见类注释) */
    public static final class Background {

        /** 跟随该类型内置主题的默认背景(内置主题默认是纯色,即"看起来就是页面底色") */
        public static final String MODE_DEFAULT = "default";
        /** 显式纯色(不挂图,页面直接是主题的 {@code bg_body}) */
        public static final String MODE_SOLID = "solid";
        /** 用某张图片({@link #ref} 指向应用私有目录里的文件) */
        public static final String MODE_IMAGE = "image";

        /** 背景图默认不透明度(0-100;100=原图) */
        public static final int DEFAULT_ALPHA = 100;
        /** 位置锚点默认值:0.5=居中(0=起始边贴边、1=结束边贴边) */
        public static final float DEFAULT_ANCHOR = 0.5f;

        private String mode = MODE_DEFAULT;
        private String ref = "";
        // ---- 摆放(仅 mode=image 有意义):**跟着主题走**,与"设置背景图"页那套模型完全一致 ----
        /** 缩放倍率(相对铺满;<=0 自动:大图铺满、很小的图按原始像素) */
        private float zoom = 0f;
        /** 横向位置:锚点比例 0~1(与屏幕尺寸无关,转横竖屏不会漂) */
        private float anchorX = DEFAULT_ANCHOR;
        /** 纵向位置:锚点比例 0~1 */
        private float anchorY = DEFAULT_ANCHOR;
        /** 背景图自身不透明度 0-100 */
        private int alpha = DEFAULT_ALPHA;
        /** 是否打开主题色遮罩(让压在底图上的文字保持可读) */
        private boolean scrim = true;

        public String getMode() {
            return mode;
        }

        public String getRef() {
            return ref;
        }

        public void setMode(String mode) {
            this.mode = (mode == null || mode.isEmpty()) ? MODE_DEFAULT : mode;
        }

        public void setRef(String ref) {
            this.ref = ref == null ? "" : ref;
        }

        public float getZoom() {
            return zoom;
        }

        public void setZoom(float zoom) {
            this.zoom = zoom;
        }

        public float getAnchorX() {
            return anchorX;
        }

        public void setAnchorX(float anchorX) {
            this.anchorX = anchorX;
        }

        public float getAnchorY() {
            return anchorY;
        }

        public void setAnchorY(float anchorY) {
            this.anchorY = anchorY;
        }

        public int getAlpha() {
            return alpha;
        }

        public void setAlpha(int alpha) {
            this.alpha = alpha;
        }

        public boolean isScrim() {
            return scrim;
        }

        public void setScrim(boolean scrim) {
            this.scrim = scrim;
        }

        /** 是否"用图片"(只有这时才需要一个文件,导出成 zip 也只在这时带图) */
        public boolean isImage() {
            return MODE_IMAGE.equals(mode);
        }

        /** 是否等同于"跟随默认"(默认与显式纯色在渲染上是同一个结果,但语义不同:前者会跟着内置主题走) */
        public boolean isDefault() {
            return !MODE_IMAGE.equals(mode);
        }

        public Background copy() {
            Background b = new Background();
            b.mode = mode;
            b.ref = ref;
            b.zoom = zoom;
            b.anchorX = anchorX;
            b.anchorY = anchorY;
            b.alpha = alpha;
            b.scrim = scrim;
            return b;
        }
    }

    public ThemeDef() {
    }

    /** 新建一个空主题:全部键取该类型内置主题的值,背景=跟随默认 */
    public static ThemeDef blank(ThemeType type, Map<String, String> builtinInput) {
        ThemeDef def = new ThemeDef();
        def.type = type == null ? ThemeType.BRIGHT : type;
        def.createdAt = System.currentTimeMillis();
        def.background = new Background();
        if (builtinInput != null) {
            for (ThemeKey k : ThemeSpec.all()) {
                String v = builtinInput.get(k.key);
                if (v != null) def.colors.put(k.key, v);
            }
        }
        def.materialize(builtinInput);
        def.materializeShapes(ThemeShapePalette.defaults());
        return def;
    }

    /**
     * 用同类型内置主题的输入值补齐缺失的键(缺失还保留在 map 里就说明内置主题文件也缺,属异常,调用方应告警)。
     *
     * @return 本次补齐了哪些键(供日志/提示)
     */
    public List<String> materialize(Map<String, String> builtinInput) {
        List<String> filled = new ArrayList<>();
        for (ThemeKey k : ThemeSpec.all()) {
            if (colors.containsKey(k.key) && colors.get(k.key) != null) continue;
            String v = builtinInput == null ? null : builtinInput.get(k.key);
            if (v == null) continue;
            colors.put(k.key, v);
            filled.add(k.key);
        }
        return filled;
    }

    /** 用同类型内置形状补齐 schema 1/2 或残缺 schema 3。 */
    public List<String> materializeShapes(ThemeShapePalette builtin) {
        ThemeShapePalette fallback = builtin == null ? ThemeShapePalette.defaults() : builtin;
        List<String> filled = new ArrayList<>();
        for (ThemeSpec.ShapeKey key : ThemeSpec.shapeKeys()) {
            LinkedHashMap<String, Float> map = key.kind == ThemeSpec.ShapeKey.Kind.RADIUS ? radii : strokes;
            Float current = map.get(key.key);
            if (current != null && ThemeShapePalette.isValid(key.key, current)) continue;
            float value = key.kind == ThemeSpec.ShapeKey.Kind.RADIUS
                    ? fallback.radiusDp(key.key) : fallback.strokeDp(key.key);
            map.put(key.key, value);
            filled.add(key.key);
        }
        return filled;
    }

    /** 取某个键的值(没有则空串) */
    public String color(String key) {
        String v = colors.get(key);
        return v == null ? "" : v;
    }

    /** 写某个键的值(未知键忽略,防止把垃圾写进主题文件) */
    public void setColor(String key, String value) {
        if (!ThemeSpec.isConfigurable(key)) return;
        colors.put(key, value == null ? "" : value);
    }

    /** 该键解析成 ARGB(非法值取 fallback) */
    public int argb(String key, int fallback) {
        ThemeKey def = ThemeSpec.byKey(key);
        String raw = colors.get(key);
        if (def == null) return fallback;
        return def.isAlpha()
                ? ThemePalette.withAlpha(fallback, ThemePalette.parsePercent(raw, ThemePalette.alphaPercentOf(fallback)))
                : ThemePalette.parseColor(raw, fallback);
    }

    /** 深拷贝(编辑页拿一份草稿改,确认后才回写) */
    public ThemeDef copy() {
        ThemeDef d = new ThemeDef();
        d.schema = schema;
        d.kind = kind;
        d.id = id;
        d.name = name;
        d.type = type;
        d.createdAt = createdAt;
        d.colors.putAll(colors);
        d.radii.putAll(radii);
        d.strokes.putAll(strokes);
        d.background = background.copy();
        return d;
    }

    // ---- getter/setter ----

    public int getSchema() {
        return schema;
    }

    public void setSchema(int schema) {
        this.schema = schema;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public ThemeType getType() {
        return type;
    }

    public void setType(ThemeType type) {
        this.type = type == null ? ThemeType.BRIGHT : type;
    }

    /** 创建时间(毫秒);主题列表按它升序 */
    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /** 可直接改的键值视图(仅同包/序列化用;外部请走 {@link #color}/{@link #setColor}) */
    public LinkedHashMap<String, String> colors() {
        return colors;
    }

    public LinkedHashMap<String, Float> radii() {
        return radii;
    }

    public LinkedHashMap<String, Float> strokes() {
        return strokes;
    }

    public float radius(String key) {
        Float value = radii.get(key);
        return value == null ? ThemeShapePalette.defaultRadius(key) : value;
    }

    public float stroke(String key) {
        Float value = strokes.get(key);
        return value == null ? ThemeShapePalette.defaultStroke(key) : value;
    }

    /** 写圆角:超上限**夹到上限**(与构建期 readRadii、与 ThemeShapePalette 同口径),负数/NaN 才回默认 */
    public void setRadius(String key, float value) {
        if (!ThemeShapePalette.isRadiusKey(key)) return;
        if (!Float.isFinite(value) || value < 0f) {
            radii.put(key, ThemeShapePalette.defaultRadius(key));
        } else {
            radii.put(key, Math.min(value, ThemeShapePalette.maxOf(key)));
        }
    }

    /** 写描边:同 {@link #setRadius} 的口径 */
    public void setStroke(String key, float value) {
        if (!ThemeShapePalette.isStrokeKey(key)) return;
        if (!Float.isFinite(value) || value < 0f) {
            strokes.put(key, ThemeShapePalette.defaultStroke(key));
        } else {
            strokes.put(key, Math.min(value, ThemeShapePalette.maxOf(key)));
        }
    }

    public Background getBackground() {
        return background;
    }

    public void setBackground(Background background) {
        this.background = background == null ? new Background() : background;
    }

    /** 背景图引用(仅 {@code mode=image} 时有意义) */
    public String backgroundRef() {
        return background.isImage() ? background.getRef() : "";
    }

    /** 是否"看起来有背景图"(导出时据此决定要不要打包图片) */
    public boolean hasBackgroundImage() {
        return background.isImage() && !background.getRef().isEmpty();
    }
}
