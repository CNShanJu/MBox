package com.github.tvbox.osc.storage.theme;

import android.content.Context;

import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.bean.theme.ThemeJson;
import com.github.tvbox.osc.bean.theme.ThemeKey;
import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.bean.theme.ThemePaletteFactory;
import com.github.tvbox.osc.bean.theme.ThemeShapePalette;
import com.github.tvbox.osc.bean.theme.ThemeSpec;
import com.github.tvbox.osc.bean.theme.ThemeType;
import com.github.tvbox.osc.config.PrefsDataStore;
import com.github.tvbox.osc.config.SystemConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 主题门面(纯逻辑部分见 {@code :common} 的 {@code bean.theme.*}):<b>主题的唯一读写入口</b>。
 *
 * <h3>三层主题</h3>
 * <ol>
 *   <li><b>内置预设</b>:打包在 {@code assets/theme/themes/bright/} 与
 *       {@code assets/theme/themes/dark/},各目录的 {@code default=true} 预设在构建期派生成 {@code res/values} 与
 *       {@code res/values-night} 下的 {@code theme_colors.xml}
 *       (首帧就是对的)。<b>不可编辑、不可删除</b> —— 它们是"恢复出厂"的落点,
 *       也是每个色值"恢复默认"的取值来源;</li>
 *   <li><b>用户自定义主题</b>:一个主题一个 JSON 文件,落在 {@code filesDir/themes/<id>.json};
 *       可以编辑/删除/导出/导入,背景图存在 {@link ThemeBackgroundLibrary};</li>
 *   <li><b>选中关系</b>:模式(跟随系统/浅色/深色) + 选中的自定义主题 id + 亮/暗各自的默认主题 id,
 *       都存 {@code SystemConfig}(随"数据备份还原"一起走)。见 {@link #resolveActive()}。</li>
 * </ol>
 *
 * <h3>选择解析("默认主题"只服务「跟随系统」)</h3>
 * <pre>
 *   选中了自定义主题          → 用它(它的 type 决定亮暗)
 *   否则 模式=浅色           → <b>内置亮色</b>(不看默认主题)
 *   否则 模式=深色           → <b>内置暗色</b>(不看默认主题)
 *   否则 模式=跟随系统        → 按当前系统明暗取对应类型的默认主题
 * </pre>
 * "默认主题"出厂就是内置亮/暗本身;用户在列表里对某个自定义主题点"设为默认"就把它替换掉 ——
 * <b>亮、暗各只有一份</b>(设置即覆盖),<b>且只在「跟随系统」时参与解析</b>:
 * 用户显式点了浅色/深色,要的就是内置那套,不能被"该类型的默认主题"劫持
 * (否则设过默认之后再也选不回内置)。这样"跟随系统"在两种系统模式下都有一套用户认可的配色,
 * 而不是永远回到内置那套。
 *
 * <h3>线程约定</h3>
 * 读写文件的方法({@link #save} / {@link #delete} / {@link #registerBackground} / {@link #gc} /
 * {@link #importArchive})是磁盘活,<b>务必在后台线程调用</b>(app 侧统一走 {@code HeavyTaskUtil});
 * 纯查询(列表/解析/名称校验)是内存操作,可主线程调用。
 */
public final class ThemeStore {

    /** 用户主题目录(相对 filesDir) */
    public static final String DIR_THEMES = "themes";

    /** 内置主题在设置页里的显示名(与"跟随系统/浅色/深色"这两个选项同名,列表里看到的就是它) */
    public static final String NAME_BRIGHT = "浅色";
    public static final String NAME_DARK = "深色";

    /** 列表里的固定项(用户主题名不许与它们重名,否则列表上会分不清哪条是选项、哪条是主题) */
    public static final String[] RESERVED_NAMES = {"跟随系统", "浅色", "深色", "自定义主题颜色"};

    /** 名称长度上限(用户口径:6 个字以内,列表一行放得下,也不挤动标题栏) */
    public static final int NAME_MAX_LEN = 6;

    private static final Object LOCK = new Object();

    private static volatile Context appContext;
    /** 用户主题缓存(按 createdAt 升序);{@code null} = 尚未加载 */
    private static volatile List<ThemeDef> cache;

    private ThemeStore() {
    }

    /** App 启动注入(幂等);不注入则一切读写都安全地降级为"只有内置主题" */
    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        synchronized (LOCK) {
            cache = null;
        }
        synchronized (BUILTIN_INPUT_CACHE) { BUILTIN_INPUT_CACHE.clear(); }
        synchronized (BUILTIN_PALETTE_CACHE) { BUILTIN_PALETTE_CACHE.clear(); }
        synchronized (BUILTIN_SHAPE_CACHE) { BUILTIN_SHAPE_CACHE.clear(); }
        synchronized (PRESET_CACHE) { PRESET_CACHE.clear(); }
    }

    /** 是否有可用的存储上下文(没注入时设置页只显示内置主题,不会崩) */
    public static boolean isReady() {
        return appContext != null;
    }

    /** 应用上下文(同包内的 ThemeArchive/ThemeBackgroundLibrary 用;外部请走本类的公开方法) */
    static Context context() {
        return appContext;
    }

    /** 临时文件目录(导入主题包时解图用);不可用返回 null */
    static File cacheDir() {
        Context ctx = appContext;
        if (ctx == null) return null;
        File d = ctx.getCacheDir();
        if (d == null) return null;
        if (!d.exists() && !d.mkdirs()) return null;
        return d;
    }

    // ------------------------------------------------------------------
    // 内置主题(只读)
    // ------------------------------------------------------------------

    /** 内置主题文件与派生结果的解析缓存(assets 在进程内不会变,解析一次就够) */
    private static final Map<ThemeType, Map<String, String>> BUILTIN_INPUT_CACHE = new java.util.EnumMap<>(ThemeType.class);
    private static final Map<ThemeType, ThemePalette> BUILTIN_PALETTE_CACHE = new java.util.EnumMap<>(ThemeType.class);
    private static final Map<ThemeType, ThemeShapePalette> BUILTIN_SHAPE_CACHE = new java.util.EnumMap<>(ThemeType.class);
    private static final Map<ThemeType, List<Preset>> PRESET_CACHE = new java.util.EnumMap<>(ThemeType.class);
    private static final String PRESET_ROOT = "theme/themes/";
    private static final String PRESET_PREFIX = "preset:";
    private static final String BUNDLED_BACKGROUND_PREFIX = "file:///android_asset/theme/backgrounds/";
    private static final String KEY_APPLIED_BUILTIN_BACKGROUND = "theme_applied_builtin_background";

    private static final class Preset {
        final ThemeDef def;
        final boolean isDefault;

        Preset(ThemeDef def, boolean isDefault) {
            this.def = def;
            this.isDefault = isDefault;
        }
    }

    /** 按目录扫描内置预设；新增 JSON 不需要在代码里登记文件名。 */
    private static List<Preset> presets(ThemeType type) {
        ThemeType t = type == null ? ThemeType.BRIGHT : type;
        synchronized (PRESET_CACHE) {
            List<Preset> cached = PRESET_CACHE.get(t);
            if (cached != null) return cached;
            List<Preset> loaded = new ArrayList<>();
            Context ctx = appContext;
            if (ctx != null) {
                String dir = PRESET_ROOT + t.jsonValue;
                try {
                    String[] names = ctx.getAssets().list(dir);
                    if (names != null) {
                        Arrays.sort(names);
                        for (String filename : names) {
                            if (!filename.endsWith(".json")) continue;
                            try (InputStream in = ctx.getAssets().open(dir + "/" + filename)) {
                                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                byte[] bytes = new byte[8 * 1024];
                                int n;
                                while ((n = in.read(bytes)) > 0) bos.write(bytes, 0, n);
                                String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                                com.google.gson.JsonObject meta = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                                if (!meta.has("default") || !meta.get("default").isJsonPrimitive()
                                        || !meta.get("default").getAsJsonPrimitive().isBoolean()) continue;
                                ThemeJson.Result parsed = ThemeJson.parse(json);
                                if (parsed.def == null || parsed.def.getType() != t
                                        || parsed.def.getName().trim().isEmpty()) continue;
                                ThemeDef def = parsed.def;
                                def.setId(PRESET_PREFIX + t.jsonValue + "/"
                                        + filename.substring(0, filename.length() - 5));
                                loaded.add(new Preset(def, meta.get("default").getAsBoolean()));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            List<Preset> result = Collections.unmodifiableList(loaded);
            PRESET_CACHE.put(t, result);
            return result;
        }
    }

    /** 可供设置页展示的内置预设，包含默认项；返回副本以免页面改动缓存。 */
    public static List<ThemeDef> builtinPresets(ThemeType type) {
        List<ThemeDef> out = new ArrayList<>();
        for (Preset preset : presets(type)) out.add(preset.def.copy());
        return Collections.unmodifiableList(out);
    }

    public static String defaultPresetId(ThemeType type) {
        for (Preset preset : presets(type)) if (preset.isDefault) return preset.def.getId();
        return "";
    }

    public static String defaultPresetName(ThemeType type) {
        for (Preset preset : presets(type)) if (preset.isDefault) return preset.def.getName();
        return type == ThemeType.DARK ? NAME_DARK : NAME_BRIGHT;
    }

    private static ThemeDef findPreset(String id) {
        if (id == null || !id.startsWith(PRESET_PREFIX)) return null;
        for (ThemeType type : ThemeType.values()) {
            for (Preset preset : presets(type)) {
                if (id.equals(preset.def.getId())) return preset.def.copy();
            }
        }
        return null;
    }

    /** 内置主题的可配置键(直接读 assets 里那份人可读的主题文件) */
    public static Map<String, String> builtinInput(ThemeType type) {
        ThemeType t = type == null ? ThemeType.BRIGHT : type;
        synchronized (BUILTIN_INPUT_CACHE) {
            Map<String, String> cached = BUILTIN_INPUT_CACHE.get(t);
            if (cached != null) return cached;
            Map<String, String> parsed = parseBuiltinInput(t);
            BUILTIN_INPUT_CACHE.put(t, parsed);
            return parsed;
        }
    }

    private static Map<String, String> parseBuiltinInput(ThemeType type) {
        for (Preset preset : presets(type)) {
            if (preset.isDefault) return Collections.unmodifiableMap(new LinkedHashMap<>(preset.def.colors()));
        }
        return new LinkedHashMap<>();
    }

    /**
     * 内置默认主题的完整调色板:读构建期生成的 {@code assets/theme/generated/theme_derived_*.json}
     * (Gradle 的 {@code derivePalette} 产物)。它是"编译期资源里那套颜色"的运行时镜像,
     * 也是自定义主题派生时的兜底来源。
     */
    public static ThemePalette builtinPalette(ThemeType type) {
        ThemeType t = type == null ? ThemeType.BRIGHT : type;
        synchronized (BUILTIN_PALETTE_CACHE) {
            ThemePalette cached = BUILTIN_PALETTE_CACHE.get(t);
            if (cached != null) return cached;
            ThemePalette parsed = parseBuiltinPalette(t);
            BUILTIN_PALETTE_CACHE.put(t, parsed);
            return parsed;
        }
    }

    private static ThemePalette parseBuiltinPalette(ThemeType type) {
        Context ctx = appContext;
        Map<String, Integer> values = new LinkedHashMap<>();
        if (ctx != null) {
            String asset = (type == ThemeType.DARK) ? "theme/generated/theme_derived_dark.json" : "theme/generated/theme_derived_bright.json";
            try (InputStream in = ctx.getAssets().open(asset)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                com.google.gson.JsonObject o = com.google.gson.JsonParser
                        .parseString(new String(bos.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : o.keySet()) {
                    values.put(key, ThemePalette.parseColor(o.get(key).getAsString(), 0));
                }
            } catch (Throwable ignored) {
            }
        }
        if (values.isEmpty()) {
            // 兜底:派生资产缺失(极端情况)时按同类型的内置输入现算
            return ThemePaletteFactory.derive(builtinInput(type), null);
        }
        return new ThemePalette(values);
    }

    /** 自定义主题 → 调色板(按同类型内置主题派生;键缺失/非法值自动兜底) */
    public static ThemePalette paletteOf(ThemeDef def) {
        if (def == null) return builtinPalette(ThemeType.BRIGHT);
        return ThemePaletteFactory.derive(def.colors(), builtinPalette(def.getType()));
    }

    /** 内置形状调色板。当前亮暗共用一份资产,API 保留类型参数以便未来分档。 */
    public static ThemeShapePalette builtinShapes(ThemeType type) {
        ThemeType resolved = type == null ? ThemeType.BRIGHT : type;
        synchronized (BUILTIN_SHAPE_CACHE) {
            ThemeShapePalette cached = BUILTIN_SHAPE_CACHE.get(resolved);
            if (cached != null) return cached;
            ThemeShapePalette parsed = parseBuiltinShapes();
            BUILTIN_SHAPE_CACHE.put(resolved, parsed);
            return parsed;
        }
    }

    private static ThemeShapePalette parseBuiltinShapes() {
        Context ctx = appContext;
        if (ctx == null) return ThemeShapePalette.defaults();
        try (InputStream in = ctx.getAssets().open("theme/radius/theme_radii.json")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) bos.write(buffer, 0, count);
            com.google.gson.JsonObject object = com.google.gson.JsonParser
                    .parseString(new String(bos.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Number> radii = new LinkedHashMap<>();
            Map<String, Number> strokes = new LinkedHashMap<>();
            for (ThemeSpec.ShapeKey key : ThemeSpec.shapeKeys()) {
                if (!object.has(key.key) || !object.get(key.key).isJsonPrimitive()) continue;
                try {
                    float value = object.get(key.key).getAsFloat();
                    if (key.kind == ThemeSpec.ShapeKey.Kind.RADIUS) radii.put(key.key, value);
                    else strokes.put(key.key, value);
                } catch (Throwable ignored) {
                }
            }
            return new ThemeShapePalette(radii, strokes);
        } catch (Throwable ignored) {
            return ThemeShapePalette.defaults();
        }
    }

    /** 自定义主题 → 完整形状调色板;缺失/非法项继承同类型内置值。 */
    public static ThemeShapePalette shapePaletteOf(ThemeDef def) {
        if (def == null) return builtinShapes(ThemeType.BRIGHT);
        ThemeShapePalette fallback = builtinShapes(def.getType());
        Map<String, Number> radii = new LinkedHashMap<>();
        Map<String, Number> strokes = new LinkedHashMap<>();
        for (ThemeSpec.ShapeKey key : ThemeSpec.shapeKeys()) {
            if (key.kind == ThemeSpec.ShapeKey.Kind.RADIUS) {
                Float value = def.radii().get(key.key);
                radii.put(key.key, value == null ? fallback.radiusDp(key.key) : value);
            } else {
                Float value = def.strokes().get(key.key);
                strokes.put(key.key, value == null ? fallback.strokeDp(key.key) : value);
            }
        }
        return new ThemeShapePalette(radii, strokes);
    }

    /**
     * <b>当前生效配色的指纹</b>(便宜、不读 assets):模式 + 选中的自定义主题 + 亮/暗默认主题 +
     * 生效类型 + 生效主题的全部颜色键的哈希。
     *
     * <p>给"主题弹窗关闭时到底要不要重启"用:只有这个指纹变了(配色/类型真的变了)才值得重启应用 ——
     * 删掉一个没在用的主题、新建一个还没选中的主题都不该白重启一次。
     * <p>**故意不含背景图**:背景是由 {@link SystemConfig#setThemeDefaultBackground} 立刻生效的
     * (各页 onResume 重新挂载背景层),不需要重启。
     */
    public static String activePaletteFingerprint() {
        Selection s = selection();
        ThemeDef def = resolveActive();
        String content = def == null ? "builtin" : def.getId() + ":" + def.colors().hashCode()
                + ":" + def.radii().hashCode() + ":" + def.strokes().hashCode();
        return s.mode + "|" + s.customId + "|" + s.defaultBrightId + "|" + s.defaultDarkId
                + "|" + activeType() + "|" + content;
    }

    // ------------------------------------------------------------------
    // 用户主题:读
    // ------------------------------------------------------------------

    /** 全部用户主题(按创建时间升序 = 列表展示顺序;新建的排在后面) */
    public static List<ThemeDef> userThemes() {
        List<ThemeDef> list = cache;
        if (list != null) return list;
        synchronized (LOCK) {
            if (cache != null) return cache;
            List<ThemeDef> loaded = loadAll();
            cache = loaded;
            return loaded;
        }
    }

    /** 按 id 找用户主题;找不到返回 null */
    public static ThemeDef find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (ThemeDef d : userThemes()) {
            if (id.equals(d.getId())) return d;
        }
        return null;
    }

    /** 用户主题或只读预设；选择状态中的 id 可以指向两者。 */
    public static ThemeDef findSelectable(String id) {
        ThemeDef user = find(id);
        return user != null ? user : findPreset(id);
    }

    /** 该 id 是不是"用户主题"(内置/空 id 都返回 false) */
    public static boolean isUserTheme(String id) {
        return find(id) != null;
    }

    /** 是否是内置主题 id(空 = 内置;{@link ThemeSpec#BUILTIN_BRIGHT}/{@code _DARK} 也认,便于导入的包引用内置) */
    public static boolean isBuiltinId(String id) {
        return id == null || id.isEmpty()
                || ThemeSpec.BUILTIN_BRIGHT.equals(id) || ThemeSpec.BUILTIN_DARK.equals(id)
                || id.startsWith(PRESET_PREFIX);
    }

    /** 重新从磁盘加载(外部改过主题目录后用;正常流程不需要) */
    public static void reload() {
        synchronized (LOCK) {
            cache = null;
        }
        userThemes();
    }

    private static List<ThemeDef> loadAll() {
        List<ThemeDef> out = new ArrayList<>();
        Context ctx = appContext;
        if (ctx == null) return Collections.unmodifiableList(out);
        File dir = new File(ctx.getFilesDir(), DIR_THEMES);
        File[] files = dir.listFiles();
        if (files == null) return Collections.unmodifiableList(out);
        for (File f : files) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".json")) continue;
            try {
                String text = ThemeFiles.readUtf8(f);
                if (text == null || text.trim().isEmpty()) continue;
                ThemeJson.Result r = ThemeJson.parse(text);
                if (r.def == null) continue;
                ThemeDef def = r.def;
                // 文件名就是权威 id(用户手改内部字段不会把主题"改丢")
                String fileId = f.getName().substring(0, f.getName().length() - ".json".length());
                def.setId(fileId);
                if (def.getName().isEmpty()) def.setName(fileId);
                if (def.getCreatedAt() <= 0) def.setCreatedAt(f.lastModified());
                def.materialize(builtinInput(def.getType()));
                def.materializeShapes(builtinShapes(def.getType()));
                out.add(def);
            } catch (Throwable ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------
    // 用户主题:写
    // ------------------------------------------------------------------

    /** 保存结果 */
    public static final class SaveResult {
        /** 成功后的主题 id;失败为空 */
        public final String id;
        /** 失败原因(可直接提示用户);成功为 null */
        public final String error;

        private SaveResult(String id, String error) {
            this.id = id;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * 新建或更新一个用户主题(磁盘写入,须在后台线程调用)。
     *
     * <p>名称校验在这里兜底({@link #checkName});调用方(编辑页)应当<b>先校验再保存</b>,
     * 这样重名时可以直接留在输入框里让用户改名,而不是"保存失败"。
     */
    public static SaveResult save(ThemeDef def) {
        synchronized (LOCK) {
            return saveLocked(def);
        }
    }

    private static SaveResult saveLocked(ThemeDef def) {
        if (def == null) return new SaveResult(null, "主题数据为空");
        Context ctx = appContext;
        if (ctx == null) return new SaveResult(null, "存储不可用,请重启应用后再试");

        def.setName(def.getName() == null ? "" : def.getName().trim());
        String nameError = checkName(def.getName(), def.getId());
        if (nameError != null) return new SaveResult(null, nameError);

        // 键补齐到"这份主题类型"的内置值:导出的主题包永远是完整的
        def.materialize(builtinInput(def.getType()));
        def.materializeShapes(builtinShapes(def.getType()));

        boolean isNew = def.getId() == null || def.getId().isEmpty();
        if (isNew) {
            def.setId(newId());
            def.setCreatedAt(System.currentTimeMillis());
        } else if (def.getCreatedAt() <= 0) {
            ThemeDef old = find(def.getId());
            def.setCreatedAt(old != null ? old.getCreatedAt() : System.currentTimeMillis());
        }

        File dir = new File(ctx.getFilesDir(), DIR_THEMES);
        if (!dir.exists() && !dir.mkdirs()) return new SaveResult(null, "存储不可用,请重启应用后再试");
        File target = new File(dir, def.getId() + ".json");
        if (!ThemeFiles.writeUtf8Atomic(target, ThemeJson.toJson(def))) {
            return new SaveResult(null, "保存失败,请稍后再试");
        }

        synchronized (LOCK) {
            cache = null;
        }
        // 保存后立刻回收一次:改了背景(旧图没人引用)时当场清掉,别等下次删除主题
        gc();
        // 改的就是当前生效的主题(含只改了背景图)时,把它的默认背景同步过去 ——
        // 背景是"立刻生效"的(各页 onResume 重挂背景层),不必等那次重启
        if (isActiveTheme(def.getId())) {
            applyActiveBackground();
        }
        return new SaveResult(def.getId(), null);
    }

    /** 该主题是不是当前生效的那一个(判断"要不要顺手同步背景") */
    private static boolean isActiveTheme(String id) {
        ThemeDef active = resolveActive();
        return active != null && id != null && id.equals(active.getId());
    }

    /**
     * 删除一个用户主题(磁盘删除 + 背景图回收,须在后台线程调用)。
     *
     * <p><b>连带处理</b>(调用方不必自己收拾):
     * <ul>
     *   <li>它正被选中 → 选中项切回<b>同类型的内置主题</b>(模式置浅色/深色),不留悬空引用;</li>
     *   <li>它是某类型的"默认主题" → 取消该默认(跟随系统时回到内置);</li>
     *   <li>它的背景图若没有别的主题引用 → 删掉(见 {@link #gc()})。</li>
     * </ul>
     *
     * @return 是否真的删掉了(内置主题、不存在的 id 一律拒绝,返回 false)
     */
    public static boolean delete(String id) {
        synchronized (LOCK) {
            return deleteLocked(id);
        }
    }

    private static boolean deleteLocked(String id) {
        if (isBuiltinId(id)) return false;
        ThemeDef def = find(id);
        if (def == null) return false;
        Context ctx = appContext;
        if (ctx == null) return false;

        File f = new File(new File(ctx.getFilesDir(), DIR_THEMES), id + ".json");
        deleteQuietly(f);
        synchronized (LOCK) {
            cache = null;
        }

        // 选中项切回同类型的内置主题:模式置成浅色/深色(这两个模式不看默认主题),并清掉自定义选择
        if (id.equals(SystemConfig.getThemeCustomId())) {
            SystemConfig.setThemeCustomId("");
            SystemConfig.setTheme(def.getType() == ThemeType.DARK
                    ? Selection.MODE_DARK : Selection.MODE_LIGHT);
        }
        // 它当过的"默认主题"一并取消(否则跟随系统时会指向一个不存在的主题)
        if (id.equals(SystemConfig.getThemeDefaultId(def.getType().isDark()))) {
            SystemConfig.setThemeDefaultId(def.getType().isDark(), "");
        }
        gc();
        // 删的正好是生效主题(选中项已被切回同类型内置主题)时,把背景也同步过去
        applyActiveBackground();
        return true;
    }

    // ------------------------------------------------------------------
    // 名称
    // ------------------------------------------------------------------

    /**
     * 校验主题名(重名/超长/空/保留名)。
     *
     * @param name     用户输入的名字(会先 trim)
     * @param exceptId 自己那份主题的 id(改名时排除自己);新建传空
     * @return 错误文案(可直接提示用户);合法返回 null
     */
    public static String checkName(String name, String exceptId) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "主题名称不能为空";
        if (n.length() > NAME_MAX_LEN) return "主题名称最多 " + NAME_MAX_LEN + " 个字";
        for (String reserved : RESERVED_NAMES) {
            if (reserved.equalsIgnoreCase(n)) return "「" + reserved + "」是固定选项名,请换一个";
        }
        for (ThemeType type : ThemeType.values()) {
            for (Preset preset : presets(type)) {
                if (n.equalsIgnoreCase(preset.def.getName().trim())) return "已有同名内置预设,请换一个名称";
            }
        }
        if (isNameTaken(n, exceptId)) return "已有同名主题,请换一个名称";
        return null;
    }

    /** 是否已有同名主题(忽略大小写与首尾空白);{@code exceptId} 用于"改名时排除自己" */
    public static boolean isNameTaken(String name, String exceptId) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return false;
        for (ThemeDef d : userThemes()) {
            if (exceptId != null && exceptId.equals(d.getId())) continue;
            if (n.equalsIgnoreCase(d.getName() == null ? "" : d.getName().trim())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 选择与默认
    // ------------------------------------------------------------------

    /** 选中关系(纯数据,给弹窗做草稿用) */
    public static final class Selection {
        /** 跟随系统:按系统明暗取"该类型的默认主题"(默认主题只在这个模式下参与解析) */
        public static final int MODE_FOLLOW_SYSTEM = 0;
        /** 强制亮色:生效的就是内置亮色 */
        public static final int MODE_LIGHT = 1;
        /** 强制暗色:生效的就是内置暗色 */
        public static final int MODE_DARK = 2;

        /** {@link #MODE_FOLLOW_SYSTEM} / {@link #MODE_LIGHT} / {@link #MODE_DARK} */
        public int mode;
        /** 选中的自定义主题 id(空=没选) */
        public String customId;
        /** 亮色类型的默认主题 id(空=内置亮色;只在"跟随系统"时生效) */
        public String defaultBrightId;
        /** 暗色类型的默认主题 id(空=内置暗色;只在"跟随系统"时生效) */
        public String defaultDarkId;

        public Selection copy() {
            Selection s = new Selection();
            s.mode = mode;
            s.customId = customId;
            s.defaultBrightId = defaultBrightId;
            s.defaultDarkId = defaultDarkId;
            return s;
        }

        /** 与另一份选择是否等价(弹窗关闭时据此判断"要不要重启生效") */
        public boolean sameAs(Selection o) {
            return o != null && mode == o.mode
                    && eq(customId, o.customId)
                    && eq(defaultBrightId, o.defaultBrightId)
                    && eq(defaultDarkId, o.defaultDarkId);
        }

        private static boolean eq(String a, String b) {
            String x = a == null ? "" : a;
            String y = b == null ? "" : b;
            return x.equals(y);
        }

        /** 一次性提交(会分别只在有变化时才写,避免多次无谓广播) */
        public void commit() {
            SystemConfig.setTheme(mode);
            SystemConfig.setThemeCustomId(customId == null ? "" : customId);
            SystemConfig.setThemeDefaultId(false, defaultBrightId == null ? "" : defaultBrightId);
            SystemConfig.setThemeDefaultId(true, defaultDarkId == null ? "" : defaultDarkId);
        }
    }

    /** 读当前选中关系 */
    public static Selection selection() {
        Selection s = new Selection();
        s.mode = SystemConfig.getTheme();
        s.customId = SystemConfig.getThemeCustomId();
        s.defaultBrightId = SystemConfig.getThemeDefaultId(false);
        s.defaultDarkId = SystemConfig.getThemeDefaultId(true);
        return s;
    }

    // ------------------------------------------------------------------
    // 生效解析
    // ------------------------------------------------------------------

    /** 当前生效的主题(用户主题;内置时为 null,用 {@link #activeType()} + {@link #activePalette()} 即可) */
    public static ThemeDef resolveActive() {
        Selection s = selection();
        if (!s.customId.isEmpty()) {
            ThemeDef d = findSelectable(s.customId);
            if (d != null) return d;
        }
        // 「默认主题」只服务「跟随系统」:用户显式选了浅色/深色,要的就是内置那套,
        // 不能被该类型的默认主题劫持(否则设过默认之后再选浅色/深色,生效的还是自定义主题)
        if (s.mode != Selection.MODE_FOLLOW_SYSTEM) return null;
        String defId = defaultIdOf(s, resolveModeType(s));
        return defId.isEmpty() ? null : findSelectable(defId);
    }

    /** 当前生效的亮暗类型(决定夜间模式、弹窗深浅、状态栏图标) */
    public static ThemeType activeType() {
        Selection s = selection();
        if (!s.customId.isEmpty()) {
            ThemeDef d = findSelectable(s.customId);
            if (d != null) return d.getType();
        }
        return resolveModeType(s);
    }

    /** 当前生效的调色板 */
    public static ThemePalette activePalette() {
        ThemeDef def = resolveActive();
        return def != null ? paletteOf(def) : builtinPalette(activeType());
    }

    /** 设置页那一行显示的当前主题名 */
    public static String activeDisplayName() {
        ThemeDef def = resolveActive();
        if (def != null) return def.getName();
        return defaultPresetName(activeType());
    }

    /**
     * 把"当前生效主题"的背景同步给全局背景系统({@link SystemConfig#setThemeDefaultBackground})。
     *
     * <p>页面背景的解析链是 <b>用户显式设置 &gt; 主题默认背景 &gt; 纯色</b>:
     * 主题只管自己那一份默认值 —— 用户在"设置背景图"页设过图的话仍然以他设的为准,
     * 换主题不会抢走他的选择(这与既有行为一致)。
     *
     * <p><b>摆放(缩放/位置/不透明度/遮罩)也跟着主题走</b>:主题定义里有一套(见
     * {@code ThemeDef.Background} 的 zoom/anchor/alpha/scrim),在主题背景<b>正是当前生效的那份</b>
     * (即用户没有显式设过全局底图)时写进 {@link SystemConfig},于是一个主题配好的摆放
     * 换主题就整体跟着换;用户用自己的底图时则不覆盖他的调整。
     *
     * <p>切主题时调用一次即可(在重启生效的那条链路上;背景本身是立刻生效的)。
     */
    public static void applyActiveBackground() {
        Context ctx = appContext;
        ThemeDef def = resolveActive();
        String path = "";
        boolean userTheme = def != null && isUserTheme(def.getId());
        if (def != null && def.hasBackgroundImage()) {
            if (userTheme && ctx != null) {
                path = ThemeBackgroundLibrary.resolvePath(ctx, def.getBackground().getRef());
            } else if (def.getId().startsWith(PRESET_PREFIX)) {
                path = bundledBackgroundSource(def.getBackground().getRef());
            }
        }
        boolean backgroundChanged = !path.equals(SystemConfig.getThemeDefaultBackground());
        SystemConfig.setThemeDefaultBackground(path);
        // 自定义主题生效 = 它自带的背景(含摆放)优先,全局背景设置整体休眠(设置页入口也隐藏);
        // 切回内置主题(含目录预设)时 SystemConfig 会把休眠前用户的全局摆放原样恢复
        SystemConfig.setActiveThemeCustom(userTheme);

        if (!userTheme) {
            applyBuiltinBackground(def, path, backgroundChanged);
            return;
        }
        // 离开内置预设后丢弃其基线，回到预设时即使图源相同也要重新套用主题值。
        if (PrefsDataStore.contains(KEY_APPLIED_BUILTIN_BACKGROUND)) {
            PrefsDataStore.delete(KEY_APPLIED_BUILTIN_BACKGROUND);
        }
        if (path.isEmpty()) return;
        ThemeDef.Background bg = def.getBackground();
        SystemConfig.setPageBackgroundTransform(bg.getZoom(), bg.getAnchorX(), bg.getAnchorY());
        SystemConfig.setPageBackgroundAlpha(bg.getAlpha());
        SystemConfig.setPageBackgroundScrimEnabled(bg.isScrim());
    }

    /**
     * 内置预设的图源可能不变，但 JSON 中的遮罩、透明度或摆放已经更新。
     * 记录上次应用的主题值作为基线：同一预设更新时只替换仍等于旧默认的字段，
     * 用户在背景设置页调过的字段继续保留；首次建立基线或切换预设时套用全部默认值。
     */
    private static void applyBuiltinBackground(ThemeDef def, String path, boolean sourceChanged) {
        if (SystemConfig.isPageBackgroundUserSet()) return;
        ThemeDef.Background bg = def == null ? new ThemeDef.Background() : def.getBackground();
        String id = def == null ? "fallback:" + activeType().jsonValue : def.getId();
        AppliedBuiltinBackground next = new AppliedBuiltinBackground(id, path, bg);
        AppliedBuiltinBackground previous = AppliedBuiltinBackground.decode(
                PrefsDataStore.getString(KEY_APPLIED_BUILTIN_BACKGROUND, ""));
        if (!sourceChanged && next.sameAs(previous)) return;

        if (sourceChanged || previous == null || !id.equals(previous.id) || !path.equals(previous.path)) {
            SystemConfig.setPageBackgroundTransform(next.zoom, next.anchorX, next.anchorY);
            SystemConfig.setPageBackgroundAlpha(next.alpha);
            SystemConfig.setPageBackgroundScrimEnabled(next.scrim);
        } else {
            float zoom = SystemConfig.getPageBackgroundZoom();
            float anchorX = SystemConfig.getPageBackgroundAnchorX();
            float anchorY = SystemConfig.getPageBackgroundAnchorY();
            if (Float.compare(previous.zoom, next.zoom) != 0 && Float.compare(zoom, previous.zoom) == 0) zoom = next.zoom;
            if (Float.compare(previous.anchorX, next.anchorX) != 0 && Float.compare(anchorX, previous.anchorX) == 0) anchorX = next.anchorX;
            if (Float.compare(previous.anchorY, next.anchorY) != 0 && Float.compare(anchorY, previous.anchorY) == 0) anchorY = next.anchorY;
            SystemConfig.setPageBackgroundTransform(zoom, anchorX, anchorY);
            if (previous.alpha != next.alpha && SystemConfig.getPageBackgroundAlpha() == previous.alpha) {
                SystemConfig.setPageBackgroundAlpha(next.alpha);
            }
            if (previous.scrim != next.scrim && SystemConfig.isPageBackgroundScrimEnabled() == previous.scrim) {
                SystemConfig.setPageBackgroundScrimEnabled(next.scrim);
            }
        }
        PrefsDataStore.put(KEY_APPLIED_BUILTIN_BACKGROUND, next.encode());
    }

    /** 上次应用的内置背景值；只记录打包预设，不记录用户自己的背景配置。 */
    private static final class AppliedBuiltinBackground {
        final String id;
        final String path;
        final float zoom;
        final float anchorX;
        final float anchorY;
        final int alpha;
        final boolean scrim;

        AppliedBuiltinBackground(String id, String path, ThemeDef.Background bg) {
            this(id, path, bg.getZoom(), bg.getAnchorX(), bg.getAnchorY(), bg.getAlpha(), bg.isScrim());
        }

        AppliedBuiltinBackground(String id, String path, float zoom, float anchorX, float anchorY,
                                 int alpha, boolean scrim) {
            this.id = id;
            this.path = path;
            this.zoom = zoom;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.alpha = alpha;
            this.scrim = scrim;
        }

        boolean sameAs(AppliedBuiltinBackground other) {
            return other != null && id.equals(other.id) && path.equals(other.path)
                    && Float.compare(zoom, other.zoom) == 0
                    && Float.compare(anchorX, other.anchorX) == 0
                    && Float.compare(anchorY, other.anchorY) == 0
                    && alpha == other.alpha && scrim == other.scrim;
        }

        String encode() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("path", path);
            json.addProperty("zoom", zoom);
            json.addProperty("anchorX", anchorX);
            json.addProperty("anchorY", anchorY);
            json.addProperty("alpha", alpha);
            json.addProperty("scrim", scrim);
            return json.toString();
        }

        static AppliedBuiltinBackground decode(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            try {
                JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
                return new AppliedBuiltinBackground(json.get("id").getAsString(), json.get("path").getAsString(),
                        json.get("zoom").getAsFloat(), json.get("anchorX").getAsFloat(),
                        json.get("anchorY").getAsFloat(), json.get("alpha").getAsInt(),
                        json.get("scrim").getAsBoolean());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    /** 内置预设只能引用打包在 theme/backgrounds 下的单个图片文件。 */
    private static String bundledBackgroundSource(String ref) {
        if (ref == null || !ref.startsWith(BUNDLED_BACKGROUND_PREFIX)) return "";
        String filename = ref.substring(BUNDLED_BACKGROUND_PREFIX.length());
        if (!filename.matches("[A-Za-z0-9_-]+\\.(png|webp|jpg|jpeg)")) return "";
        return ref;
    }

    /** 模式 → 亮暗类型(跟随系统时看当前系统明暗) */
    private static ThemeType resolveModeType(Selection s) {
        if (s.mode == Selection.MODE_LIGHT) return ThemeType.BRIGHT;
        if (s.mode == Selection.MODE_DARK) return ThemeType.DARK;
        return systemNight() ? ThemeType.DARK : ThemeType.BRIGHT;
    }

    private static String defaultIdOf(Selection s, ThemeType type) {
        return type.isDark() ? s.defaultDarkId : s.defaultBrightId;
    }

    /** 系统当前是否暗色(仅在"跟随系统"分支用到) */
    private static boolean systemNight() {
        Context ctx = appContext;
        if (ctx == null) return false;
        try {
            int night = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable th) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 背景图库(见 ThemeBackgroundLibrary)
    // ------------------------------------------------------------------

    /**
     * 把一张压好的图收进主题图库(磁盘活,后台线程)。
     *
     * @return {@code ref}(相对 filesDir 的路径);失败返回空串
     */
    public static String registerBackground(File src) {
        synchronized (LOCK) {
            return ThemeBackgroundLibrary.register(appContext, src);
        }
    }

    /** 配置批量导入期间串行化主题文件/背景图修改，供调用方准备和恢复文件快照。 */
    public static <T> T runWithStorageLock(java.util.concurrent.Callable<T> action) throws Exception {
        synchronized (LOCK) {
            return action.call();
        }
    }

    /** {@code ref} → 绝对路径(文件不存在返回空串) */
    public static String resolveBackgroundPath(String ref) {
        return ThemeBackgroundLibrary.resolvePath(appContext, ref);
    }

    /**
     * 回收背景图:删掉没有被任何主题引用的文件。
     * <p>引用集合由<b>所有用户主题</b>汇总 —— 包括没被选中的那些(它们随时可能被切回来)。
     *
     * @return 删掉的文件数
     */
    public static int gc() {
        synchronized (LOCK) {
            return gcLocked();
        }
    }

    private static int gcLocked() {
        List<ThemeDef> themes = userThemes();
        Set<String> refs = new HashSet<>();
        for (ThemeDef d : themeListSnapshot(themes)) {
            String name = ThemeBackgroundLibrary.refOf(d);
            if (!name.isEmpty()) refs.add(name);
        }
        return ThemeBackgroundLibrary.gc(appContext, refs);
    }

    /** 汇总所有用户主题引用的背景图(导出整包/统计用) */
    public static Set<String> referencedBackgrounds() {
        Set<String> refs = new HashSet<>();
        for (ThemeDef d : themeListSnapshot(userThemes())) {
            String name = ThemeBackgroundLibrary.refOf(d);
            if (!name.isEmpty()) refs.add(name);
        }
        return refs;
    }

    /** 用一个不受缓存影响的快照做遍历(gc 期间别的线程可能刚保存/删除) */
    private static List<ThemeDef> themeListSnapshot(List<ThemeDef> current) {
        return current == null ? new ArrayList<ThemeDef>() : new ArrayList<>(current);
    }

    /** 主题图库占用字节(设置页显示"主题占用") */
    public static long backgroundUsedBytes() {
        return ThemeBackgroundLibrary.totalBytes(appContext);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 生成新主题 id:{@code t<毫秒>_<4位随机>};不依赖 UUID 类库,肉眼也能看出创建先后 */
    private static String newId() {
        return "t" + System.currentTimeMillis() + "_"
                + String.format(Locale.ROOT, "%04x", (int) (Math.random() * 0xFFFF));
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 供备份/调试:全部主题的"类型 → 列表"视图(排序稳定) */
    public static Map<ThemeType, List<ThemeDef>> userThemesByType() {
        Map<ThemeType, List<ThemeDef>> out = new TreeMap<>();
        for (ThemeType t : ThemeType.values()) out.put(t, new ArrayList<ThemeDef>());
        for (ThemeDef d : userThemes()) out.get(d.getType()).add(d);
        return out;
    }

    /** 某主题"某个键恢复默认"应当取的值(同类型内置主题的同名键) */
    public static String defaultValueOf(ThemeType type, ThemeKey key) {
        return builtinInput(type).get(key.key);
    }

    /** 某主题"整份恢复默认":所有键回到同类型内置主题 */
    public static void resetToBuiltin(ThemeDef def) {
        if (def == null) return;
        Map<String, String> builtin = builtinInput(def.getType());
        for (ThemeKey k : ThemeSpec.all()) {
            String v = builtin.get(k.key);
            if (v != null) def.setColor(k.key, v);
        }
        ThemeShapePalette shapes = builtinShapes(def.getType());
        for (ThemeSpec.ShapeKey key : ThemeSpec.shapeKeys()) {
            if (key.kind == ThemeSpec.ShapeKey.Kind.RADIUS) {
                def.setRadius(key.key, shapes.radiusDp(key.key));
            } else {
                def.setStroke(key.key, shapes.strokeDp(key.key));
            }
        }
    }

    /** 收集全部用户主题(含各自 ref)→ 供归档打包 */
    Collection<ThemeDef> allThemesForArchive() {
        return userThemes();
    }
}
