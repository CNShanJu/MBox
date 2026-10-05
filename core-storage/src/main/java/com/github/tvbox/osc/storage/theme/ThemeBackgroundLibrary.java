package com.github.tvbox.osc.storage.theme;

import android.content.Context;

import com.github.tvbox.osc.bean.theme.ThemeDef;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题背景图库:所有<b>自定义主题</b>的页面与开屏背景图都存这里,按内容 hash 命名。
 *
 * <p>为什么要单独一个目录 + hash 命名:
 * <ul>
 *   <li><b>去重</b>:用户给页面、开屏或不同主题选了同一张图,磁盘上只有一份(文件名就是内容摘要),
 *       主题 JSON 里存的是同一个 {@code ref};</li>
 *   <li><b>可回收</b>:主题被删、或图片引用被清除之后,如果<b>没有别的背景</b>再引用这个 ref,
 *       就把文件删掉(见 {@link #gc})."删除主题却把图留着"会让私有目录无限长大;</li>
 *   <li><b>可导出</b>:{@code ref} 是相对应用私有目录的路径({@code theme_bg/<hash>.webp}),
 *       导出成 zip 时按同样的相对路径打包,导入方解出来路径依然成立,JSON 一个字都不用改。</li>
 * </ul>
 *
 * <p>注意与"全局页面背景图"({@code filesDir/page_bg/},见 app 的 PageBackgroundStore)的区别:
 * 那是<b>用户给自己设的一张全局底图</b>(每个主题下都用它,直到用户改掉),这里是<b>主题自带的默认背景</b>
 * ({@code theme_default_bg} 的来源,只在用户没显式设过全局底图时生效)。两者互不覆盖:
 * 用户在"设置背景图"页显式设过图,主题背景就只是"换主题时的新默认",不会抢走用户的选择。
 */
public final class ThemeBackgroundLibrary {

    /** 存放目录名(相对 filesDir);{@code ref} 形如 {@code theme_bg/<hash>.webp} */
    public static final String DIR = "theme_bg";

    /** 文件名长度:sha256 十六进制取前 32 位(128 bit)。够长到不会撞,又不至于把文件名撑得没法看 */
    private static final int NAME_LEN = 32;
    /** Theme JSON is untrusted input. A ref may only name one image directly inside theme_bg. */
    private static final Pattern SAFE_IMAGE_NAME = Pattern.compile(
            "[A-Za-z0-9_-]+\\.(?:webp|png|jpg|jpeg)", Pattern.CASE_INSENSITIVE);

    private ThemeBackgroundLibrary() {
    }

    /** 目录(不存在则创建);创建失败返回 null */
    public static File dir(Context context) {
        if (context == null) return null;
        File d = new File(context.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) return null;
        try {
            File canonical = d.getCanonicalFile();
            return canonical.getParentFile().equals(context.getFilesDir().getCanonicalFile())
                    && canonical.isDirectory() ? canonical : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** {@code ref} → 主题图库中的文件；拒绝绝对路径、目录穿越与逃逸的符号链接。 */
    public static File resolve(Context context, String ref) {
        if (context == null) return null;
        String name = safeNameFromRef(ref);
        if (name.isEmpty()) return null;
        try {
            File library = new File(context.getFilesDir(), DIR).getCanonicalFile();
            if (!library.getParentFile().equals(context.getFilesDir().getCanonicalFile())) return null;
            File image = new File(library, name).getCanonicalFile();
            return library.equals(image.getParentFile()) ? image : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** {@code ref} → 绝对路径(文件不存在时返回空串,调用方据此退回"纯色") */
    public static String resolvePath(Context context, String ref) {
        File f = resolve(context, ref);
        return f != null && f.isFile() ? f.getAbsolutePath() : "";
    }

    /** 把主题里的 {@code ref} 规整成相对路径形式(容忍绝对路径/多余分隔符,便于手改过的主题包) */
    public static String normalizeRef(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace('\\', '/');
        if (s.isEmpty()) return "";
        // 绝对路径(旧写法/手改)只取文件名,归一到 theme_bg/ 下
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return safeImageName(s) ? DIR + "/" + s : "";
    }

    /**
     * 把一张已经压好的图(WebP)收进图库。
     *
     * <p>按内容 hash 命名:<b>已存在同样的图就直接复用</b>(删掉传进来的源文件),返回同一个 ref。
     * 耗时操作(读文件算摘要 + 落盘),务必在后台线程调用。
     *
     * @param src 源文件(通常是背景图导入流程产出的临时 WebP)
     * @return {@code ref}(相对 filesDir 的路径);失败返回空串
     */
    public static String register(Context context, File src) {
        if (context == null || src == null || !src.isFile() || src.length() <= 0) return "";
        File d = dir(context);
        if (d == null) return "";
        String hash;
        try {
            hash = sha256Hex(src);
        } catch (Throwable th) {
            return "";
        }
        if (hash.isEmpty()) return "";
        String name = hash.substring(0, Math.min(NAME_LEN, hash.length())) + extensionOf(src);
        File target = new File(d, name);
        if (target.isFile() && target.length() == src.length()) {
            // 同内容复用:源文件是导入流程的临时产物,收编后即可删掉
            deleteQuietly(src);
            return DIR + "/" + name;
        }
        File tmp = new File(d, name + ".tmp");
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable th) {
            deleteQuietly(tmp);
            return "";
        }
        if (tmp.length() <= 0) {
            deleteQuietly(tmp);
            return "";
        }
        // 原子换名:同名目标可能已被别的主题写过(内容相同),直接覆盖即可
        if (target.exists() && !target.delete()) {
            deleteQuietly(tmp);
            return "";
        }
        if (!tmp.renameTo(target)) {
            deleteQuietly(tmp);
            return "";
        }
        deleteQuietly(src);
        return DIR + "/" + name;
    }

    /**
     * 回收:删掉目录里<b>没有被任何主题引用</b>的图。
     *
     * <p>调用时机:主题被删除、主题背景从图片改成纯色/跟随默认、主题编辑保存之后。
     * 传进来的 {@code referencedRefs} 必须是"剩下所有主题当前的引用集合"(由 {@link ThemeStore} 汇总),
     * 所以这里<b>不做任何猜测</b>:不在集合里的文件就是垃圾。
     *
     * @return 删掉的文件数
     */
    public static int gc(Context context, Collection<String> referencedRefs) {
        File d = dir(context);
        if (d == null) return 0;
        Set<String> keep = new HashSet<>();
        if (referencedRefs != null) {
            for (String ref : referencedRefs) {
                String name = fileNameOf(ref);
                if (!name.isEmpty()) keep.add(name);
            }
        }
        File[] files = d.listFiles();
        if (files == null) return 0;
        int removed = 0;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            // 中断的写入残留一律清掉
            if (name.endsWith(".tmp")) {
                if (deleteQuietly(f)) removed++;
                continue;
            }
            if (keep.contains(name)) continue;
            if (deleteQuietly(f)) removed++;
        }
        return removed;
    }

    /** 目录当前总字节数(设置页/日志用) */
    public static long totalBytes(Context context) {
        File d = dir(context);
        if (d == null) return 0L;
        File[] files = d.listFiles();
        if (files == null) return 0L;
        long sum = 0L;
        for (File f : files) {
            if (f != null && f.isFile()) sum += f.length();
        }
        return sum;
    }

    /** 某主题定义引用的背景图 ref(没有/不是图片时为空串) */
    public static String refOf(ThemeDef def) {
        if (def == null || def.getBackground() == null) return "";
        if (!ThemeDef.Background.MODE_IMAGE.equals(def.getBackground().getMode())) return "";
        return safeNameFromRef(def.getBackground().getRef());
    }

    /** 开屏图片切到纯色后仍保留其引用，供下次切回时直接使用。 */
    public static String splashRefOf(ThemeDef def) {
        if (def == null || def.getSplashBackground() == null) return "";
        return safeNameFromRef(def.getSplashBackground().getRef());
    }

    private static String fileNameOf(String ref) {
        if (ref == null) return "";
        String s = ref.trim();
        if (s.startsWith(DIR + "/")) return safeNameFromRef(s);
        return safeImageName(s) ? s : "";
    }

    /** Strict stored ref validation; normalization of legacy archive names is separate. */
    public static boolean isSafeRef(String ref) {
        return !safeNameFromRef(ref).isEmpty();
    }

    private static String safeNameFromRef(String ref) {
        if (ref == null || !ref.startsWith(DIR + "/")) return "";
        String name = ref.substring(DIR.length() + 1);
        return safeImageName(name) ? name : "";
    }

    private static boolean safeImageName(String name) {
        return name != null && SAFE_IMAGE_NAME.matcher(name).matches();
    }

    private static String extensionOf(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return ".webp";
        String ext = name.substring(dot);
        // 与安全引用校验保持一致，避免注册后得到不能读取的 ref。
        return ".png".equals(ext) || ".jpg".equals(ext)
                || ".jpeg".equals(ext) || ".webp".equals(ext) ? ext : ".webp";
    }

    /** 文件内容的 sha256 十六进制(小写) */
    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static boolean deleteQuietly(File f) {
        try {
            return f != null && f.exists() && f.delete();
        } catch (Throwable th) {
            return false;
        }
    }
}
