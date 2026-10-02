package com.github.tvbox.osc.storage.theme;

import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.bean.theme.ThemeJson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 主题包的导出与导入(一个主题一份,便于发给别人用)。
 *
 * <h3>两种形态</h3>
 * <ul>
 *   <li><b>不带背景图</b> → 就一个 {@code .json}(纯文本,微信里直接发文件即可,
 *       甚至可以复制文本粘贴给别人);</li>
 *   <li><b>带背景图</b> → 打成一个 {@code .zip},里面是
 *       <pre>
 *         暗夜紫.json
 *         theme_bg/3f2a....webp
 *       </pre>
 *       zip 内部的目录结构与 App 私有目录<b>完全一致</b>:主题 JSON 里那行背景图路径
 *       ({@code theme_bg/<hash>.webp})解出来就是同一个位置,导入方不需要改写 JSON 的任何字段。</li>
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
    /** zip 里最多看多少个条目(正常就 2 个:json + 图) */
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

        File image = def.hasBackgroundImage() ? resolveImage(def) : null;

        if (image == null) {
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

            // zip 内部路径与 App 私有目录一致:JSON 里那行 theme_bg/<hash>.webp 解出来就在同一个位置
            zos.putNextEntry(new ZipEntry(ThemeBackgroundLibrary.normalizeRef(def.getBackground().getRef())));
            try (InputStream in = new FileInputStream(image)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
            }
            zos.closeEntry();
        } catch (Throwable th) {
            ThemeFiles.deleteQuietly(out);
            return new ExportResult(null, "导出失败: " + th.getClass().getSimpleName(), false);
        }
        return new ExportResult(out, null, true);
    }

    /** 主题包建议文件名(标题栏/分享文案里显示用) */
    public static String suggestedFileName(ThemeDef def) {
        if (def == null) return "theme";
        return safeFileName(def.getName()) + (def.hasBackgroundImage() ? EXT_ZIP : EXT_JSON);
    }

    private static File resolveImage(ThemeDef def) {
        File f = ThemeBackgroundLibrary.resolve(ThemeStore.context(), def.getBackground().getRef());
        return (f != null && f.isFile()) ? f : null;
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
        // 粘贴进来的 JSON 若声明了背景图,那张图在本机大概率不存在:退化成"跟随默认"并提示
        List<String> warnings = new ArrayList<>(r.warnings);
        if (def.hasBackgroundImage() && ThemeBackgroundLibrary.resolvePath(ThemeStore.context(),
                def.getBackground().getRef()).isEmpty()) {
            warnings.add("主题包里的背景图不见了,已改为跟随默认背景");
            def.getBackground().setMode(ThemeDef.Background.MODE_DEFAULT);
            def.getBackground().setRef("");
        }
        return new ImportResult(def, null, warnings, false);
    }

    private static ImportResult importPlainJson(File file) {
        String text = ThemeFiles.readUtf8(file);
        if (text == null || text.trim().isEmpty()) return new ImportResult(null, "文件内容读不出来", null, false);
        return importText(text);
    }

    private static ImportResult importZip(File zip) {
        ThemeDef def = null;
        List<String> warnings = new ArrayList<>();
        boolean withImage = false;
        File tmpImage = null;
        File cacheDir = ThemeStore.cacheDir();
        if (cacheDir == null) return new ImportResult(null, "存储不可用,请重启应用后再试", null, false);

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (++seen > MAX_ENTRIES) {
                    warnings.add("主题包里的文件过多,只读了前 " + MAX_ENTRIES + " 个");
                    break;
                }
                if (entry.isDirectory()) continue;
                // 条目名只用来"认出这是不是主题 JSON / 是不是那张背景图",
                // 绝不拿来当落盘路径(防 Zip Slip,见类注释)
                String entryName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);

                if (def == null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(EXT_JSON)) {
                    String text = readEntryText(zis);
                    if (text == null) return new ImportResult(null, "主题包里的 JSON 读不出来", null, false);
                    ThemeJson.Result r = ThemeJson.parse(text);
                    if (r.def == null) return new ImportResult(null, r.error, null, false);
                    def = r.def;
                    warnings.addAll(r.warnings);
                    continue;
                }
                if (def != null && def.hasBackgroundImage() && tmpImage == null
                        && fileName.equals(imageFileNameOf(def))) {
                    File tmp = new File(cacheDir, "theme_import_" + System.currentTimeMillis() + ".webp");
                    if (copyEntry(zis, tmp)) tmpImage = tmp;
                }
            }
        } catch (Throwable th) {
            ThemeFiles.deleteQuietly(tmpImage);
            return new ImportResult(null, "主题包解析失败,可能文件已损坏", null, false);
        }

        if (def == null) {
            ThemeFiles.deleteQuietly(tmpImage);
            return new ImportResult(null, "主题包里没有找到主题文件(.json)", null, false);
        }
        def.materialize(ThemeStore.builtinInput(def.getType()));
        def.materializeShapes(ThemeStore.builtinShapes(def.getType()));

        // 背景图:收进图库(按内容 hash 去重,重复的图直接复用)
        if (tmpImage != null) {
            String ref = ThemeStore.registerBackground(tmpImage);
            if (ref.isEmpty()) {
                warnings.add("背景图导入失败,已改为跟随默认背景");
                def.getBackground().setMode(ThemeDef.Background.MODE_DEFAULT);
                def.getBackground().setRef("");
            } else {
                def.getBackground().setMode(ThemeDef.Background.MODE_IMAGE);
                def.getBackground().setRef(ref);
                withImage = true;
            }
        } else if (def.hasBackgroundImage()) {
            warnings.add("主题包里的背景图不见了,已改为跟随默认背景");
            def.getBackground().setMode(ThemeDef.Background.MODE_DEFAULT);
            def.getBackground().setRef("");
        }
        return new ImportResult(def, null, warnings, withImage);
    }

    private static String imageFileNameOf(ThemeDef def) {
        String ref = ThemeBackgroundLibrary.normalizeRef(def.getBackground().getRef());
        return ref.substring(ref.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String readEntryText(InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ENTRY_BYTES) return null;
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
