package com.github.tvbox.osc.share.internal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.ShareErrorCode;
import com.github.tvbox.osc.share.ShareException;
import com.github.tvbox.osc.share.ShareManifest;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

/**
 * 归档包读写工具(ZIP)。包内至少有 {@link ShareManifest#ENTRY_MANIFEST}；
 * 全量备份还包含 {@link ShareManifest#ENTRY_PREFS} / {@link ShareManifest#ENTRY_ROOM}，
 * 按类别的配置包则由业务层选择其余条目。
 *
 * <p>为什么把解压放在本模块而不是让 :app 自己写一份:
 * <ul>
 *   <li><b>Zip Slip 只该有一份正确实现</b>(AGENTS §七:每个条目的最终落盘路径必须位于目标目录内)。
 *       :app 的 {@code RemoteServer.unzip} 已有一份,新链路再抄一份就是两个地方各自演化;</li>
 *   <li>上传的导入包是<b>同网段任意设备给的不可信字节</b>,这里是最该设防的一环:
 *       条目名可能含 {@code ../}、绝对路径、符号链接、超大解压比;</li>
 *   <li>校验逻辑(清单可读性 + 大小 + 校验和)与解压是同一件事的两面,分开写容易漏。</li>
 * </ul>
 *
 * <p>防御清单(全部在 {@link #extractTo} 里落实):
 * 条目名非空且不含 NUL;规范化后的 canonical 路径必须落在目标目录内;
 * 拒绝绝对路径与 {@code ..} 逃逸;单条目解压上限 {@link #MAX_ENTRY_BYTES};
 * 总解压上限 {@link #MAX_TOTAL_BYTES}(防 zip 炸弹);一律不用条目里的时间戳/权限。
 */
public final class ShareArchive {

    /** 单条目解压上限(归档里最大的是 Room 库,给到 512 MB 已远超实际) */
    public static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    /** 解压总量上限(整包上限;设置+库+主题实测几 MB 量级) */
    public static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    /** 清单条目读取上限(它只是个 JSON 头部,几 KB) */
    public static final long MAX_MANIFEST_BYTES = 256L * 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int BUF = 8192;

