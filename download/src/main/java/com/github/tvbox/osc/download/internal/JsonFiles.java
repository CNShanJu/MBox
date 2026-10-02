package com.github.tvbox.osc.download.internal;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 下载持久化文件工具(任务/档案大对象改存应用私有文件,不再进配置键值存储):
 * <ul>
 *   <li>路径:filesDir 下固定文件名(经 {@link DownloadManager#appContext});</li>
 *   <li>写:AtomicFile 原子替换,避免半写;调用方自行保证串行/频率;</li>
 *   <li>编码 UTF-8。</li>
 * </ul>
 */
final class JsonFiles {

    /** 下载任务和档案是元数据；异常大文件不应在启动时整体读入堆内存。 */
    private static final long MAX_METADATA_BYTES = 32L * 1024L * 1024L;

    private JsonFiles() {
    }

    /** 私有文件路径(调用时 DownloadManager.appContext 必须已注入,即 App init 之后) */
    static File privateFile(String name) {
        android.content.Context ctx = DownloadManager.appContext;
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), name);
    }

    static String readUtf8(File f) {
        if (f == null) return null;
        android.util.AtomicFile atomic = new android.util.AtomicFile(f);
        try (java.io.FileInputStream is = atomic.openRead();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) {
                if (out.size() + (long) n > MAX_METADATA_BYTES) return null;
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 原子写：失败时由 AtomicFile 保留旧文件，避免直接覆盖造成下载记录半写。 */
    static void writeUtf8Atomic(File target, String content) {
        if (target == null || content == null) return;
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        android.util.AtomicFile atomic = new android.util.AtomicFile(target);
        FileOutputStream os = null;
        try {
            byte[] bytes = content.getBytes("UTF-8");
            if (bytes.length > MAX_METADATA_BYTES) return;
            os = atomic.startWrite();
            os.write(bytes);
            atomic.finishWrite(os);
            os = null;
        } catch (IOException | RuntimeException ignored) {
            if (os != null) atomic.failWrite(os);
        }
    }
}
