package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * 待传输的归档包:一个已经落到本地文件的字节包 + 它的清单。
 *
 * <p>为什么是 {@link File} 而不是 {@code byte[]}:
 * <ul>
 *   <li>导出包是"设置 + Room 库 + 主题"打成的归档,几 MB 起步,而 storage.to 单文件上限 25 GB
 *       —— 真按字节数组设计,大包会在内存里直接 OOM;</li>
 *   <li>上传要能<b>重试</b>(单次 PUT 失败重发)且 multipart 要按偏移切片,只有 {@code File} 能
 *       反复 seek 重读;流是一次性的,读完就没了;</li>
 *   <li>局域网侧要把字节喂给 HTTP 响应体,同样是文件流式读最省内存。</li>
 * </ul>
 * 因此约定:<b>归档一律先由调用方(:app 的归档器)落盘成临时文件,再交给本模块</b>;
 * 本模块只读不写归属,用完由调用方负责清理(见 {@link #file()} 的注释)。
 */
public final class SharePackage {

    private final File file;
    private final String fileName;
    private final String mimeType;
    private final ShareManifest manifest;

    private SharePackage(File file, String fileName, String mimeType, ShareManifest manifest) {
        this.file = file;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.manifest = manifest;
    }

    /**
     * @param file     归档文件(必须已存在、可读);<b>生命周期归调用方</b>,本模块不删它
     * @param fileName 对端看到的文件名(不含路径);空则退回 {@code file.getName()}
     * @param mimeType MIME;空则按扩展名猜,再不行用 {@code application/octet-stream}
     * @param manifest 清单;可为 null(在线/局域网两侧都不强制要,导入时按可读性兜底)
     */
    @NonNull
    public static SharePackage of(@NonNull File file, @Nullable String fileName,
                                  @Nullable String mimeType, @Nullable ShareManifest manifest) {
        if (file == null) throw new IllegalArgumentException("file == null");
        String name = (fileName == null || fileName.trim().isEmpty()) ? file.getName() : fileName.trim();
        String mime = (mimeType == null || mimeType.trim().isEmpty())
                ? guessMime(name) : mimeType.trim();
        return new SharePackage(file, name, mime, manifest);
    }

    @NonNull
    public File file() {
        return file;
    }

    /** 对端看到的文件名(已保证非空) */
    @NonNull
    public String fileName() {
        return fileName;
    }

    @NonNull
    public String mimeType() {
        return mimeType;
    }

    /** 清单;可能为 null */
    @Nullable
    public ShareManifest manifest() {
        return manifest;
    }

    /** 当前字节数(以磁盘实际大小为准,不信任清单里写的值) */
    public long sizeBytes() {
        return file.length();
    }

    /** 包是否可用:存在且是文件且非空 */
    public boolean isUsable() {
        try {
            return file.exists() && file.isFile() && file.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按扩展名猜 MIME(归档/配置都是这几类,不做完整 MIME 表) */
    @NonNull
    private static String guessMime(@Nullable String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".db") || n.endsWith(".sqlite")) return "application/octet-stream";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        return "application/octet-stream";
    }

    @NonNull
    @Override
    public String toString() {
        return "SharePackage{name=" + fileName + ", size=" + sizeBytes() + ", mime=" + mimeType + "}";
    }
}
