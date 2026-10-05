package com.github.tvbox.osc.storage.theme;

import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.bean.theme.ThemeJson;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 主题包的导出与导入(一个主题一份,便于发给别人用)。
 *
 * <h3>两种形态</h3>
 * <ul>
 *   <li><b>不带背景图</b> → 就一个 {@code .json}(纯文本,微信里直接发文件即可,
 *       甚至可以复制文本粘贴给别人);</li>
 *   <li><b>引用用户图片</b> → 打成一个 {@code .zip},里面是
 *       <pre>
 *         暗夜紫.json
 *         theme_bg/3f2a....webp
 *         theme_bg/7a9b....webp     // 若开屏使用另一张图
 *       </pre>
 *       页面与开屏引用同一张图时只打包一次；图片条目与 JSON 的顺序没有要求。
 *       开屏若引用 App 内置 asset，则只保留 JSON 中的 asset 引用。</li>
 * </ul>
 *
 * <h3>安全(AGENTS §七:归档防 Zip Slip)</h3>
 * <b>不使用压缩包里的条目名当落盘路径</b>:图片一律先解到应用缓存目录下的临时文件
 * (名字由我们生成),再按<b>内容 hash</b> 收进主题图库({@link ThemeBackgroundLibrary#register})。
 * 于是 {@code ../../} 这类条目名最多让我们"找不到它",不可能写到目标目录之外;同理也限制了
 * 条目大小与图片数量,不会解出个磁盘炸弹。
 *
 * <p>线程约定:全是磁盘活,后台线程调用。
 */
public final class ThemeArchive {

    /** 主题包扩展名 */
    public static final String EXT_JSON = ".json";
    public static final String EXT_ZIP = ".zip";

    /** 单个条目的大小上限(背景图导入本来就限 30MB,这里留一倍余量防解压炸弹) */
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    /** 主题 JSON 正常只有几 KB，不接受异常庞大的元数据。 */
    private static final long MAX_JSON_BYTES = 1024L * 1024;
    /** zip 里最多看多少个条目(正常至多 3 个:json + 页面图 + 开屏图) */
    private static final int MAX_ENTRIES = 16;

    private ThemeArchive() {
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /** 导出结果 */
    public static final class ExportResult {
        /** 实际写出的文件(可能是 .json 或 .zip);失败为 null */
        public final File file;
        /** 失败原因(可直接提示用户) */
        public final String error;
        /** 是否带上了背景图(=写的是 zip) */
        public final boolean withImage;

        private ExportResult(File file, String error, boolean withImage) {
            this.file = file;
            this.error = error;
            this.withImage = withImage;
        }

        public boolean ok() {
            return file != null;
        }
    }

    /**
     * 把一个主题导出到目录里(文件名按主题名生成,已存在就加后缀)。
     *
     * <p>调用方只需给一个可写目录(通常是应用缓存目录或用户通过 SAF 选的位置):
     * 有背景图会写成 {@code <主题名>.zip},没有就写 {@code <主题名>.json}。
     *
     * @param def 要导出的主题(建议传 {@code ThemeStore} 里那份,保证背景图引用有效)
     */
    public static ExportResult exportTo(ThemeDef def, File destDir) {
        if (def == null) return new ExportResult(null, "主题数据为空", false);
        if (destDir == null) return new ExportResult(null, "导出位置不可用", false);
        if (!destDir.exists() && !destDir.mkdirs()) return new ExportResult(null, "导出位置不可用", false);

        String base = safeFileName(def.getName());
        String json = ThemeJson.toJson(def);

        String pageRef = def.hasBackgroundImage() ? imageRef(def.getBackground().getRef()) : "";
        String splashRawRef = def.getSplashBackground().getRef();
        boolean bundledSplash = isBundledSplashRef(splashRawRef);
        String splashRef = splashRawRef == null || splashRawRef.isEmpty() || bundledSplash
                ? "" : imageRef(splashRawRef);
        if (def.hasBackgroundImage() && pageRef.isEmpty()) {
            return new ExportResult(null, "主题背景图引用无效", false);
        }
        if (splashRawRef != null && !splashRawRef.isEmpty()
                && !bundledSplash && splashRef.isEmpty()) {
            return new ExportResult(null, "开屏背景图引用无效", false);
        }
        Map<String, File> images = new LinkedHashMap<>();
        if (!pageRef.isEmpty()) {
            File image = resolveImage(pageRef);
            if (image == null) return new ExportResult(null, "主题背景图缺失或无效", false);
            images.put(pageRef, image);
        }
        if (!splashRef.isEmpty() && !images.containsKey(splashRef)) {
            File image = resolveImage(splashRef);
            if (image == null) return new ExportResult(null, "开屏背景图缺失或无效", false);
            images.put(splashRef, image);
        }

        if (images.isEmpty()) {
            File out = uniqueFile(destDir, base, EXT_JSON);
            return ThemeFiles.writeUtf8Atomic(out, json)
                    ? new ExportResult(out, null, false)
                    : new ExportResult(null, "导出失败,请检查存储空间", false);
        }

        File out = uniqueFile(destDir, base, EXT_ZIP);
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            zos.setLevel(6); // 背景图已是 WebP,再高压缩率收益很小,6 是速度/体积的平衡点
            zos.putNextEntry(new ZipEntry(safeEntryName(base) + EXT_JSON));
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 条目名只使用通过校验的主题图库相对引用，避免写出绝对路径或目录穿越。
            for (Map.Entry<String, File> entry : images.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                try (InputStream in = new FileInputStream(entry.getValue())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        } catch (Throwable th) {
            ThemeFiles.deleteQuietly(out);
            return new ExportResult(null, "导出失败: " + th.getClass().getSimpleName(), false);
        }
        return new ExportResult(out, null, true);
    }

    /** 主题包建议文件名(标题栏/分享文案里显示用) */
    public static String suggestedFileName(ThemeDef def) {
        if (def == null) return "theme";
        return safeFileName(def.getName())
                + (def.hasBackgroundImage() || !imageRef(def.getSplashBackground().getRef()).isEmpty()
                ? EXT_ZIP : EXT_JSON);
    }

    /** 只接受主题图库中的单个图片文件名，不能让导出读取任意本机路径。 */
    private static String imageRef(String raw) {
        return ThemeBackgroundLibrary.isSafeRef(raw) ? raw : "";
    }

    /** 打包在 App 里的开屏素材在每台设备上都有，保留原引用即可。 */
    private static boolean isBundledSplashRef(String ref) {
        return ref != null && ref.matches(
                "(?i)file:///android_asset/theme/backgrounds/[a-z0-9_-]+\\.(webp|png|jpg|jpeg)");
    }

    private static File resolveImage(String ref) {
        if (ThemeStore.context() == null) return null;
        try {
            File dir = new File(ThemeStore.context().getFilesDir(), ThemeBackgroundLibrary.DIR)
                    .getCanonicalFile();
            File image = new File(ThemeStore.context().getFilesDir(), ref).getCanonicalFile();
            return dir.equals(image.getParentFile()) && image.isFile()
                    && image.length() > 0 && image.length() <= MAX_ENTRY_BYTES ? image : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /** 导入结果 */
    public static final class ImportResult {
        /** 解析出的主题(schema 已校验、颜色键已按类型补齐);失败为 null */
        public final ThemeDef def;
        /** 失败原因(可直接提示用户) */
        public final String error;
        /** 成功后的提醒(忽略的键、背景图缺失等) */
        public final List<String> warnings;
        /** 是否成功解出了背景图并收进图库 */
        public final boolean withImage;

        private ImportResult(ThemeDef def, String error, List<String> warnings, boolean withImage) {
            this.def = def;
            this.error = error;
            this.warnings = warnings == null ? new ArrayList<String>() : warnings;
            this.withImage = withImage;
        }

        public boolean ok() {
            return def != null;
        }
    }

    /**
     * 导入一个主题包文件({@code .json} 或 {@code .zip},按<b>内容</b>判断而非扩展名)。
     *
     * <p>带图的话会把图解出来收进主题图库({@link ThemeBackgroundLibrary#register},按内容 hash 去重),
     * 并把主题里的背景引用改写成库里的 ref。若这个主题随后没有被保存(用户取消了命名),
     * 调用方应当调一次 {@link ThemeStore#gc()} 把这个没人引用的图回收掉。
     */
    public static ImportResult importFrom(File file) {
        if (file == null || !file.isFile()) return new ImportResult(null, "找不到这个文件", null, false);
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            int n = in.read(head);
            if (n < 2) return new ImportResult(null, "文件是空的", null, false);
        } catch (Throwable th) {
            return new ImportResult(null, "文件读不出来", null, false);
        }
        boolean isZip = head[0] == 'P' && head[1] == 'K';
        return isZip ? importZip(file) : importPlainJson(file);
    }

    /** 直接导入一段 JSON 文本(粘贴导入) */
    public static ImportResult importText(String json) {
        ThemeJson.Result r = ThemeJson.parse(json);
        if (r.def == null) return new ImportResult(null, r.error, null, false);
        ThemeDef def = r.def;
        def.materialize(ThemeStore.builtinInput(def.getType()));
        def.materializeShapes(ThemeStore.builtinShapes(def.getType()));
        // 粘贴进来的 JSON 若引用了本机不存在的图，两种背景分别退回安全状态。
        List<String> warnings = new ArrayList<>(r.warnings);
        if (def.hasBackgroundImage()) {
            String ref = imageRef(def.getBackground().getRef());
            if (ref.isEmpty() || resolveImage(ref) == null) {
                clearPageImage(def, warnings, "主题包里的背景图不见了,已改为跟随默认背景");
            } else {
                def.getBackground().setRef(ref);
            }
        }
        if (!def.getSplashBackground().getRef().isEmpty()
                && !isBundledSplashRef(def.getSplashBackground().getRef())) {
            String ref = imageRef(def.getSplashBackground().getRef());
            if (ref.isEmpty() || resolveImage(ref) == null) {
                clearSplashImage(def, warnings, "主题包里的开屏背景图不见了,已清除图片引用");
            } else {
                def.getSplashBackground().setRef(ref);
            }
        }
        return new ImportResult(def, null, warnings, false);
    }

    private static ImportResult importPlainJson(File file) {
        String text = ThemeFiles.readUtf8(file);
        if (text == null || text.trim().isEmpty()) return new ImportResult(null, "文件内容读不出来", null, false);
        return importText(text);
    }

    private static ImportResult importZip(File zip) {
        List<String> warnings = new ArrayList<>();
        File cacheDir = ThemeStore.cacheDir();
        if (cacheDir == null) return new ImportResult(null, "存储不可用,请重启应用后再试", null, false);
        ThemeDef def;
        String pageRef;
        String splashRawRef;
        String splashRef;
        boolean bundledSplash;
        Map<String, File> extracted = new LinkedHashMap<>();
        // ZipFile 只枚举目录、不解压无关条目；图片排在 JSON 前面也能正确匹配。
        try (ZipFile archive = new ZipFile(zip)) {
            List<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements() && entries.size() <= MAX_ENTRIES) {
                entries.add(all.nextElement());
            }
            if (entries.size() > MAX_ENTRIES) {
                return new ImportResult(null, "主题包里的文件过多", null, false);
            }
            ThemeJson.Result parsed = null;
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(EXT_JSON)) continue;
                try (InputStream input = archive.getInputStream(entry)) {
                    String text = readEntryText(input);
                    if (text == null) return new ImportResult(null, "主题包里的 JSON 读不出来", null, false);
                    parsed = ThemeJson.parse(text);
                    if (parsed.def == null) return new ImportResult(null, parsed.error, null, false);
                }
                break;
            }
            if (parsed == null) {
                return new ImportResult(null, "主题包里没有找到主题文件(.json)", null, false);
            }
            def = parsed.def;
            warnings.addAll(parsed.warnings);
            def.materialize(ThemeStore.builtinInput(def.getType()));
            def.materializeShapes(ThemeStore.builtinShapes(def.getType()));

            pageRef = def.hasBackgroundImage() ? imageRef(def.getBackground().getRef()) : "";
            splashRawRef = def.getSplashBackground().getRef();
            bundledSplash = isBundledSplashRef(splashRawRef);
            splashRef = splashRawRef.isEmpty() || bundledSplash ? "" : imageRef(splashRawRef);
            if (!pageRef.isEmpty()) extracted.put(pageRef, null);
            if (!splashRef.isEmpty()) extracted.put(splashRef, null);

            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                // 不拿条目名拼落盘路径；只认与主题引用完全一致的图库相对路径。
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (!extracted.containsKey(name) || extracted.get(name) != null) continue;
                File tmp = null;
                try (InputStream input = archive.getInputStream(entry)) {
                    String suffix = name.substring(name.lastIndexOf('.'));
                    tmp = File.createTempFile("theme_import_", suffix, cacheDir);
                    if (copyEntry(input, tmp)) extracted.put(name, tmp);
                    else ThemeFiles.deleteQuietly(tmp);
                } catch (Throwable ignored) {
                    // 一张图解压失败不影响另一张图和主题本体。
                    ThemeFiles.deleteQuietly(tmp);
                }
            }
        } catch (Throwable th) {
            for (File file : extracted.values()) ThemeFiles.deleteQuietly(file);
            return new ImportResult(null, "主题包解析失败,可能文件已损坏", null, false);
        }

        Map<String, String> imported = new LinkedHashMap<>();
        for (Map.Entry<String, File> image : extracted.entrySet()) {
            File tmp = image.getValue();
            if (tmp == null) continue;
            try {
                String ref = ThemeStore.registerBackground(tmp);
                if (!ref.isEmpty()) imported.put(image.getKey(), ref);
            } catch (Throwable ignored) {
                // 保存失败只让当前图片回退，其余图片仍可导入。
            } finally {
                ThemeFiles.deleteQuietly(tmp);
            }
        }
        if (def.hasBackgroundImage()) {
            String ref = imported.get(pageRef);
            if (ref == null) clearPageImage(def, warnings,
                    "主题包里的背景图缺失或无效,已改为跟随默认背景");
            else def.getBackground().setRef(ref);
        }
        if (!splashRawRef.isEmpty() && !bundledSplash) {
            String ref = imported.get(splashRef);
            if (ref == null) clearSplashImage(def, warnings,
                    "主题包里的开屏背景图缺失或无效,已清除图片引用");
            else def.getSplashBackground().setRef(ref);
        }
        return new ImportResult(def, null, warnings, !imported.isEmpty());
    }

    private static void clearPageImage(ThemeDef def, List<String> warnings, String warning) {
        warnings.add(warning);
        def.getBackground().setMode(ThemeDef.Background.MODE_DEFAULT);
        def.getBackground().setRef("");
    }

    private static void clearSplashImage(ThemeDef def, List<String> warnings, String warning) {
        warnings.add(warning);
        def.getSplashBackground().setRef("");
        if (ThemeDef.SplashBackground.MODE_IMAGE.equals(def.getSplashBackground().getMode())) {
            def.getSplashBackground().setMode(ThemeDef.SplashBackground.MODE_THEME);
        }
    }

    private static String readEntryText(InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_JSON_BYTES) return null;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable th) {
            return null;
        }
    }

    private static boolean copyEntry(InputStream in, File target) {
        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ENTRY_BYTES) {
                    os.close();
                    ThemeFiles.deleteQuietly(target);
                    return false;
                }
                os.write(buf, 0, n);
            }
            os.flush();
            return target.length() > 0;
        } catch (Throwable th) {
            ThemeFiles.deleteQuietly(target);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 文件名
    // ------------------------------------------------------------------

    /** 主题名 → 安全文件名:去掉路径分隔符与 Windows 保留字符(用户在别的设备上解压也不会失败) */
    static String safeFileName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "theme";
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty() || ".".equals(out) || "..".equals(out)) out = "theme";
        return out.length() > 24 ? out.substring(0, 24) : out;
    }

    /** zip 条目内部也用安全名(中文名可以,但要挡掉路径分隔符) */
    private static String safeEntryName(String name) {
        return safeFileName(name).replace('/', '_').replace('\\', '_');
    }

    private static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        if (!f.exists()) return f;
        for (int i = 2; i < 1000; i++) {
            File n = new File(dir, base + "(" + i + ")" + ext);
            if (!n.exists()) return n;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }
}
