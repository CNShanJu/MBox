package com.github.tvbox.osc.share.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * 在线导入时"引用 → 可下载文件"的解析结果。
 *
 * <p>为什么要有这么一层(而不是直接把用户粘的链接丢给下载器):storage.to 的分享链接
 * {@code https://storage.to/xxxx} 是<b>给人看的下载页</b>而非文件本身 —— 官方已经下线了
 * {@code /r/} 直链(见其 CLI 测试里"FileInfo JSON 不得再出现 raw_url"的断言),
 * 直接 GET 拿到的是 HTML。所以导入必须先"解析"再"下载",这两步的失败原因也完全不同:
 * 解析失败要说"这个链接不能直接导入"(用户可自己打开页面下载),
 * 下载失败才是"网络问题"。分开报,用户才知道该干什么。
 */
public final class ResolvedDownload {

    private final String directUrl;
    private final String fileName;
    private final long sizeBytes;
    private final String sourceRef;
    private final boolean experimental;

    public ResolvedDownload(@NonNull String directUrl, @Nullable String fileName, long sizeBytes,
                            @Nullable String sourceRef, boolean experimental) {
        this.directUrl = directUrl;
        this.fileName = fileName == null ? "" : fileName;
        this.sizeBytes = sizeBytes;
        this.sourceRef = sourceRef == null ? "" : sourceRef;
        this.experimental = experimental;
    }

    /** 可直接取字节的地址 */
    @NonNull
    public String directUrl() {
        return directUrl;
    }

    /** 服务端给的文件名(可能为空;为空时由调用方按 URL 末段兜底) */
    @NonNull
    public String fileName() {
        return fileName;
    }

    /** 文件大小;<=0 表示未知 */
    public long sizeBytes() {
        return sizeBytes;
    }

    /** 用户原始输入的引用(报错时回显,便于用户核对) */
    @NonNull
    public String sourceRef() {
        return sourceRef;
    }

    /**
     * 是否走了"非官方保证"的解析路径(如从分享页 HTML 里抠地址)。
     * <p>为 true 时调用方/界面应当把话说软一些(如"可能因平台改版失效"),
     * 并且在失败时引导用户改用"自己下载后本地导入"。这类路径本质上依赖对方页面结构,
     * 对方一改版就断 —— 官方 CLI 干脆不提供下载功能,原因就在这里。
     */
    public boolean isExperimental() {
        return experimental;
    }

    /** 建议的本地落盘名(去掉任何路径成分,只取末段) */
    @NonNull
    public String suggestedLocalName() {
        String n = fileName;
        if (n.isEmpty()) {
            n = lastSegment(directUrl);
        }
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        n = n.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.isEmpty() || n.contains("..")) n = "import_" + System.currentTimeMillis() + ".zip";
        return n;
    }

    @NonNull
    private static String lastSegment(@NonNull String url) {
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        int slash = u.lastIndexOf('/');
        return slash >= 0 ? u.substring(slash + 1) : u;
    }

    /** 便捷:按落盘名在给定目录里定位最终文件 */
    @NonNull
    public File targetFileIn(@NonNull File dir) {
        return new File(dir, suggestedLocalName());
    }

    @NonNull
    @Override
    public String toString() {
        return "ResolvedDownload{file=" + suggestedLocalName() + ", size=" + sizeBytes
                + ", experimental=" + experimental + "}";
    }
}