    private ShareArchive() {
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** 是不是 ZIP(按魔数判断;不看扩展名,对端可能改了名) */
    public static boolean looksLikeZip(@Nullable File file) {
        if (file == null || !file.isFile()) return false;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int b0 = in.read();
            int b1 = in.read();
            return b0 == 'P' && b1 == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /** 读归档里的清单;没有条目/JSON 非法/超限都返回 null(调用方按需降级,不阻断) */
    @Nullable
    public static ShareManifest readManifest(@Nullable File archive) {
        if (!looksLikeZip(archive)) return null;
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(ShareManifest.ENTRY_MANIFEST);
            if (entry == null || entry.isDirectory()) return null;
            if (entry.getSize() > MAX_MANIFEST_BYTES) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] raw = readCapped(in, MAX_MANIFEST_BYTES);
                return ShareManifest.fromJson(new String(raw, UTF8));
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 导入前的完整性校验。通过则正常返回;不通过抛 {@link ShareException}
     * (码已分好类,界面直接按码说话)。
     *
     * @param manifest 期望清单;可为 null(跳过"schema 可读性"与"校验和"两项;
     *                 大小/可解压性仍然会查)
     */
    public static void check(@Nullable File archive, @Nullable ShareManifest manifest)
            throws ShareException {
        if (archive == null || !archive.isFile()) {
            throw new ShareException(ShareErrorCode.IO, "归档文件不存在");
        }
        if (archive.length() <= 0) {
            throw new ShareException(ShareErrorCode.IO, "归档文件为空");
        }
        if (!looksLikeZip(archive)) {
            throw new ShareException(ShareErrorCode.PARSE, "不是有效的归档包(需要 ZIP)");
        }
        if (manifest != null) {
            if (!manifest.isReadableByCurrentVersion()) {
                throw new ShareException(ShareErrorCode.PARSE,
                        "归档格式版本(" + manifest.schema() + ")高于当前应用可读范围,请先升级应用");
            }
            if (manifest.sizeBytes() > 0 && manifest.sizeBytes() != archive.length()) {
                throw new ShareException(ShareErrorCode.CHECKSUM,
                        "归档大小与清单不符(可能传输不完整)");
            }
            if (!manifest.checksumSha256().isEmpty()) {
                // sha256Hex 抛的是 IOException(它是通用文件工具),这里转成 ShareException:
                // "算校验和时读不动文件"对调用方就是一次导入失败,不该多一种异常类型要处理
                String actual;
                try {
                    actual = sha256Hex(archive);
                } catch (IOException e) {
                    throw new ShareException(ShareErrorCode.IO,
                            "无法读取归档以校验完整性", e);
                }
                if (!actual.equalsIgnoreCase(manifest.checksumSha256())) {
                    throw new ShareException(ShareErrorCode.CHECKSUM, "归档校验和不一致");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 校验和
    // ------------------------------------------------------------------

    /** 文件 SHA-256(小写十六进制) */
    @NonNull
    public static String sha256Hex(@NonNull File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 不可用", e);
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 打包 / 解包
    // ------------------------------------------------------------------

    /**
     * 把若干文件打成 zip({@code entries} 的 key 是归档内条目名)。
     * 归档内条目名一律由调用方给定、且必须是单段相对路径(下面会校验),
     * 避免把绝对路径写进归档,给对端造成解压风险。
     */
    @NonNull
    public static File zip(@NonNull Map<String, File> entries, @NonNull File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建归档目录: " + parent);
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, File> e : entries.entrySet()) {
                String name = e.getKey();
                File src = e.getValue();
                if (name == null || name.isEmpty() || name.indexOf('/') >= 0
                        || name.indexOf('\\') >= 0 || name.contains("..")) {
                    throw new IOException("非法归档条目名: " + name);
                }
                if (src == null || !src.isFile()) continue;
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = new BufferedInputStream(new FileInputStream(src))) {
                    byte[] buf = new byte[BUF];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
        return out;
    }

    /**
     * 解压到目标目录(Zip Slip 防护,见类注释)。
     * 任一条目不合法即抛 {@link SecurityException} 且<b>不继续</b>(调用方应先解到临时目录再落地,
     * 保证失败不留半套数据)。
     */
    public static void extractTo(@NonNull File archive, @NonNull File destDir) throws IOException {
        extractTo(archive, destDir, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES);
    }

    /** Domain-specific extraction limits may be stricter than the full-backup defaults. */
    public static void extractTo(@NonNull File archive, @NonNull File destDir,
                                 long maxEntryBytes, long maxTotalBytes) throws IOException {
        if (maxEntryBytes <= 0 || maxTotalBytes <= 0) throw new IOException("归档解压上限无效");
        maxEntryBytes = Math.min(maxEntryBytes, MAX_ENTRY_BYTES);
        maxTotalBytes = Math.min(maxTotalBytes, MAX_TOTAL_BYTES);
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目标目录: " + destDir);
        }
        String destCanonical = destDir.getCanonicalPath();
        long total = 0L;
        Set<String> seenPaths = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                if (seenPaths.size() >= 1024) throw new SecurityException("归档条目数量超限");
                ZipEntry entry = it.nextElement();
                String name = entry.getName();
                if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
                    throw new SecurityException("非法归档条目名");
                }
                // 绝对路径/盘符直接拒(Windows 风格 C:\ 与 /etc 都拦)
                if (name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
                    throw new SecurityException("归档条目为绝对路径: " + name);
                }
                File target = new File(destDir, name);
                String targetCanonical = target.getCanonicalPath();
                if (!targetCanonical.equals(destCanonical)
                        && !targetCanonical.startsWith(destCanonical + File.separator)) {
                    throw new SecurityException("归档条目逃出目标目录: " + name);
                }
                if (!seenPaths.add(targetCanonical)) throw new SecurityException("归档包含重复条目: " + name);
                // 落盘一律用规范化后的路径:条目名里的 "a/.." 在 Linux 上会让下面的
                // getParentFile().mkdirs() 因 dest/a 不存在而失败(ENOENT),把"重复条目"报成文件错误。
                target = new File(targetCanonical);
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IOException("无法创建目录: " + name);
                    }
                    continue;
                }
                if (entry.getSize() > maxEntryBytes) {
                    throw new SecurityException("归档条目过大: " + name);
                }
                File p = target.getParentFile();
                if (p != null && !p.exists() && !p.mkdirs()) {
                    throw new IOException("无法创建父目录: " + name);
                }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new FileOutputStream(target)) {
                    byte[] buf = new byte[BUF];
                    int n;
                    long written = 0L;
                    CRC32 crc = new CRC32();
                    while ((n = in.read(buf)) > 0) {
                        written += n;
                        total += n;
                        if (written > maxEntryBytes) {
                            throw new SecurityException("归档条目解压超限: " + name);
                        }
                        if (total > maxTotalBytes) {
                            throw new SecurityException("归档解压总量超限");
                        }
                        crc.update(buf, 0, n);
                        os.write(buf, 0, n);
                    }
                    if ((entry.getSize() >= 0 && entry.getSize() != written)
                            || (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue())) {
                        throw new IOException("归档条目校验失败: " + name);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 带上限读取(超限抛 IOException,防被超大条目拖爆内存) */
    @NonNull
    private static byte[] readCapped(@NonNull InputStream in, long cap) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[BUF];
        int n;
        long total = 0L;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > cap) throw new IOException("读取超限");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 便利用:按扩展名给个默认归档名(时间戳由调用方决定) */
    @NonNull
    public static String safeEntryName(@Nullable String raw) {
        if (raw == null) return "";
        String n = raw.trim().replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.isEmpty() || n.equals(".") || n.equals("..") || n.indexOf('\0') >= 0) return "";
        return n.toLowerCase(Locale.US);
    }
}
