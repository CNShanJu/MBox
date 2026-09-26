package com.github.tvbox.osc.download.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 下载持久化文件工具(任务/档案大对象改存应用私有文件,不再进配置键值存储):
 * <ul>
 *   <li>路径:filesDir 下固定文件名(经 {@link DownloadManager#appContext});</li>
 *   <li>写:同目录 .tmp 原子 rename,避免半写;调用方自行保证串行/频率;</li>
 *   <li>编码 UTF-8。</li>
 * </ul>
 */
final class JsonFiles {

    private JsonFiles() {
    }

    /** 私有文件路径(调用时 DownloadManager.appContext 必须已注入,即 App init 之后) */
    static File privateFile(String name) {
        android.content.Context ctx = DownloadManager.appContext;
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), name);
    }

    static String readUtf8(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream is = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = is.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, "UTF-8");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 原子写:先写 .tmp 再 rename(同目录 rename 原子);失败静默(下轮重写) */
    static void writeUtf8Atomic(File target, String content) {
        if (target == null || content == null) return;
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        File tmp = new File(dir, target.getName() + ".tmp");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(content.getBytes("UTF-8"));
            os.flush();
            os.close();
            os = null;
            if (!tmp.renameTo(target)) {
                // rename 失败(极少):尝试直接覆盖
                try (FileOutputStream direct = new FileOutputStream(target)) {
                    direct.write(content.getBytes("UTF-8"));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException | RuntimeException ignored) {
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
