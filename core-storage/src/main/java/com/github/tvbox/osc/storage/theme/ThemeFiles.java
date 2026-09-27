package com.github.tvbox.osc.storage.theme;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 主题文件的小工具:UTF-8 文本读写 + 原子落盘。
 *
 * <p>为什么原子写:主题文件是"用户的资产"——写一半断电/被杀,下次启动就会读到半截 JSON,
 * 表现为"主题莫名消失了"。所以一律先写同目录 {@code .tmp} 再 rename(同卷 rename 是原子的)。
 *
 * <p>为什么不用 {@code java.nio.file.Files}:那是 API 26 的类,本项目 minSdk 24,
 * 主代码里用它会 NoClassDefFoundError(单测在 JVM 上跑得通,反而掩盖问题)。
 */
final class ThemeFiles {

    private ThemeFiles() {
    }

    /** 读 UTF-8 文本;不存在/读失败返回 null */
    static String readUtf8(File f) {
        if (f == null || !f.isFile()) return null;
        try (FileInputStream is = new FileInputStream(f)) {
            long len = f.length();
            byte[] data = new byte[(int) Math.max(0, Math.min(len, Integer.MAX_VALUE))];
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

    /**
     * 原子写 UTF-8 文本。
     *
     * @return 是否成功(失败时已清理 .tmp,调用方据此给用户报错)
     */
    static boolean writeUtf8Atomic(File target, String content) {
        if (target == null || content == null) return false;
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
        File tmp = new File(dir, target.getName() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(content.getBytes("UTF-8"));
            os.flush();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            return false;
        }
        if (target.exists() && !target.delete()) {
            deleteQuietly(tmp);
            return false;
        }
        if (!tmp.renameTo(target)) {
            deleteQuietly(tmp);
            return false;
        }
        return true;
    }

    /** 把输入流整份拷到目标文件(覆盖);调用方负责关输入流 */
    static boolean copyToFile(InputStream in, File target) {
        if (in == null || target == null) return false;
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
        try (OutputStream os = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            return true;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            return false;
        }
    }

    static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
